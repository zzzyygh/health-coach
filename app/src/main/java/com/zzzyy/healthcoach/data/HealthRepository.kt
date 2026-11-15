package com.zzzyy.healthcoach.data

import android.content.Context
import com.zzzyy.healthcoach.data.cloud.MiCloudClient
import com.zzzyy.healthcoach.data.cloud.MiTokenStore
import com.zzzyy.healthcoach.data.local.MiHealthLocalDb
import com.zzzyy.healthcoach.data.local.RootShell
import com.zzzyy.healthcoach.data.model.DataSource
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.HeartRateSummary
import com.zzzyy.healthcoach.data.model.SleepSummary
import com.zzzyy.healthcoach.data.model.Spo2Summary
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
    suspend fun sync(days: Int = 30): SyncReport = withContext(Dispatchers.IO) {
        val sinceDate = daysAgoIso(days)
        android.util.Log.i(TAG, "sync 开始：范围 $days 天（since=$sinceDate）")
        var localDays = 0
        var cloudDays = 0
        var localError: String? = null
        var cloudError: String? = null

        // ── 本地 ──────────────────────────────────────────────
        val rootAvailable = RootShell.isAvailable()
        if (rootAvailable) {
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
                val cloud = pullCloud(token, days)
                cloudDays = cloud.size
                if (cloud.isNotEmpty()) store.mergeDays(cloud)
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
        )
    }

    /**
     * 云端聚合。
     *
     * 对应 `.work/mifit_export.py`：逐条拉 key，按天汇总。
     * 注意云端**没有**静息心率、**也没有**压力
     * （实测 `huami_all_day_stress_record` 返回 0 条），这两个只能来自本地库。
     * 云端的 sleep 记录里**有** rem / awake / awake_count / items，字段名与本地一致。
     */
    private suspend fun pullCloud(
        token: com.zzzyy.healthcoach.data.cloud.MiToken,
        days: Int,
    ): List<HealthDay> {
        val client = MiCloudClient(token)
        val since = epochSecondsOf(daysAgoIso(days))
        val buckets = linkedMapOf<String, HealthDay>()
        val hrSamples = linkedMapOf<String, MutableList<Int>>()
        val spo2Samples = linkedMapOf<String, MutableList<Int>>()

        fun bucket(date: String): HealthDay =
            buckets.getOrPut(date) { HealthDay(date = date, source = DataSource.CLOUD) }

        // 页数要跟着天数走：心率一天约 686 条，固定 40 页只够 40~50 天。
        // 这里按「天 × 4 页」给，最低 60 页，宁可多发几次请求也不要静默少数据。
        val maxPages = (days * 4).coerceAtLeast(60)
        for (key in CLOUD_KEYS) {
            val records = client.pullSince(key, since, maxPages = maxPages)
            for (record in records) {
                val date = isoOf(record.time)
                when (key) {
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
                        }
                    }

                    "spo2" -> {
                        val spo2 = record.value.optInt("spo2", 0)
                        if (spo2 in 50..100) {
                            spo2Samples.getOrPut(date) { mutableListOf() }.add(spo2)
                        }
                    }

                    "sleep" -> {
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

        return buckets.values.filter { it.hasData }
    }

    companion object {
        private const val TAG = "HealthCoach.Repository"
        /** 云端可用的 key。注意：没有静息心率。 */
        val CLOUD_KEYS = listOf("steps", "calories", "valid_stand", "heart_rate", "spo2", "sleep")

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
