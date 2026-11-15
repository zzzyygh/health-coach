package com.zzzyy.healthcoach.data.local

import android.content.Context
import android.util.Log
import android.database.sqlite.SQLiteDatabase
import com.zzzyy.healthcoach.data.model.DataSource
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.HeartRateSummary
import com.zzzyy.healthcoach.data.model.SleepSummary
import com.zzzyy.healthcoach.data.model.Spo2Summary
import com.zzzyy.healthcoach.data.model.StressSummary
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 读取手机本机「小米运动健康」（com.mi.health）的 SQLite 数据库。
 *
 * 路径：`/data/data/com.mi.health/databases/<账号>/<区域>/fitness_data`
 *
 * app 本身没有别的 app 私有目录的读权限，所以流程是：
 *   1. 用 root shell 探测库的真实路径（不同 ROM / 版本层级可能不同）；
 *   2. 用 root shell 把库（连同 -wal / -shm）复制到 app 的缓存目录；
 *   3. 用 Android 自带的 SQLiteDatabase **只读**打开副本，在 Kotlin 里解析 value 里的 JSON。
 *
 * ⚠️ 解析放在 Kotlin 而不是 SQL 里做 `json_extract`：Android 自带的 SQLite
 * 不保证带 JSON1 扩展，写死在 SQL 里会在某些 ROM 上直接报错。
 */
class MiHealthLocalDb(private val context: Context) {

    companion object {
        private const val TAG = "HealthCoach.LocalDb"
        private const val DB_ROOT = "/data/data/com.mi.health/databases"
        private const val PRIMARY_DB = "fitness_data"
    }

    /** 探测结果：库文件是否就绪。 */
    data class SyncResult(
        val databaseFile: File?,
        val sourcePath: String?,
        val copiedBytes: Long,
        val error: String? = null,
    )

