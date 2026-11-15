package com.zzzyy.healthcoach.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 小米运动健康扫码登录。
 *
 * 移植自 `mi_fitness/auth/qr.py` + `auth/_helpers.py`：
 *   1. GET account.xiaomi.com/longPolling/loginUrl → 拿到二维码图片 / 长轮询地址
 *   2. 长轮询 lp 直到用户扫码确认（服务端会挂起请求直到有动作）
 *   3. 从响应里取 userId / cUserId / ssecurity / passToken / location
 *   4. 请求 location，从 Set-Cookie 里取 serviceToken
 */
class MiAuth(private val client: OkHttpClient = defaultClient()) {

    data class QrSession(
        val imageUrl: String,
        val loginUrl: String,
        val longPollingUrl: String,
        val timeoutSeconds: Int,
    )

    /** 步骤 1：获取二维码。 */
    fun requestQr(): QrSession {
        val now = System.currentTimeMillis()
        val url = ("https://account.xiaomi.com/longPolling/loginUrl" +
            "?_qrsize=480" +
            "&qs=%3Fsid%3Dmiothealth%26_json%3Dtrue" +
            "&callback=https%3A%2F%2Fsts-hlth.io.mi.com%2Fhealthapp%2Fsts" +
            "&_hasLogo=false" +
            "&sid=miothealth" +
            "&serviceParam=" +
            "&_locale=zh_CN" +
            "&_dc=$now").toHttpUrl()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LOGIN_USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("获取二维码失败 HTTP ${response.code}: ${text.take(120)}")
            }
            val data = parseMiResponse(text)
            val imageUrl = data.optString("qr")
            val pollingUrl = data.optString("lp")
            if (imageUrl.isBlank() || pollingUrl.isBlank()) {
                throw IOException("获取二维码失败：响应缺少 qr/lp 字段")
            }
            return QrSession(
                imageUrl = imageUrl,
                loginUrl = data.optString("loginUrl"),
                longPollingUrl = pollingUrl,
                timeoutSeconds = data.optInt("timeout", 280),
            )
        }
    }

    /**
     * 步骤 2+3+4：长轮询等待扫码，成功后返回凭证。
     *
     * @param onTick 每轮轮询回调（用于更新 UI 上的等待提示）。
     */
    suspend fun awaitScan(session: QrSession, onTick: (Int) -> Unit = {}): MiToken =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + session.timeoutSeconds * 1000L
            var waited = 0

            while (System.currentTimeMillis() < deadline) {
                val data = try {
                    pollOnce(session.longPollingUrl)
                } catch (e: IOException) {
                    // 长轮询被服务端挂起后断开是常态，等一轮再试
                    delay(2000)
                    waited += 2
                    onTick(waited)
                    continue
                }
                if (data == null) {
                    delay(2000)
                    waited += 2
                    onTick(waited)
                    continue
                }

                val userId = data.optString("userId")
                if (userId.isBlank()) {
                    delay(2000)
                    waited += 2
                    onTick(waited)
                    continue
                }

                return@withContext buildToken(data)
            }
            throw IOException("二维码扫码超时，请重新获取")
        }

    /** 长轮询一次。返回 null 表示「还没扫」。 */
    private fun pollOnce(longPollingUrl: String): JSONObject? {
        val request = Request.Builder()
            .url(longPollingUrl)
            .header("User-Agent", LOGIN_USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            return try {
                parseMiResponse(text)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 步骤 4：拿 serviceToken。 */
    private fun buildToken(data: JSONObject): MiToken {
        val token = MiToken(
            userId = data.optString("userId"),
            cUserId = data.optString("cUserId"),
            ssecurity = data.optString("ssecurity"),
            passToken = data.optString("passToken"),
            serviceToken = "",
            deviceId = data.optString("deviceId"),
        )

        val location = data.optString("location")
        if (location.isNotBlank()) {
            val serviceToken = fetchServiceToken(location)
            return token.copy(serviceToken = serviceToken)
        }
        return token
    }

    private fun fetchServiceToken(location: String): String {
        val request = Request.Builder()
            .url(location)
            .header("User-Agent", LOGIN_USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val cookies = response.headers.values("set-cookie")
            cookies.firstOrNull { it.contains("serviceToken=") }?.let { cookie ->
                val value = cookie.substringAfter("serviceToken=").substringBefore(';')
                if (value.isNotBlank()) return value
            }
            // 兜底：从重定向地址的查询串里取
            val finalUrl = response.request.url
            finalUrl.queryParameter("serviceToken")?.let { if (it.isNotBlank()) return it }
        }
        throw IOException("未能获取 serviceToken")
    }

    /**
     * 用已保存的 passToken 换取新凭证（**免扫码**）。
     *
     * serviceToken / ssecurity 会过期，但 passToken 可以反复换新，
     * 所以只要第一次扫码拿��� passToken，之后就永远不用再扫。
     *
     * 移植自 `mi_fitness/auth/passtoken.py`：
     *   1. 把 passToken / deviceId / userId 写成 cookie
     *   2. GET account.xiaomi.com/pass/serviceLogin?_json=true&sid=miothealth
     *   3. 响应里有 ssecurity / cUserId / nonce / location
     *   4. 请求 location + &clientSign=quote(base64(sha1("nonce=<nonce>&<ssecurity>")))
     *   5. 从 Set-Cookie 里取 serviceToken
     */
    suspend fun loginWithPassToken(
        passToken: String,
        userId: String,
        deviceId: String,
    ): MiToken = withContext(Dispatchers.IO) {
        if (passToken.isBlank() || userId.isBlank()) {
            throw IOException("缺少 passToken 或 userId")
        }
        val device = deviceId.ifBlank { "an_${randomHex(16)}" }

        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=miothealth")
            .header("User-Agent", LOGIN_USER_AGENT)
            .header("Cookie", buildString {
                append("passToken=$passToken; deviceId=$device; userId=$userId")
            })
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("serviceLogin 失败 HTTP ${response.code}: ${text.take(160)}")
            }
            val data = parseMiResponse(text)
            val ssecurity = data.optString("ssecurity")
            if (ssecurity.isBlank()) {
                throw IOException("passToken 已失效（未返回 ssecurity），需要重新扫码登录")
            }
            val nonce = data.optString("nonce")
            val location = data.optString("location")

            var serviceToken = ""
            if (location.isNotBlank()) {
                val signText = "nonce=$nonce&$ssecurity"
                val digest = MessageDigest.getInstance("SHA-1")
                    .digest(signText.toByteArray(Charsets.UTF_8))
                val clientSign = URLEncoder.encode(MiCrypto.b64encode(digest), "UTF-8")
                val fullUrl = "$location&clientSign=$clientSign"
                serviceToken = fetchServiceToken(fullUrl)
            }
            if (serviceToken.isBlank()) {
                throw IOException("未能获取 serviceToken")
            }

            MiToken(
                userId = data.optString("userId").ifBlank { userId },
                cUserId = data.optString("cUserId"),
                serviceToken = serviceToken,
                ssecurity = ssecurity,
                passToken = passToken,
                deviceId = device,
            )
        }
    }

    private fun randomHex(length: Int): String {
        val bytes = ByteArray(length / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LOGIN_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS) // 长轮询要等很久
            .followRedirects(false)
            .build()

        /** 小米接口的响应带 `&&&START&&&` 前缀。 */
        fun parseMiResponse(text: String): JSONObject {
            val body = text.removePrefix("&&&START&&&")
            return JSONObject(body)
        }
    }
}
