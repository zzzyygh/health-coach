package com.zzzyy.healthcoach.data.store

import android.content.Context
import com.zzzyy.healthcoach.data.model.DataSource
import com.zzzyy.healthcoach.data.model.FeedbackEntry
import com.zzzyy.healthcoach.data.model.AdviceRecord
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.InterventionEntry
import com.zzzyy.healthcoach.data.model.HeartRateSummary
import com.zzzyy.healthcoach.data.model.SleepSummary
import com.zzzyy.healthcoach.data.model.SleepStage
import com.zzzyy.healthcoach.data.model.SleepStageKind
import com.zzzyy.healthcoach.data.model.Spo2Summary
import com.zzzyy.healthcoach.data.model.StressSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地存储：日聚合数据 + 用户反馈，都存成 app 私有目录下的 JSON 文件。
 *
 * 没有用 Room：数据量小（每天一条 + 每天几条反馈），而且沙箱里
 * Room 的 KSP 验证器要求可写的系统临时目录，跑不起来。
 */
class HealthStore(context: Context) {

    private companion object {
        const val TAG = "HealthCoach.Store"

        /**
         * 落盘数据的版本号。
         *
         * **每给 HealthDay / SleepSummary 加新字段就要 +1**，否则老数据里
         * 那些字段永远是 null，界面上表现成「无数据」，看起来像取数坏了。
         * 版本不符时 [needsUpgrade] 为 true，上层会强制重同步一次。
         *
         *   1 → 初版（步数/心率/血氧/睡眠基础字段）
         *   2 → 加 REM / 清醒 / 效率 / 分期时间轴 / 压力
         *   3 → 加入睡潜伏期 / 夜间最低血氧 / 前后半夜心率
         */
        const val CURRENT_DATA_VERSION = 3

        const val KEY_VERSION = "version"
        const val KEY_DAYS = "days"
        const val KEY_STORED_VERSION = "data_version"
    }

    /**
     * 数据版本必须**持久化**，不能只放在内存里。
     *
     * 之前的实现用了一个内存字段 `loadedVersion`（初值 0），而 App 启动时
     * `refresh()` 是异步的、版本检查发生在它之前 —— 于是每次启动都判成
     * 「版本落后 → 强制同步」，打开几次就同步几次。必须落到 SharedPreferences。
     */
    private val metaPrefs = context.getSharedPreferences("store_meta", Context.MODE_PRIVATE)

    /** 落盘数据是否落后于当前模型版本（落后就需要重新同步一次）。 */
    fun needsUpgrade(): Boolean {
        val stored = metaPrefs.getInt(KEY_STORED_VERSION, 0)
        return stored < CURRENT_DATA_VERSION
    }

    private fun markVersion(version: Int) {
        metaPrefs.edit().putInt(KEY_STORED_VERSION, version).apply()
        android.util.Log.i(TAG, "数据版本标记为 $version（当前模型 $CURRENT_DATA_VERSION）")
    }

    private val dir = File(context.filesDir, "health")
    private val daysFile = File(dir, "days.json")
    private val feedbackFile = File(dir, "feedback.json")
    private val interventionFile = File(dir, "interventions.json")
    private val adviceFile = File(dir, "advices.json")

    // ── 日聚合数据 ────────────────────────────────────────────────

