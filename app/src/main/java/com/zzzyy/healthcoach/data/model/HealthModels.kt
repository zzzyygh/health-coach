package com.zzzyy.healthcoach.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一天的聚合健康数据。
 *
 * 云端（`hlth.io.mi.com`）和本地（`com.mi.health` 的 SQLite）两条取数路线
 * 最终都归一到这个模型，上层（建议生成、UI）不关心数据来自哪儿。
 */
data class HealthDay(
    /** yyyy-MM-dd */
    val date: String,
    val steps: Int = 0,
    val distanceMeters: Int = 0,
    val activeCalories: Int = 0,
    /** 有效站立的小时数 */
    val standHours: Int = 0,
    val heartRate: HeartRateSummary? = null,
    /** 静息心率（本地库才有；云端不提供） */
    val restingHeartRate: Int? = null,
    val spo2: Spo2Summary? = null,
    val sleep: SleepSummary? = null,
    /** 全天压力（本地库才有；云端接口无此 key） */
    val stress: StressSummary? = null,
    /** 数据来源 */
    val source: DataSource = DataSource.UNKNOWN,
) {
    val hasData: Boolean
        get() = steps > 0 || heartRate != null || sleep != null || spo2 != null || stress != null
}

enum class DataSource {
    /** 本机 root 直读 com.mi.health 数据库 */
    LOCAL,

    /** 小米云端接口 */
    CLOUD,

    UNKNOWN,
}

data class HeartRateSummary(
    val average: Int,
    val min: Int,
    val max: Int,
    val sampleCount: Int,
)

data class Spo2Summary(
    val average: Int,
    val min: Int,
    val sampleCount: Int,
)

/**
 * 全天压力。
 *
 * 小米的 `stress_record` 每几分钟一条（`{"stress":29,"time":…}`），
 * 库里 2024-06 至今有 52 万条，按天聚合成均值/峰值。
 *
 * ⚠️ 这个值**不是**医学指标，是小米用 HRV 推出来的相对强度；
 * 同一台设备纵向比自己才有意义，不要跟别人的数值比。
 */
data class StressSummary(
    val average: Int,
    val max: Int,
    val min: Int,
    val sampleCount: Int,
)

/** 睡眠分期的一段。 */
data class SleepStage(
    val startMillis: Long,
    val endMillis: Long,
    val kind: SleepStageKind,
) {
    val minutes: Int get() = ((endMillis - startMillis) / 60000L).toInt().coerceAtLeast(0)
}

/**
 * 小米睡眠分期编码（用真实库对照验证过，见 `.work/watch/fd.db`）：
 *
 *   state=2 → 深睡（合计分钟数 = `sleep_deep_duration`）
 *   state=3 → 浅睡（= `sleep_light_duration`）
 *   state=4 → 快速眼动 REM（= `sleep_rem_duration`）
 *   state=5 → 清醒（= `sleep_awake_duration`）
 *   state=6 → 卧床未入睡（`sleep_trace_duration`，只有「戴了表但没睡着」才出现）
 */
enum class SleepStageKind(val label: String) {
    DEEP("深睡"),
    LIGHT("浅睡"),
    REM("快速眼动"),
    AWAKE("清醒"),
    UNKNOWN("未分类");

    companion object {
        fun fromState(state: Int): SleepStageKind = when (state) {
            2 -> DEEP
            3 -> LIGHT
            4 -> REM
            5 -> AWAKE
            else -> UNKNOWN
        }
    }
}

