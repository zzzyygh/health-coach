package com.zzzyy.healthcoach.data

import android.content.Context
import com.zzzyy.healthcoach.data.analysis.nightHeartRateStats
import com.zzzyy.healthcoach.data.cloud.MiCloudClient
import com.zzzyy.healthcoach.data.cloud.MiTokenStore
import com.zzzyy.healthcoach.data.local.MiHealthLocalDb
import com.zzzyy.healthcoach.data.local.RootShell
import com.zzzyy.healthcoach.data.model.DataSource
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.HeartRateSummary
import com.zzzyy.healthcoach.data.model.SleepSummary
import com.zzzyy.healthcoach.data.model.Spo2Summary
import com.zzzyy.healthcoach.data.model.StressSummary
import com.zzzyy.healthcoach.data.store.HealthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 数据仓库：把「本地 root 直读」和「云端接口」两条路的数据汇总到一起。
 *
 * 两条路的分工：
 *   - 本地：指标最全（静息心率、HRV、压力、睡眠分段都在），但要 root；
 *   - 云端：不需要 root，能跨设备，但**没有静息心率这个 key**。
 * 合并时按字段取并集，不整条覆盖。
 */
class HealthRepository(context: Context) {

    private val store = HealthStore(context)
    private val tokenStore = MiTokenStore(context)
    private val localDb = MiHealthLocalDb(context)

    data class SyncReport(
        val localDays: Int = 0,
        val cloudDays: Int = 0,
        val totalDays: Int = 0,
        val localError: String? = null,
        val cloudError: String? = null,
        val rootAvailable: Boolean = false,
        val tokenUsable: Boolean = false,
        /** 本次云端实际拉到的原始记录条数（0 通常意味着手机上的小米运动健康还没同步） */
        val cloudRecords: Int = 0,
    )

    fun loadDays(): List<HealthDay> = store.loadDays()

    /** 落盘数据版本是否落后（落后就要重新同步一次，否则新字段永远是 null）。 */
    fun needsUpgrade(): Boolean = store.needsUpgrade()

    fun loadFeedback() = store.loadFeedback()

    fun loadInterventions() = store.loadInterventions()

    fun loadAdvices() = store.loadAdvices()

    fun saveAdvice(record: com.zzzyy.healthcoach.data.model.AdviceRecord) =
        store.saveAdvice(record)

    fun clearAdvices() = store.clearAdvices()

    fun addIntervention(entry: com.zzzyy.healthcoach.data.model.InterventionEntry) =
        store.addIntervention(entry)

    fun deleteIntervention(entry: com.zzzyy.healthcoach.data.model.InterventionEntry) =
        store.deleteIntervention(entry)

    fun saveFeedback(entry: com.zzzyy.healthcoach.data.model.FeedbackEntry) =
        store.saveFeedback(entry)

    fun token() = tokenStore.load()