    fun loadDays(): List<HealthDay> {
        val file = daysFile
        if (!file.exists()) {
            android.util.Log.w(TAG, "loadDays：数据文件不存在（${file.absolutePath}）")
            return emptyList()
        }
        return try {
            val text = file.readText()
            val (version, array) = parsePayload(text)
            // 文件本身已是新版 → 记录下来，下次启动就不用再判「落后」
            if (version >= CURRENT_DATA_VERSION && needsUpgrade()) markVersion(version)
            val days = buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(dayFromJson(it)) }
                }
            }
            android.util.Log.i(
                TAG,
                "loadDays 完成：版本 $version（当前 $CURRENT_DATA_VERSION），${days.size} 天，" +
                    "有睡眠记录 ${days.count { it.sleep != null }} 天" +
                    if (version < CURRENT_DATA_VERSION) " → 数据落后，需要重新同步" else "",
            )
            days
        } catch (e: Exception) {
            // 绝不能静默吞掉：解析失败会让整个界面变成「还没有数据」，
            // 看起来像同步没做，实际是数据读不出来。
            android.util.Log.e(TAG, "loadDays 解析失败：${e.message}", e)
            emptyList()
        }
    }

    fun saveDays(days: List<HealthDay>) {
        val array = JSONArray()
        days.forEach { array.put(dayToJson(it)) }
        val payload = JSONObject().apply {
            put(KEY_VERSION, CURRENT_DATA_VERSION)
            put(KEY_DAYS, array)
        }
        writeAtomically(daysFile, payload.toString())
        markVersion(CURRENT_DATA_VERSION)
    }

    /**
     * 兼容两种格式：
     *   - 旧版是裸数组 `[...]`
     *   - 新版是 `{"version": 3, "days": [...]}`
     */
    private fun parsePayload(text: String): Pair<Int, JSONArray> {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("[")) {
            1 to JSONArray(text)
        } else {
            val obj = JSONObject(text)
            (obj.optInt(KEY_VERSION, 1)) to (obj.optJSONArray(KEY_DAYS) ?: JSONArray())
        }
    }

    /**
     * 合并新数据：同一天以 [preferNew] 的一方为准做字段级合并。
     *
     * 云端和本地各有短板（云端没有静息心率，本地没有某些云端聚合），
     * 所以不能整条覆盖，得逐字段取并集。
     */
    fun mergeDays(incoming: List<HealthDay>): List<HealthDay> {
        val merged = linkedMapOf<String, HealthDay>()
        loadDays().forEach { merged[it.date] = it }
        incoming.forEach { new ->
            val old = merged[new.date]
            merged[new.date] = if (old == null) {
                new
            } else {
                old.copy(
                    steps = maxOf(old.steps, new.steps),
                    distanceMeters = maxOf(old.distanceMeters, new.distanceMeters),
                    activeCalories = maxOf(old.activeCalories, new.activeCalories),
                    standHours = maxOf(old.standHours, new.standHours),
                    heartRate = new.heartRate ?: old.heartRate,
                    restingHeartRate = new.restingHeartRate ?: old.restingHeartRate,
                    spo2 = new.spo2 ?: old.spo2,
                    sleep = betterSleep(old.sleep, new.sleep),
                    stress = new.stress ?: old.stress,
                    source = if (old.source == DataSource.LOCAL || new.source == DataSource.LOCAL) {
                        DataSource.LOCAL
                    } else {
                        DataSource.CLOUD
                    },
                )
            }
        }
        val result = merged.values.sortedBy { it.date }
        saveDays(result)
        return result
    }

    /**
     * 睡眠记录可能来自两个来源，取信息量更大的那条。
     *
     * 比较的是「真正睡着」的时间（深睡+浅睡+REM），不是卧床时长 ——
     * 卧床 10 小时但只睡 5 小时的一晚，不该盖掉卧床 8 小时睡满 7.5 小时的那条。
     * 睡着时间相同时，**带分期数据**的那条优先（能画时间轴）。
     */
    private fun betterSleep(old: SleepSummary?, new: SleepSummary?): SleepSummary? {
        if (old == null) return new
        if (new == null) return old
        // 先按「真正睡着的时间」选一条主要记录，**再逐字段补全**。
        // 不能像以前那样整条二选一：两晚睡着时间相同时会返回 old，
        // 于是新读到的 latency / minSpo2 / 前后半夜心率 全被丢掉，
        // 界面上就表现成「这些指标无数据」。
        val primary = when {
            new.asleepMinutes > old.asleepMinutes -> new
            new.asleepMinutes < old.asleepMinutes -> old
            new.hasStages && !old.hasStages -> new
            else -> old
        }
        val secondary = if (primary === new) old else new
        return primary.copy(
            latencyMinutes = primary.latencyMinutes ?: secondary.latencyMinutes,
            minSpo2 = primary.minSpo2 ?: secondary.minSpo2,
            firstHalfHeartRate = primary.firstHalfHeartRate ?: secondary.firstHalfHeartRate,
            secondHalfHeartRate = primary.secondHalfHeartRate ?: secondary.secondHalfHeartRate,
            averageHeartRate = primary.averageHeartRate ?: secondary.averageHeartRate,
            averageHrv = primary.averageHrv ?: secondary.averageHrv,
            averageSpo2 = primary.averageSpo2 ?: secondary.averageSpo2,
            averageBreath = primary.averageBreath ?: secondary.averageBreath,
            efficiency = primary.efficiency ?: secondary.efficiency,
            awakeMinutes = maxOf(primary.awakeMinutes, secondary.awakeMinutes),
            awakeCount = maxOf(primary.awakeCount, secondary.awakeCount),
            stages = if (primary.stages.isNotEmpty()) primary.stages else secondary.stages,
        )
    }

    // ── 反馈 ─────────────────────────────────────────────────────

    fun loadFeedback(): List<FeedbackEntry> {
        if (!feedbackFile.exists()) return emptyList()
        return try {
            val array = JSONArray(feedbackFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(FeedbackEntry.fromJson(it)) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存反馈。
     *
     * 语义分两种，因为这两件事本来就不一样：
     *   - **早上打卡**（[FeedbackEntry.SOURCE_MORNING]）：每天只有一条，
     *     重复提交时**替换**当天的打卡，而不是堆出两条；
     *   - **随手记录**（[FeedbackEntry.SOURCE_MANUAL]）：一天可以有多条，
     *     只追加、不覆盖 —— 下午犯困和晚上放松是两件事，不该互相顶掉。
     */
    fun saveFeedback(entry: FeedbackEntry) {
        val existing = loadFeedback()
        val next = if (entry.isMorningCheckIn) {
            // 替换当天已有的打卡记录（保留其他来源的记录）
            existing.filterNot { it.date == entry.date && it.isMorningCheckIn } + entry
        } else {
            // 手动记录：如果时间戳完全相同的补交（同一次提交重放），也做去重
            existing.filterNot { it.date == entry.date && it.timestamp == entry.timestamp } + entry
        }
        val array = JSONArray()
        next.sortedBy { it.timestamp }.forEach { array.put(it.toJson()) }
        writeAtomically(feedbackFile, array.toString())
        android.util.Log.i(
            TAG,
            "反馈已保存：${entry.date} 来源=${entry.source} " +
                "睡眠质量${entry.sleepQuality ?: "-"} 精力${entry.energy ?: "-"} " +
                "（当天共 ${next.count { it.date == entry.date }} 条）",
        )
    }

    // ── 干预记录（补充剂 / 咖啡 / 运动…）────────────────────────────

    fun loadInterventions(): List<InterventionEntry> {
        if (!interventionFile.exists()) return emptyList()
        return try {
            InterventionEntry.listFromJson(JSONArray(interventionFile.readText()))
                .sortedBy { it.timestamp }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "loadInterventions 解析失败：${e.message}", e)
            emptyList()
        }
    }

    /**
     * 追加一条干预记录。
     *
     * **同日同名同剂量视为重复**直接跳过：用户很容易点两次，
     * 重复记两条会让「吃了 8000IU」这种错误结论进到模型里。
     */
    fun addIntervention(entry: InterventionEntry): Boolean {
        val all = loadInterventions().toMutableList()
        val duplicate = all.any {
            it.date == entry.date && it.name == entry.name &&
                it.dose == entry.dose && it.unit == entry.unit
        }
        if (duplicate) {
            android.util.Log.i(TAG, "干预记录重复，跳过：${entry.display}（${entry.date}）")
            return false
        }
        all.add(entry)
        writeAtomically(interventionFile, InterventionEntry.listToJson(all).toString())
        android.util.Log.i(TAG, "干预记录已保存：${entry.date} ${entry.display}")
        return true
    }

    fun deleteIntervention(entry: InterventionEntry) {
        val all = loadInterventions().filterNot {
            it.date == entry.date && it.name == entry.name && it.timestamp == entry.timestamp
        }
        writeAtomically(interventionFile, InterventionEntry.listToJson(all).toString())
        android.util.Log.i(TAG, "干预记录已删除：${entry.date} ${entry.display}")
    }

    // ── AI 建议历史 ──────────────────────────────────────────────

    fun loadAdvices(): List<AdviceRecord> {
        if (!adviceFile.exists()) return emptyList()
        return try {
            AdviceRecord.listFromJson(JSONArray(adviceFile.readText()))
                .sortedBy { it.timestamp }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "loadAdvices 解析失败：${e.message}", e)
            emptyList()
        }
    }

    fun saveAdvice(record: AdviceRecord) {
        // 只保留最近 60 条，避免文件无限增长
        val all = (loadAdvices() + record).sortedBy { it.timestamp }.takeLast(60)
        writeAtomically(adviceFile, AdviceRecord.listToJson(all).toString())
        android.util.Log.i(
            TAG,
            "建议已保存：${record.date} 模型=${record.model} " +
                "解析${if (record.parseFailed) "失败" else "成功"}（共 ${all.size} 条历史）",
        )
    }

    fun clearAdvices() {
        writeAtomically(adviceFile, "[]")
        android.util.Log.i(TAG, "建议历史已清空")
    }

    // ── JSON 转换 ────────────────────────────────────────────────

    private fun dayToJson(day: HealthDay): JSONObject = JSONObject().apply {
        put("date", day.date)
        put("steps", day.steps)
        put("distanceMeters", day.distanceMeters)
        put("activeCalories", day.activeCalories)
        put("standHours", day.standHours)
        put("source", day.source.name)
        day.heartRate?.let {
            put("heartRate", JSONObject().apply {
                put("average", it.average)
                put("min", it.min)
                put("max", it.max)
                put("sampleCount", it.sampleCount)
            })
        }
        day.restingHeartRate?.let { put("restingHeartRate", it) }
        day.spo2?.let {
            put("spo2", JSONObject().apply {
                put("average", it.average)
                put("min", it.min)
                put("sampleCount", it.sampleCount)
            })
        }
        day.sleep?.let {
            put("sleep", JSONObject().apply {
                put("startMillis", it.startMillis)
                put("endMillis", it.endMillis)
                put("hours", it.hours)
                put("deepMinutes", it.deepMinutes)
                put("lightMinutes", it.lightMinutes)
                put("remMinutes", it.remMinutes)
                put("awakeMinutes", it.awakeMinutes)
                put("awakeCount", it.awakeCount)
                it.efficiency?.let { v -> put("efficiency", v) }
                it.latencyMinutes?.let { v -> put("latencyMinutes", v) }
                it.minSpo2?.let { v -> put("minSpo2", v) }
                it.firstHalfHeartRate?.let { v -> put("firstHalfHeartRate", v) }
                it.secondHalfHeartRate?.let { v -> put("secondHalfHeartRate", v) }
                if (it.stages.isNotEmpty()) {
                    put("stages", JSONArray().apply {
                        it.stages.forEach { stage ->
                            put(JSONObject().apply {
                                put("startMillis", stage.startMillis)
                                put("endMillis", stage.endMillis)
                                put("kind", stage.kind.name)
                            })
                        }
                    })
                }
                it.averageHeartRate?.let { v -> put("averageHeartRate", v) }
                it.averageHrv?.let { v -> put("averageHrv", v) }
                it.averageSpo2?.let { v -> put("averageSpo2", v) }
                it.averageBreath?.let { v -> put("averageBreath", v) }
            })
        }
        day.stress?.let {
            put("stress", JSONObject().apply {
                put("average", it.average)
                put("max", it.max)
                put("min", it.min)
                put("sampleCount", it.sampleCount)
            })
        }
    }

    private fun dayFromJson(json: JSONObject): HealthDay {
        val heartRate = json.optJSONObject("heartRate")?.let {
            HeartRateSummary(
                average = it.optInt("average"),
                min = it.optInt("min"),
                max = it.optInt("max"),
                sampleCount = it.optInt("sampleCount"),
            )
        }
        val spo2 = json.optJSONObject("spo2")?.let {
            Spo2Summary(
                average = it.optInt("average"),
                min = it.optInt("min"),
                sampleCount = it.optInt("sampleCount"),
            )
        }
        val sleep = json.optJSONObject("sleep")?.let {
            SleepSummary(
                startMillis = it.optLong("startMillis"),
                endMillis = it.optLong("endMillis"),
                hours = it.optDouble("hours", 0.0),
                deepMinutes = it.optInt("deepMinutes"),
                lightMinutes = it.optInt("lightMinutes"),
                remMinutes = it.optInt("remMinutes"),
                awakeMinutes = it.optInt("awakeMinutes"),
                awakeCount = it.optInt("awakeCount"),
                efficiency = it.optInt("efficiency").takeIf { v -> v > 0 },
                latencyMinutes = it.optInt("latencyMinutes").takeIf { v -> v >= 0 && it.has("latencyMinutes") },
                minSpo2 = it.optInt("minSpo2").takeIf { v -> v in 50..100 },
                firstHalfHeartRate = it.optInt("firstHalfHeartRate").takeIf { v -> v in 25..220 },
                secondHalfHeartRate = it.optInt("secondHalfHeartRate").takeIf { v -> v in 25..220 },
                stages = it.optJSONArray("stages")?.let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            val stage = array.optJSONObject(i) ?: continue
                            add(
                                SleepStage(
                                    startMillis = stage.optLong("startMillis"),
                                    endMillis = stage.optLong("endMillis"),
                                    kind = runCatching {
                                        SleepStageKind.valueOf(stage.optString("kind"))
                                    }.getOrDefault(SleepStageKind.UNKNOWN),
                                )
                            )
                        }
                    }
                } ?: emptyList(),
                averageHeartRate = it.optInt("averageHeartRate").takeIf { v -> v > 0 },
                averageHrv = it.optInt("averageHrv").takeIf { v -> v > 0 },
                averageSpo2 = it.optInt("averageSpo2").takeIf { v -> v > 0 },
                averageBreath = it.optInt("averageBreath").takeIf { v -> v > 0 },
            )
        }
        val stress = json.optJSONObject("stress")?.let {
            StressSummary(
                average = it.optInt("average"),
                max = it.optInt("max"),
                min = it.optInt("min"),
                sampleCount = it.optInt("sampleCount"),
            )
        }
        return HealthDay(
            date = json.optString("date"),
            steps = json.optInt("steps"),
            distanceMeters = json.optInt("distanceMeters"),
            activeCalories = json.optInt("activeCalories"),
            standHours = json.optInt("standHours"),
            heartRate = heartRate,
            restingHeartRate = json.optInt("restingHeartRate").takeIf { it > 0 },
            spo2 = spo2,
            sleep = sleep,
            stress = stress,
            source = runCatching { DataSource.valueOf(json.optString("source")) }
                .getOrDefault(DataSource.UNKNOWN),
        )
    }

    /**
     * 写文件。
     *
     * 不用 `File.writeText` 的原子替换（rename over existing 在某些环境会 EPERM），
     * 改成「写临时文件 → 覆盖」，失败时保留旧文件。
     */
    private fun writeAtomically(target: File, content: String) {
        dir.mkdirs()
        val temp = File(dir, "${target.name}.tmp")
        try {
            temp.writeText(content)
            if (!temp.renameTo(target)) {
                target.writeText(content)
                temp.delete()
            }
        } catch (e: Exception) {
            try {
                target.writeText(content)
            } catch (e2: Exception) {
                // 写不进去就算了，不能因此崩掉整个 app
            }
        }
    }
}