    /**
     * 找出 com.mi.health 数据库的位置。
     *
     * 已知布局是 `<账号>/<区域>/fitness_data`，但层级在不同版本上会变，
     * 所以直接用 find 搜文件名，比硬编码路径稳。
     */
    fun locateSource(): String? {
        // 不加 2>/dev/null：把 find 的错误信息留下来，否则失败时只能看到空输出
        val output = try {
            RootShell.run("find $DB_ROOT -name $PRIMARY_DB -type f")
        } catch (e: Exception) {
            Log.e(TAG, "find 异常：${e.message}", e)
            return null
        }
        Log.i(TAG, "ls 结果：${RootShell.run("ls $DB_ROOT").take(300).trim()}")
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(DB_ROOT) && it.endsWith("/$PRIMARY_DB") }
    }

    /**
     * 把库复制到 app 缓存目录。
     *
     * 只有当源文件比上次复制的新时才重新复制（按 size + mtime 判断），
     * 否则每次同步都要拷 500MB。
     */
    @Synchronized
    fun syncToCache(): SyncResult {
        if (!RootShell.isAvailable()) {
            return SyncResult(null, null, 0L, "没有 root 权限（Magisk 未授权本应用）")
        }
        val source = locateSource()
            ?: return SyncResult(null, null, 0L, "找不到小米运动健康的数据库（$DB_ROOT 下没有 $PRIMARY_DB）")

        val target = File(context.cacheDir, "mi_health/$PRIMARY_DB")
        val stamp = File(context.cacheDir, "mi_health/$PRIMARY_DB.stamp")

        val sourceStat = RootShell.run("stat -c '%s:%Y' '$source' 2>/dev/null").trim()
        if (target.exists() && stamp.exists() && stamp.readText().trim() == sourceStat) {
            return SyncResult(target, source, target.length())
        }

        target.parentFile?.mkdirs()
        Log.i(TAG, "同步数据库：$source → ${target.absolutePath}（${target.length() / 1024 / 1024} MB 旧副本）")
        return try {
            val started = System.currentTimeMillis()
            RootShell.copyToApp(source, target.absolutePath)
            val elapsed = (System.currentTimeMillis() - started) / 1000
            stamp.writeText(sourceStat)
            Log.i(TAG, "数据库复制完成：${target.length() / 1024 / 1024} MB，用时 ${elapsed}s")
            SyncResult(target, source, target.length())
        } catch (e: Exception) {
            Log.e(TAG, "数据库复制失败：${e.message}", e)
            SyncResult(null, source, 0L, e.message)
        }
    }

    /**
     * 从本地库读出 [sinceDate] 之后（或全部）的日聚合数据。
     *
     * @param sinceDate yyyy-MM-dd，传空则读全部。
     */
    fun readDays(sinceDate: String? = null): List<HealthDay> {
        val sync = syncToCache()
        val file = sync.databaseFile
        if (file == null) {
            Log.w(TAG, "readDays 失败：没有可用数据库（${sync.error}）")
            return emptyList()
        }
        return try {
            val started = System.currentTimeMillis()
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val days = readDaysFrom(db, sinceDate)
                Log.i(TAG, "readDays 完成：${days.size} 天，用时 ${(System.currentTimeMillis() - started) / 1000}s")
                days.forEach { day ->
                    Log.i(
                        TAG,
                        "  ${day.date} 步${day.steps} 静息${day.restingHeartRate ?: "-"} " +
                            "心率${day.heartRate?.average ?: "-"} 血氧${day.spo2?.average ?: "-"} " +
                            "睡${day.sleep?.hours ?: "-"} " +
                            "深${day.sleep?.deepMinutes ?: "-"}/浅${day.sleep?.lightMinutes ?: "-"}" +
                            "/REM${day.sleep?.remMinutes ?: "-"}/醒${day.sleep?.awakeMinutes ?: "-"}" +
                            "(${day.sleep?.awakeCount ?: "-"}次,效率${day.sleep?.efficiency ?: "-"}) " +
                            "分期${day.sleep?.stages?.size ?: 0}段 " +
                            "压力${day.stress?.average ?: "-"}"
                    )
                }
                days
            }
        } catch (e: Exception) {
            Log.e(TAG, "readDays 异常：${e.message}", e)
            emptyList()
        }
    }

    private fun readDaysFrom(db: SQLiteDatabase, sinceDate: String?): List<HealthDay> {
        val since = sinceDate?.let {
            runCatching { dateFormat().parse(it)!!.time / 1000 }.getOrNull()
        } ?: 0L

        val days = linkedMapOf<String, HealthDay>()
        fun day(date: String): HealthDay = days.getOrPut(date) { HealthDay(date = date, source = DataSource.LOCAL) }

        // ── 步数 / 距离 / 卡路里（增量型记录，直接求和） ─────────────
        query(db, """
            select time, value from step_record
            where isDeleted=0 and key in ('steps','com.xiaomi.steps.delta') and time >= ?
        """.trimIndent(), since) { time, value ->
            val day = day(dayKey(time))
            val updated = day.copy(
                steps = day.steps + value.optInt("steps", 0),
                distanceMeters = day.distanceMeters + value.optInt("distance", 0),
                activeCalories = day.activeCalories + Math.round(value.optDouble("calories", 0.0)).toInt(),
            )
            days[day.date] = updated
        }

        // ── 心率 ──────────────────────────────────────────────────
        val hrByDay = linkedMapOf<String, MutableList<Int>>()
        // 同时保留「时间 → 心率」明细，稍后要按睡眠段切成前半夜/后半夜
        val hrTimeline = mutableListOf<Pair<Long, Int>>()
        query(db, """
            select time, value from hr_record
            where isDeleted=0 and key='heart_rate' and time >= ?
        """.trimIndent(), since) { time, value ->
            val bpm = value.optInt("bpm", 0)
            if (bpm in 25..220) {
                hrByDay.getOrPut(dayKey(time)) { mutableListOf() }.add(bpm)
                hrTimeline.add(time to bpm)
            }
        }
        hrByDay.forEach { (date, samples) ->
            val base = days[date] ?: HealthDay(date = date, source = DataSource.LOCAL)
            days[date] = base.copy(
                heartRate = HeartRateSummary(
                    average = Math.round(samples.average()).toInt(),
                    min = samples.min(),
                    max = samples.max(),
                    sampleCount = samples.size,
                )
            )
        }

        // ── 静息心率（每天一条独立记录；云端拿不到，只有本地有） ──────
        val restingByDay = mutableMapOf<String, Int>()
        query(db, """
            select time, value from hr_record
            where isDeleted=0 and key='resting_heart_rate' and time >= ?
        """.trimIndent(), since) { time, value ->
            val bpm = value.optInt("bpm", 0)
            if (bpm in 25..220) restingByDay[dayKey(time)] = bpm
        }
        restingByDay.forEach { (date, bpm) ->
            val base = days[date] ?: HealthDay(date = date, source = DataSource.LOCAL)
            days[date] = base.copy(restingHeartRate = bpm)
        }

        // ── 血氧 ──────────────────────────────────────────────────
        val spo2ByDay = linkedMapOf<String, MutableList<Int>>()
        query(db, """
            select time, value from spo2_record
            where isDeleted=0 and key='spo2' and time >= ?
        """.trimIndent(), since) { time, value ->
            val spo2 = value.optInt("spo2", 0)
            if (spo2 in 50..100) spo2ByDay.getOrPut(dayKey(time)) { mutableListOf() }.add(spo2)
        }
        spo2ByDay.forEach { (date, samples) ->
            val base = days[date] ?: HealthDay(date = date, source = DataSource.LOCAL)
            days[date] = base.copy(
                spo2 = Spo2Summary(
                    average = Math.round(samples.average()).toInt(),
                    min = samples.min(),
                    sampleCount = samples.size,
                )
            )
        }

        // ── 站立（每个清醒时段一条 valid_stand） ───────────────────
        query(db, """
            select time, value from stand_record
            where isDeleted=0 and key='valid_stand' and time >= ?
        """.trimIndent(), since) { time, _ ->
            val date = dayKey(time)
            val base = days[date] ?: HealthDay(date = date, source = DataSource.LOCAL)
            days[date] = base.copy(standHours = base.standHours + 1)
        }

        // ── 压力（每几分钟一条，按天聚合；云端没有这个 key） ─────────
        val stressByDay = linkedMapOf<String, MutableList<Int>>()
        query(db, """
            select time, value from stress_record
            where isDeleted=0 and time >= ?
        """.trimIndent(), since) { time, value ->
            val stress = value.optInt("stress", -1)
            if (stress in 1..100) stressByDay.getOrPut(dayKey(time)) { mutableListOf() }.add(stress)
        }
        stressByDay.forEach { (date, samples) ->
            val base = days[date] ?: HealthDay(date = date, source = DataSource.LOCAL)
            val summary = StressSummary(
                average = Math.round(samples.average()).toInt(),
                max = samples.max(),
                min = samples.min(),
                sampleCount = samples.size,
            )
            days[date] = base.copy(stress = summary)
            Log.i(TAG, "  压力 $date：均${summary.average} 峰${summary.max} 低${summary.min}（${summary.sampleCount} 条）")
        }

        // ── 睡眠（一晚可能有多条，取「真正睡着」时间最长的那条） ────────
        query(db, """
            select time, value from sleep_segment
            where isDeleted=0 and key='sleep' and time >= ?
        """.trimIndent(), since) { _, value ->
            val parsed = SleepSummary.fromLocalJson(value) ?: return@query
            // 按睡眠时间段切出前半夜/后半夜平均心率（副交感恢复的直接信号）
            val sleep = withHalfNightHeartRate(parsed, hrTimeline)
            // 归属日期用「入睡那天」，跨零点的睡眠算前一天
            val date = dayKey(sleep.startMillis / 1000)
            val base = days[date] ?: HealthDay(date = date, source = DataSource.LOCAL)
            val existing = base.sleep
            if (existing == null || sleep.asleepMinutes > existing.asleepMinutes) {
                days[date] = base.copy(sleep = sleep)
                Log.i(TAG, "  睡眠 $date：${sleep.hours}h 深${sleep.deepMinutes} " +
                    "浅${sleep.lightMinutes} REM${sleep.remMinutes} 醒${sleep.awakeMinutes} " +
                    "(${sleep.awakeCount}次) 效率${sleep.efficiency ?: "-"} 潜伏${sleep.latencyMinutes ?: "-"}分 " +
                    "前/后半夜心率 ${sleep.firstHalfHeartRate ?: "-"}/${sleep.secondHalfHeartRate ?: "-"} " +
                    "最低血氧${sleep.minSpo2 ?: "-"} 分期${sleep.stages.size}段")
            }
        }

        return days.values.filter { it.hasData }.sortedBy { it.date }
    }

    /**
     * 把一晚切成前半夜 / 后半夜，分别算平均心率。
     *
     * 正常生理模式是「前半夜心率 ≤ 后半夜」：前半夜深睡占优、副交感主导，
     * 后半夜 REM 变多、心率略升。反过来（前半夜明显更高）值得留意。
     *
     * ⚠️ 注意这不等于「睡眠质量」：文献里夜间心率不下降主要预测**心血管风险**，
     * 与主观睡眠质量无相关（Huang 2018 / Leiva Sisnieguez 2025）。
     */
    private fun withHalfNightHeartRate(
        sleep: SleepSummary,
        timeline: List<Pair<Long, Int>>,
    ): SleepSummary {
        if (timeline.isEmpty()) return sleep
        val startSec = sleep.startMillis / 1000
        val endSec = sleep.endMillis / 1000
        if (endSec <= startSec) return sleep
        val mid = (startSec + endSec) / 2

        var firstSum = 0L
        var firstCount = 0
        var secondSum = 0L
        var secondCount = 0
        for ((time, bpm) in timeline) {
            if (time < startSec || time > endSec) continue
            if (time < mid) {
                firstSum += bpm; firstCount++
            } else {
                secondSum += bpm; secondCount++
            }
        }
        val first = if (firstCount > 0) (firstSum / firstCount).toInt() else null
        val second = if (secondCount > 0) (secondSum / secondCount).toInt() else null
        return sleep.copy(firstHalfHeartRate = first, secondHalfHeartRate = second)
    }

    private inline fun query(
        db: SQLiteDatabase,
        sql: String,
        since: Long,
        onRow: (time: Long, value: JSONObject) -> Unit,
    ) {
        db.rawQuery(sql, arrayOf(since.toString())).use { cursor ->
            val timeIndex = cursor.getColumnIndexOrThrow("time")
            val valueIndex = cursor.getColumnIndexOrThrow("value")
            while (cursor.moveToNext()) {
                val time = cursor.getLong(timeIndex)
                val raw = cursor.getString(valueIndex) ?: continue
                val json = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    continue
                }
                onRow(time, json)
            }
        }
    }

    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun dayKey(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(java.util.Date(epochSeconds * 1000))
}
