package com.zzzyy.healthcoach.ui

import com.zzzyy.healthcoach.data.model.HealthDay

/**
 * 「个人基线」——康伴的核心概念。
 *
 * 别的健康 app 告诉你「你今天走了 8000 步，达标了吗」「你比同龄人差 12%」。
 * 康伴只回答一个问题：**今天跟你自己比，怎么样？**
 *
 * 所以这里不算分数、不发徽章、不跟人群平均比，
 * 只做一件事：把最近 30 天（不含今天）的中位数当作「你的常态」，
 * 今天的读数相对它的偏离，才是值得看的信号。
 */
enum class MetricKind(val key: String, val label: String, val unit: String, val short: String) {
    STEPS("steps", "步数", "步", "步数"),
    RESTING_HR("resting_hr", "静息心率", "bpm", "静息"),
    AVG_HR("avg_hr", "平均心率", "bpm", "均心率"),
    SLEEP("sleep", "睡眠时长", "小时", "睡眠"),
    SPO2("spo2", "血氧饱和度", "%", "血氧"),
    STRESS("stress", "压力", "", "压力"),
    SLEEP_REM("rem", "REM 睡眠", "分钟", "REM"),
    SLEEP_DEEP("deep", "深睡", "分钟", "深睡"),
}

/** 一个指标在某一天的值。 */
data class MetricPoint(val date: String, val value: Float)

/** 某个指标的完整序列 + 它自己的基线。 */
data class MetricSeries(
    val kind: MetricKind,
    val points: List<MetricPoint>,
    /** 过去 30 天（不含最新一天）的中位数 */
    val baseline: Float?,
    /** 基线用了几天样本 */
    val baselineSampleDays: Int,
    /**
     * 基线的正常波动半径（= 四分位距的一半）。
     *
     * 用真实分布算，而不是拍一个百分比：
     * 血氧这种几乎恒定的指标带宽会接近 0（画出来就是一条线），
     * 步数这种波动大的指标带宽就宽。
     */
    val baselineBand: Float? = null,
) {
    val values: List<Float> get() = points.map { it.value }
    val labels: List<String> get() = points.map { it.date }
    val latest: Float? get() = points.lastOrNull()?.value
    val latestDate: String? get() = points.lastOrNull()?.date
    val mean: Float? get() = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val min: Float? get() = values.minOrNull()
    val max: Float? get() = values.maxOrNull()

    /** 最新一天相对基线的偏移百分比（正 = 高于常态）。 */
    val deviationPercent: Float?
        get() {
            val b = baseline ?: return null
            if (b <= 0f) return null
            val l = latest ?: return null
            return (l - b) / b * 100f
        }
}

object HealthBaselines {

    /** 基线窗口长度（天）。 */
    const val WINDOW_DAYS = 30

    /** 迷你图里展示的天数。 */
    const val SPARK_DAYS = 14

    fun extract(day: HealthDay, kind: MetricKind): Float? = when (kind) {
        MetricKind.STEPS -> day.steps.toFloat().takeIf { it > 0f }
        MetricKind.RESTING_HR -> day.restingHeartRate?.toFloat()
        MetricKind.AVG_HR -> day.heartRate?.average?.toFloat()
        MetricKind.SLEEP -> day.sleep?.hours?.toFloat()
        MetricKind.SPO2 -> day.spo2?.average?.toFloat()
        MetricKind.STRESS -> day.stress?.average?.toFloat()
        MetricKind.SLEEP_REM -> day.sleep?.remMinutes?.toFloat()
        MetricKind.SLEEP_DEEP -> day.sleep?.deepMinutes?.toFloat()
    }

    /**
     * 计算某个指标的序列。
     *
     * 基线**故意不含最新一天** —— 否则「今天」自己会拉低/抬高基线，
     * 偏离值会被人为压小。
     */
    fun series(days: List<HealthDay>, kind: MetricKind): MetricSeries {
        val points = days.mapNotNull { day ->
            extract(day, kind)?.let { MetricPoint(day.date, it) }
        }
        val window = points.dropLast(1).takeLast(WINDOW_DAYS)
        return MetricSeries(
            kind = kind,
            points = points,
            baseline = median(window.map { it.value }),
            baselineSampleDays = window.size,
            baselineBand = bandHalfWidth(window.map { it.value }),
        )
    }

    fun all(days: List<HealthDay>): Map<MetricKind, MetricSeries> =
        MetricKind.entries.associateWith { series(days, it) }

    /**
     * 中位数。
     *
     * 用中位数而不是均值：一天 30000 步的爬山日会把均值拉飞，
     * 中位数不会被单日异常带偏，更接近「常态」。
     */
    fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * 四分位距的一半 = 「正常波动半径」。
     *
     * 样本少于 4 天时返回 null（样本太少，说不准正常范围）。
     */
    fun bandHalfWidth(values: List<Float>): Float? {
        if (values.size < 4) return null
        val sorted = values.sorted()
        val q1 = quantile(sorted, 0.25f)
        val q3 = quantile(sorted, 0.75f)
        val band = (q3 - q1) / 2f
        return band.takeIf { it > 0.0001f }
    }

    /** 线性插值分位数。 */
    fun quantile(sorted: List<Float>, q: Float): Float {
        if (sorted.isEmpty()) return 0f
        val pos = (sorted.size - 1) * q
        val low = pos.toInt()
        val high = minOf(low + 1, sorted.size - 1)
        val frac = pos - low
        return sorted[low] * (1f - frac) + sorted[high] * frac
    }

    /** 展示用的格式化：步数取整，心率取整，睡眠保留一位小数。 */
    fun format(kind: MetricKind, value: Float?): String {
        if (value == null) return "—"
        return when (kind) {
            MetricKind.STEPS -> value.toInt().toString()
            MetricKind.RESTING_HR, MetricKind.AVG_HR, MetricKind.SPO2 -> value.toInt().toString()
            MetricKind.SLEEP -> String.format("%.1f", value)
            MetricKind.STRESS, MetricKind.SLEEP_REM, MetricKind.SLEEP_DEEP -> value.toInt().toString()
        }
    }
}
