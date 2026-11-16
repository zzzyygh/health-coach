package com.zzzyy.healthcoach.data.analysis

import com.zzzyy.healthcoach.data.model.HealthDay
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 「昨晚 vs 前晚」的逐项差异，以及这个差异**是否超出个人日常波动**。
 *
 * 为什么要先算噪声：单晚数据本身很吵（消费级手环睡眠分期与 PSG 一致率约 76%，
 * 血氧误差 ±2~3%）。昨晚深睡比前晚少 15 分钟，是「真的变差了」还是「本来就这么跳」，
 * 光看两个数字永远说不清 —— 只有跟**自己近 30 天的标准差**比才有意义。
 *
 * 这一步必须由代码做（可复现、可测试），不能丢给模型心算：
 * 模型看见两个数字会本能地编一个解释，而「这属于日常波动」才是多数情况下的正确答案。
 */
data class MetricDelta(
    val label: String,
    val unit: String,
    val prev: Double?,
    val last: Double?,
    /** 昨晚 − 前晚 */
    val delta: Double?,
    /** 近 30 天该指标的标准差；样本不足时为 null */
    val sd: Double?,
    /** delta / sd；sd 为 0 或缺失时为 null */
    val z: Double?,
    /** 是否值得解释：|z| ≥ [NOISE_THRESHOLD]，或没有历史波动可参照但差异明显 */
    val worthExplaining: Boolean,
) {
    fun format(v: Double?): String = when {
        v == null -> "—"
        unit == "h" -> String.format("%.1f", v)
        unit == "%" -> String.format("%.0f", v)
        else -> String.format("%.0f", v)
    }
}

/** |z| 超过这个值才算「超出日常波动」。1 个标准差覆盖约 68% 的情况。 */
const val NOISE_THRESHOLD = 1.0

/** 算标准差至少要这么多晚，否则不拿它当参照。 */
private const val MIN_SAMPLE = 5

/**
 * @param history 近 30 天（含这两晚）用于估计个人波动，顺序不限
 */
fun nightDeltas(
    last: HealthDay?,
    prev: HealthDay?,
    history: List<HealthDay>,
): List<MetricDelta> {
    val lastSleep = last?.sleep
    val prevSleep = prev?.sleep

    fun series(name: String, pick: (com.zzzyy.healthcoach.data.model.SleepSummary) -> Double?): Double? {
        val values = history.mapNotNull { it.sleep?.let(pick) }
        if (values.size < MIN_SAMPLE) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance).takeIf { it > 1e-6 }
    }

    fun metric(
        label: String,
        unit: String,
        pick: (com.zzzyy.healthcoach.data.model.SleepSummary) -> Double?,
    ): MetricDelta {
        val p = prevSleep?.let(pick)
        val l = lastSleep?.let(pick)
        val sd = series(label, pick)
        val delta = if (p != null && l != null) l - p else null
        val z = if (delta != null && sd != null) delta / sd else null
        val worth = when {
            delta == null -> false
            z != null -> abs(z) >= NOISE_THRESHOLD
            // 没有历史波动可参照时，保守一点：只要有差异就值得提，但要求模型说明缺参照
            else -> abs(delta) > 0
        }
        return MetricDelta(label, unit, p, l, delta, sd, z, worth)
    }

    return listOf(
        metric("卧床时长", "h") { it.hours },
        metric("深睡", "分钟") { it.deepMinutes.toDouble() },
        metric("REM", "分钟") { it.remMinutes.toDouble() },
        metric("夜间清醒", "分钟") { it.awakeMinutes.toDouble() },
        metric("睡眠效率", "%") { it.efficiency?.toDouble() },
        metric("夜间平均心率", "bpm") { it.averageHeartRate?.toDouble() },
        metric("夜间 HRV", "") { it.averageHrv?.toDouble() },
        metric("最低血氧", "%") { it.minSpo2?.toDouble() },
        metric("入睡时刻(距20点)", "分钟") { sleep -> bedMinutesFrom20(sleep.startMillis) },
        metric("后半夜−前半夜心率", "bpm") { sleep ->
            val a = sleep.firstHalfHeartRate
            val b = sleep.secondHalfHeartRate
            if (a != null && b != null) (b - a).toDouble() else null
        },
    )
}

/** 入睡时刻换算成「距当天 20:00 的分钟数」，跨零点后继续累加，避免 23:50 和 00:20 差成 23 小时。 */
internal fun bedMinutesFrom20(startMillis: Long): Double {
    val calendar = Calendar.getInstance().apply { timeInMillis = startMillis }
    var minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    if (minutes < 20 * 60) minutes += 24 * 60
    // 减去 20:00 这个锚点，返回值才是「距 20 点多少分钟」
    return (minutes - 20 * 60).toDouble()
}
