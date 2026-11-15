package com.zzzyy.healthcoach.data.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 小米运动健康云端取数客户端。
 *
 * 对应 `.work/mifit_client.py` 的 `MiHealth` 类：
 *   GET https://hlth.io.mi.com/app/v1/data/get_fitness_data_by_time
 *   data = 加密后的 {key, reverse, startTime, endTime, next_key}
 *
 * 注意：`hlth.io.mi.com` 的响应是 base64(RC4(JSON))，解密后是 {code, message, result}。
 */
class MiCloudClient(
    private val token: MiToken,
    private val client: OkHttpClient = defaultClient(),
) {
    /** 本客户端实例打出去的网络请求数（用于观察「一次同步到底发了多少请求」）。 */
    var requestCount: Int = 0
        private set

    /** 云端返回的一条原始记录。 */
    data class Record(
        val time: Long,
        val value: JSONObject,
    )

    /** 一次查询的结果。 */
    data class Page(
        val records: List<Record>,
        val hasMore: Boolean,
        val nextKey: String?,
    )

    class AuthException(message: String) : IOException(message)

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to "Android-17-3.59.2-realme",
        "region_tag" to "cn",
        "Cookie" to "cUserId=${token.cUserId};serviceToken=${token.serviceToken};locale=zh_CN",
    )

    /** 调用一个 hlth.io.mi.com 接口，返回解密后的 JSON。 */
    suspend fun call(path: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // DNS / 连接这类瞬时故障在这台机器上实测会间歇出现（连续请求时尤甚），
        // 一次失败就跳过某个 key，会让用户看到「某几项没数据」却不知道为什么。
        // 这里对**可恢复的**网络异常退避重试两次：1.5 秒、4 秒。
        var attempt = 0
        while (true) {
            try {
                return@withContext callOnce(path, payload)
            } catch (e: java.net.UnknownHostException) {
                if (attempt >= MAX_NET_RETRIES) throw e
                val wait = RETRY_BACKOFF_MS[attempt]
                android.util.Log.w(TAG, "DNS 解析失败，${wait}ms 后重试（第 ${attempt + 1} 次）：${e.message}")
                attempt++
                kotlinx.coroutines.delay(wait)
            } catch (e: java.net.SocketTimeoutException) {
                if (attempt >= MAX_NET_RETRIES) throw e
                val wait = RETRY_BACKOFF_MS[attempt]
                android.util.Log.w(TAG, "连接超时，${wait}ms 后重试（第 ${attempt + 1} 次）")
                attempt++
                kotlinx.coroutines.delay(wait)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    private suspend fun callOnce(path: String, payload: JSONObject): JSONObject {
        requestCount++
        val nonce = MiCrypto.generateNonce(0)
        val plain = payload.toString()
        val params = MiCrypto.encryptQueryParams(
            method = "GET",
            path = path,
            params = mapOf("data" to plain),
            nonce = nonce,
            ssecurity = token.ssecurity,
        )

        val urlBuilder = (("https://hlth.io.mi.com" + path).toHttpUrl()).newBuilder()
        params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .apply { headers().forEach { (key, value) -> header(key, value) } }
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) {
                throw AuthException("登录已失效（401），请重新扫码登录")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(160)}")
            }
            val plainJson = MiCrypto.decryptResponsePayload(body, nonce, token.ssecurity)
            val json = JSONObject(plainJson)
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IOException("接口返回错误 code=$code ${json.optString("message")}")
            }
            json
        }
    }

    /**
     * 按 key 取一段时间的健康数据。
     *
     * @param key 指标 key（steps / calories / valid_stand / heart_rate / spo2 / sleep …）
     */
    suspend fun fitness(
        key: String,
        startTime: Long,
        endTime: Long,
        reverse: Boolean = false,
        nextKey: String? = null,
    ): Page {
        val payload = JSONObject()
            .put("key", key)
            .put("reverse", reverse)
            .put("startTime", startTime)
            .put("endTime", endTime)
        if (!nextKey.isNullOrBlank()) payload.put("next_key", nextKey)

        val result = call(PATH_FITNESS_BY_TIME, payload).optJSONObject("result") ?: JSONObject()
        val list = result.optJSONArray("data_list") ?: return Page(emptyList(), false, null)

        val records = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val time = item.optLong("time")
                val raw = item.optString("value")
                val value = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    continue
                }
                add(Record(time, value))
            }
        }
        Log.d(TAG, "fitness(key=$key, reverse=$reverse) → ${records.size} 条, hasMore=${result.optBoolean("has_more", false)}")
        return Page(
            records = records,
            hasMore = result.optBoolean("has_more", false),
            nextKey = result.optString("next_key").takeIf { it.isNotBlank() },
        )
    }

    /**
     * 增量拉取：按时间倒序往前翻页，直到覆盖 [since] 之前。
     *
     * 与 `.work/mifit_export.py` 的 `pull()` 语义一致。
     */
    /**
     * 按时间倒序分页拉取，直到越过 [since] 或没有更多。
     *
     * ⚠️ [maxPages] 是**真实存在的数据上限**：心率一天约 686 条，
     * 页大小由服务端决定，拉到 40 页左右就到 40~50 天。
     * 天数拉得长就必须把上限放大，否则会**静默少数据**（不报错，只是缺）。
     * 所以触顶时会打 warn 日志，不装没事。
     */
    suspend fun pullSince(
        key: String,
        since: Long,
        maxPages: Int = 40,
    ): List<Record> {
        val collected = mutableListOf<Record>()
        var nextKey: String? = null
        var pages = 0
        var exhausted = false

        while (pages < maxPages) {
            val page = fitness(key, 0, 0, reverse = true, nextKey = nextKey)
            if (page.records.isEmpty()) {
                exhausted = true
                break
            }
            collected += page.records
            pages++
            // 节流：小米 App 自己是低频增量同步，我们连续翻页时不要「背靠背」发请求，
            // 每页之间留 250ms，降低被风控判定为爬虫的概率。
            kotlinx.coroutines.delay(PAGE_INTERVAL_MS)
            if (page.records.minOf { it.time } <= since) {
                exhausted = true
                break
            }
            nextKey = page.nextKey
            if (nextKey == null || !page.hasMore) {
                exhausted = true
                break
            }
        }
        if (!exhausted) {
            android.util.Log.w(
                TAG,
                "pullSince($key) 触到 $maxPages 页上限，可能**少数据**（已取 ${collected.size} 条，" +
                    "最早 ${collected.minOfOrNull { it.time }?.let { java.util.Date(it * 1000) }}，" +
                    "目标起点 ${java.util.Date(since * 1000)}）",
            )
        } else {
            android.util.Log.d(TAG, "pullSince($key) 完成：$pages 页 ${collected.size} 条")
        }
        return collected.filter { it.time >= since }
    }

    companion object {
        private const val TAG = "HealthCoach.Cloud"
        /** 翻页之间的最小间隔，见 pullSince 里的说明。 */
        private const val PAGE_INTERVAL_MS = 250L
        private const val MAX_NET_RETRIES = 2
        private val RETRY_BACKOFF_MS = longArrayOf(1500L, 4000L)

        const val PATH_FITNESS_BY_TIME = "/app/v1/data/get_fitness_data_by_time"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