    fun hasToken(): Boolean = tokenStore.load().isUsable

    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) { RootShell.isAvailable() }

    /** 拉取最近 [days] 天的数据并合并入库。 */
    suspend fun sync(
        days: Int = 30,
        incrementalSince: Long = 0L,
        cloudOnly: Boolean = false,
    ): SyncReport = withContext(Dispatchers.IO) {
        val sinceDate = daysAgoIso(days)
        android.util.Log.i(TAG, "sync 开始：范围 $days 天（since=$sinceDate）")
        var localDays = 0
        var cloudDays = 0
        var localError: String? = null
        var cloudError: String? = null
        var cloudRecords = 0

        // ── 本地 ──────────────────────────────────────────────
        val rootAvailable = RootShell.isAvailable()
        if (cloudOnly) {
            // 仅云端模式：故意不读本地库。
            // 给无 root 用户一个等效路径，也用来验证「云端单独能撑到什么程度」。
            localError = "仅云端模式：已跳过本地库"
            android.util.Log.i(TAG, "sync：仅云端模式，跳过本地库读取")
        } else if (rootAvailable) {
            try {
                val local = localDb.readDays(sinceDate)
                localDays = local.size
                if (local.isNotEmpty()) store.mergeDays(local)
            } catch (e: Exception) {
                localError = e.message ?: e.javaClass.simpleName
            }
        } else {
            localError = "没有 root 权限（Magisk 未授权本应用）"
        }

        // ── 云端 ──────────────────────────────────────────────
        val token = tokenStore.load()
        val tokenUsable = token.isUsable
        if (tokenUsable) {
            try {
                val pull = pullCloud(token, days, incrementalSince)
                cloudDays = pull.days.size
                cloudRecords = pull.records
                if (pull.days.isNotEmpty()) store.mergeDays(pull.days)
            } catch (e: Exception) {
                cloudError = e.message ?: e.javaClass.simpleName
            }
        } else {
            cloudError = "尚未登录小米账号"
        }

        android.util.Log.i(
            TAG,
            "sync 完成：本地 $localDays 天（${localError ?: "OK"}），" +
                "云端 $cloudDays 天（${cloudError ?: "OK"}），库内共 ${store.loadDays().size} 天",
        )
        return@withContext SyncReport(
            localDays = localDays,
            cloudDays = cloudDays,
            totalDays = store.loadDays().size,
            localError = localError,
            cloudError = cloudError,
            rootAvailable = rootAvailable,
            tokenUsable = tokenUsable,
            cloudRecords = cloudRecords,
        )
    }

    /**
     * 云端聚合。
     *
     * 对应 `.work/mifit_export.py`：逐条拉 key，按天汇总。
     * 压力确实没有（实测 `huami_all_day_stress_record` 返回 0 条）。
     * 静息心率：旧结论是「云端没有」，但旧代码从未真正请求过 `resting_heart_rate`，
     * 现在把它加进 `CLOUD_KEYS` 实测验证，见那里的注释。
     * 云端的 sleep 记录里**有** rem / awake / awake_count / items，字段名与本地一致。
     */
    private data class CloudPull(val days: List<HealthDay>, val records: Int)

    private suspend fun pullCloud(
        token: com.zzzyy.healthcoach.data.cloud.MiToken,
        days: Int,
        incrementalSince: Long = 0L,
    ): CloudPull {
        val client = MiCloudClient(token)
        val windowStart = epochSecondsOf(daysAgoIso(days))
        // 增量：上次同步成功之后再往前留 6 小时重叠（防止跨零点/补数据漏掉），
        // 这样「一天内的第二次同步」只翻一两页，而不是把 90 天全量重拉一遍。
        val since = if (incrementalSince > 0) maxOf(windowStart, incrementalSince) else windowStart
        android.util.Log.i(
            TAG,
            "云端拉取窗口：${isoOf(since)} 起（窗口起点 ${isoOf(windowStart)}，" +
                "增量水位 ${if (incrementalSince > 0) isoOf(incrementalSince) else "无"}）",
        )
        val buckets = linkedMapOf<String, HealthDay>()
        var recordCount = 0
        val hrSamples = linkedMapOf<String, MutableList<Int>>()
        val spo2Samples = linkedMapOf<String, MutableList<Int>>()

        fun bucket(date: String): HealthDay =
            buckets.getOrPut(date) { HealthDay(date = date, source = DataSource.CLOUD) }

        // 页数要跟着天数走：心率一天约 686 条，固定 40 页只够 40~50 天。
        // 这里按「天 × 4 页」给，最低 60 页，宁可多发几次请求也不要静默少数据。
        val maxPages = (days * 4).coerceAtLeast(60)
        // 云端静息心率：以前以为「云端没有」，但那是旧结论，现在按实验验证一遍。
        // 若云端确实没有，这个 key 会返回 0 条或报错 —— 下面的 try/catch 保证不影响其它 key。
        val restingByDay = linkedMapOf<String, Int>()
        val stressSamples = linkedMapOf<String, MutableList<Int>>()
        // 逐条心率采样（epochSeconds, bpm）：云端没有「夜间平均心率」这种预聚合字段，
        // 只能拿原始采样自己算（见 NightHeartRate.kt）
        val allHrSamples = mutableListOf<Pair<Long, Int>>()
        val loggedKeys = mutableSetOf<String>()
        var sleepPayloadLogged = false
        for (key in CLOUD_KEYS) {
            val records = try {
                client.pullSince(key, since, maxPages = maxPages)
            } catch (auth: com.zzzyy.healthcoach.data.cloud.MiCloudClient.AuthException) {
                // 实测过「第一个 key 报 401、后面 7 个却成功」的瞬时失效，先重试一次再放弃
                android.util.Log.w(TAG, "云端 key=$key 首次 401，2 秒后重试一次")
                kotlinx.coroutines.delay(2000)
                try {
                    client.pullSince(key, since, maxPages = maxPages)
                } catch (e: Exception) {
                    android.util.Log.w(
                        TAG,
                        "云端 key=$key 重试后仍失败，跳过：${e.javaClass.simpleName} ${e.message}",
                    )
                    continue
                }
            } catch (e: Exception) {
                // 单个 key 失败不能拖垮整次同步（旧实现是会抛到外层、整段云端全丢）
                android.util.Log.w(
                    TAG,
                    "云端 key=$key 拉取失败，跳过：${e.javaClass.simpleName} ${e.message}",
                )
                continue
            }
            recordCount += records.size
            for (record in records) {
                val date = isoOf(record.time)
                when (key) {
                    "stress" -> {
                        val v = record.value.optInt("stress", -1)
                        if (v in 1..100) {
                            stressSamples.getOrPut(date) { mutableListOf() }.add(v)
                        } else if (loggedKeys.add(key)) {
                            android.util.Log.w(
                                TAG,
                                "云端 stress 字段不认识，value 的键=${record.value.keys().asSequence().toList()}",
                            )
                        }
                    }

                    "resting_heart_rate" -> {
                        val bpm = record.value.optInt("bpm", 0)
                        if (bpm in 25..220) {
                            restingByDay[date] = bpm
                        } else if (loggedKeys.add(key)) {
                            // 只打字段名，不打具体值
                            android.util.Log.w(
                                TAG,
                                "云端 resting_heart_rate 字段不认识，value 的键=${record.value.keys().asSequence().toList()}",
                            )
                        }
                    }
                    "steps" -> {
                        val day = bucket(date)
                        buckets[date] = day.copy(
                            steps = day.steps + record.value.optInt("steps", 0),
                            distanceMeters = day.distanceMeters + record.value.optInt("distance", 0),
                        )
                    }

                    "calories" -> {
                        val day = bucket(date)
                        buckets[date] = day.copy(
                            activeCalories = day.activeCalories +
                                Math.round(record.value.optDouble("calories", 0.0)).toInt(),
                        )
                    }

                    "valid_stand" -> {
                        val day = bucket(date)
                        buckets[date] = day.copy(standHours = day.standHours + 1)
                    }

                    "heart_rate" -> {
                        val bpm = record.value.optInt("bpm", 0)
                        if (bpm in 25..220) {
                            hrSamples.getOrPut(date) { mutableListOf() }.add(bpm)
                            allHrSamples.add(record.time to bpm)
                        }
                    }

                    "spo2" -> {
                        val spo2 = record.value.optInt("spo2", 0)
                        if (spo2 in 50..100) {
                            spo2Samples.getOrPut(date) { mutableListOf() }.add(spo2)
                        }
                    }

                    "sleep" -> {
                        // 云端 sleep 的真实字段名只能靠观测，先打一次键名清单（不含值），
                        // 用来判断 HRV / 呼吸率 / 潜伏期这些字段云端到底给不给。
                        if (!sleepPayloadLogged) {
                            sleepPayloadLogged = true
                            android.util.Log.i(
                                TAG,
                                "云端 sleep 字段清单=${record.value.keys().asSequence().toList()}",
                            )
                        }
                        val sleep = SleepSummary.fromCloudJson(record.value) ?: continue
                        val day = bucket(date)
                        val existing = day.sleep
                        if (existing == null || sleep.asleepMinutes > existing.asleepMinutes) {
                            buckets[date] = day.copy(sleep = sleep)
                        }
                    }
                }
            }
        }

        hrSamples.forEach { (date, samples) ->
            val day = bucket(date)
            buckets[date] = day.copy(
                heartRate = HeartRateSummary(
                    average = Math.round(samples.average()).toInt(),
                    min = samples.min(),
                    max = samples.max(),
                    sampleCount = samples.size,
                )
            )
        }
        spo2Samples.forEach { (date, samples) ->
            val day = bucket(date)
            buckets[date] = day.copy(
                spo2 = Spo2Summary(
                    average = Math.round(samples.average()).toInt(),
                    min = samples.min(),
                    sampleCount = samples.size,
                )
            )
        }
        restingByDay.forEach { (date, bpm) ->
            val day = bucket(date)
            buckets[date] = day.copy(restingHeartRate = bpm)
        }
        stressSamples.forEach { (date, samples) ->
            val day = bucket(date)
            buckets[date] = day.copy(
                stress = StressSummary(
                    average = Math.round(samples.average()).toInt(),
                    max = samples.max(),
                    min = samples.min(),
                    sampleCount = samples.size,
                )
            )
        }
        // 用逐条心率补出夜间心率三件套（无 root 用户只靠云端也能参与评分的这两项）
        var nightHrFilled = 0
        buckets.entries.toList().forEach { entry ->
            val sleep = entry.value.sleep ?: return@forEach
            val stats = nightHeartRateStats(sleep.startMillis, sleep.endMillis, allHrSamples)
            if (stats.average == null) return@forEach
            nightHrFilled++
            buckets[entry.key] = entry.value.copy(
                sleep = sleep.copy(
                    averageHeartRate = sleep.averageHeartRate ?: stats.average,
                    firstHalfHeartRate = sleep.firstHalfHeartRate ?: stats.firstHalf,
                    secondHalfHeartRate = sleep.secondHalfHeartRate ?: stats.secondHalf,
                )
            )
        }
        android.util.Log.i(
            TAG,
            "云端夜间心率：用 ${allHrSamples.size} 条采样补出 $nightHrFilled 晚的平均/前后半夜心率",
        )
        // 证据日志：云端 sleep 自带的预聚合值覆盖了多少晚（无 root 用户的评分完整度就看这个）
        val cloudSleeps = buckets.values.mapNotNull { it.sleep }
        android.util.Log.i(
            TAG,
            "云端睡眠字段覆盖：共 ${cloudSleeps.size} 晚，" +
                "心率=${cloudSleeps.count { it.averageHeartRate != null }}，" +
                "HRV=${cloudSleeps.count { it.averageHrv != null }}，" +
                "平均血氧=${cloudSleeps.count { it.averageSpo2 != null }}，" +
                "呼吸=${cloudSleeps.count { it.averageBreath != null }}，" +
                "最低血氧=${cloudSleeps.count { it.minSpo2 != null }}",
        )
        android.util.Log.i(
            TAG,
            "云端压力：拿到 ${stressSamples.size} 天" +
                if (stressSamples.isEmpty()) "（该 key 云端没有数据）" else "",
        )
        android.util.Log.i(
            TAG,
            "云端静息心率：拿到 ${restingByDay.size} 天" +
                if (restingByDay.isEmpty()) "（该 key 云端没有数据）" else "",
        )
        // 日期本身不是敏感值，打出来便于核对「云端到底覆盖了哪几天」
        if (restingByDay.isNotEmpty()) {
            android.util.Log.d(TAG, "云端静息心率日期：${restingByDay.keys.sorted()}")
        }

        android.util.Log.i(
            TAG,
            "云端本轮共发出 ${client.requestCount} 个请求（窗口 ${isoOf(since)} 起），" +
                "拿到 $recordCount 条原始记录",
        )
        return CloudPull(buckets.values.filter { it.hasData }, recordCount)
    }

    companion object {
        private const val TAG = "HealthCoach.Repository"
        /** 云端可用的 key。`resting_heart_rate` 是**实验性加入**：以前以为云端没有，现在验证。 */
        val CLOUD_KEYS = listOf(
            "steps",
            "calories",
            "valid_stand",
            "heart_rate",
            "spo2",
            "sleep",
            "resting_heart_rate",
            // 探针：旧注释说「云端没有压力」，但同样从未真正请求过，先请求再说
            "stress",
        )

        fun daysAgoIso(days: Int): String {
            val calendar = Calendar.getInstance(TimeZone.getDefault())
            calendar.add(Calendar.DAY_OF_YEAR, -days)
            return "%04d-%02d-%02d".format(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )
        }

        fun isoOf(epochSeconds: Long): String {
            val calendar = Calendar.getInstance(TimeZone.getDefault())
            calendar.timeInMillis = epochSeconds * 1000
            return "%04d-%02d-%02d".format(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )
        }

        private fun epochSecondsOf(iso: String): Long {
            val parts = iso.split("-")
            val calendar = Calendar.getInstance(TimeZone.getDefault())
            calendar.clear()
            calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            return calendar.timeInMillis / 1000
        }
    }
}