data class SleepSummary(
    /** 入睡时间戳（毫秒） */
    val startMillis: Long,
    /** 起床时间戳（毫秒） */
    val endMillis: Long,
    val hours: Double,
    /** 深睡分钟数 */
    val deepMinutes: Int,
    /** 浅睡分钟数 */
    val lightMinutes: Int,
    /** 快速眼动分钟数 */
    val remMinutes: Int = 0,
    /** 夜间清醒分钟数 */
    val awakeMinutes: Int = 0,
    /** 夜里醒来的次数 */
    val awakeCount: Int = 0,
    /** 睡眠效率（%，睡着时间 / 卧床时间） */
    val efficiency: Int? = null,
    val averageHeartRate: Int? = null,
    val averageHrv: Int? = null,
    val averageSpo2: Int? = null,
    val averageBreath: Int? = null,
    /** 入睡潜伏期（分钟）= 上床 → 真正睡着；Oura 的 Latency 因子 */
    val latencyMinutes: Int? = null,
    /** 夜间最低血氧（%），用于提示可能的呼吸问题 */
    val minSpo2: Int? = null,
    /** 前半夜平均心率（入睡 → 中点） */
    val firstHalfHeartRate: Int? = null,
    /** 后半夜平均心率（中点 → 起床） */
    val secondHalfHeartRate: Int? = null,
    /** 分期时间轴，按时间升序 */
    val stages: List<SleepStage> = emptyList(),
) {
    /** 深睡 + 浅睡 + REM 的分钟数（真正的「睡着」时长） */
    val asleepMinutes: Int get() = deepMinutes + lightMinutes + remMinutes

    /** 有分期数据时才能画时间轴 */
    val hasStages: Boolean get() = stages.size >= 2

    companion object {
        /** 解析 `items` 数组成分期列表；同时把 state=6 之外的段都留下。 */
        private fun parseStages(json: JSONObject): List<SleepStage> {
            val array = json.optJSONArray("items") ?: return emptyList()
            val stages = mutableListOf<SleepStage>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optLong("start_time", 0L)
                val end = item.optLong("end_time", 0L)
                if (start <= 0 || end <= start) continue
                stages.add(
                    SleepStage(
                        startMillis = start * 1000,
                        endMillis = end * 1000,
                        kind = SleepStageKind.fromState(item.optInt("state", 0)),
                    )
                )
            }
            return stages.sortedBy { it.startMillis }
        }

        /**
         * 从一条原始 sleep 记录构造。
         *
         * ⚠️ 坑（`.work/watch/export-health.mjs` 已实测确认）：
         * `sleep_duration` 字段**不可信**（7.6 小时的一晚写成 1560），
         * 睡眠时长必须由 `device_bedtime → device_wake_up_time` 计算；
         * `sleep_deep_duration` / `sleep_light_duration` / `sleep_rem_duration`
         * 以及 `sleep_awake_duration` 都是**分钟**。
         */
        fun fromLocalJson(json: JSONObject): SleepSummary? {
            val bed = json.optLong("device_bedtime", 0L)
            val wake = json.optLong("device_wake_up_time", 0L)
            if (bed <= 0 || wake <= bed) return null
            val hours = (wake - bed) / 3600.0
            if (hours < 1.0 || hours > 16.0) return null
            return SleepSummary(
                startMillis = bed * 1000,
                endMillis = wake * 1000,
                hours = Math.round(hours * 100) / 100.0,
                deepMinutes = json.optInt("sleep_deep_duration", 0),
                lightMinutes = json.optInt("sleep_light_duration", 0),
                remMinutes = json.optInt("sleep_rem_duration", 0),
                awakeMinutes = json.optInt("sleep_awake_duration", 0),
                awakeCount = json.optInt("awake_count", 0),
                efficiency = json.optInt("sleep_efficiency", 0).takeIf { it in 1..100 },
                averageHeartRate = json.optInt("avg_hr").takeIf { it > 0 },
                averageHrv = json.optInt("avg_hrv").takeIf { it > 0 },
                averageSpo2 = json.optInt("avg_spo2").takeIf { it > 0 },
                averageBreath = json.optDouble("avg_breath", 0.0).toInt().takeIf { it > 0 },
                latencyMinutes = latencyOf(json),
                minSpo2 = json.optInt("min_spo2", 0).takeIf { it in 50..100 },
                stages = parseStages(json),
            )
        }

        /**
         * 入睡潜伏期 = 上床时刻 → 真正睡着。
         *
         * `bed_timestamp` 是躺下的时间，`bedtime` 是判定入睡的时间，
         * 两者之差就是潜伏期。超过 2 小时或为负都视为无效（设备没记录）。
         */
        /**
         * 取一个整数字段，并做范围校验。
         * 云端的 `avg_hrv` 之类可能是浮点（如 45.6），`optInt` 会截断 —— 对 HRV 这种
         * 「看趋势不看小数」的指标足够；超出 [range] 视为脏数据返回 null。
         */
        private fun intOrNull(json: JSONObject, key: String, range: IntRange): Int? {
            if (!json.has(key)) return null
            val value = json.optDouble(key, Double.NaN)
            if (value.isNaN()) return null
            val rounded = Math.round(value).toInt()
            return rounded.takeIf { it in range }
        }

        private fun latencyOf(json: JSONObject): Int? {
            val bedTs = json.optLong("bed_timestamp", 0L)
            val bedtime = json.optLong("bedtime", 0L)
            if (bedTs <= 0 || bedtime <= bedTs) return null
            val minutes = ((bedtime - bedTs) / 60).toInt()
            return minutes.takeIf { it in 0..120 }
        }

        /**
         * 从云端的一条记录构造。
         *
         * 云端字段名不同：`bedtime` / `wake_up_time`（不是 device_bedtime）。
         * 云端**也有** rem / awake / awake_count / items，字段名与本地一致。
         */
        fun fromCloudJson(json: JSONObject): SleepSummary? {
            val bed = json.optLong("bedtime", 0L)
            val wake = json.optLong("wake_up_time", 0L)
            if (bed <= 0 || wake <= bed) return null
            val hours = (wake - bed) / 3600.0
            if (hours < 1.0 || hours > 16.0) return null
            return SleepSummary(
                startMillis = bed * 1000,
                endMillis = wake * 1000,
                hours = Math.round(hours * 100) / 100.0,
                deepMinutes = json.optInt("sleep_deep_duration", 0),
                lightMinutes = json.optInt("sleep_light_duration", 0),
                remMinutes = json.optInt("sleep_rem_duration", 0),
                awakeMinutes = json.optInt("sleep_awake_duration", 0),
                awakeCount = json.optInt("awake_count", 0),
                efficiency = json.optInt("sleep_efficiency", 0).takeIf { it in 1..100 },
                // 云端 sleep 记录里**确实有**这些预聚合值（实测字段清单）：
                // avg_hr / avg_hrv / avg_spo2 / avg_breath / min_spo2 / max_hr / min_hr。
                // 旧实现把它们全写成 null，导致无 root 用户在这几项上恒为「无数据」。
                averageHeartRate = intOrNull(json, "avg_hr", 25..220),
                averageHrv = intOrNull(json, "avg_hrv", 1..300),
                averageSpo2 = intOrNull(json, "avg_spo2", 50..100),
                averageBreath = intOrNull(json, "avg_breath", 4..40),
                latencyMinutes = latencyOf(json),
                minSpo2 = json.optInt("min_spo2", 0).takeIf { it in 50..100 },
                stages = parseStages(json),
            )
        }
    }
}
