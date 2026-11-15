package com.zzzyy.healthcoach.data.analysis

/**
 * 用「逐条心率采样 + 睡眠窗口」算出夜间心率统计。
 *
 * 为什么单独抽出来：本地库路径是从 `sleep_segment` 里直接读到夜间平均心率的，
 * 而**云端没有这个预聚合字段** —— 它只有逐条 `heart_rate` 采样。
 * 以前云端路径把 `averageHeartRate` / `firstHalfHeartRate` / `secondHalfHeartRate`
 * 一律留成 null，于是没有 root 的用户在这三项上恒为「无数据」，
 * 睡眠评分直接少了 12 + 6 = 18 分的分项。这个函数就是把云端的原始采样补成同样口径。
 *
 * 口径必须与本地一致（`MiHealthLocalDb.withHalfNightHeartRate`）：
 * 以睡眠窗口的中点为界，前半段/后半段分别取算术平均。
 *
 * @param timeline 逐条采样 `(epochSeconds, bpm)`，可以包含睡眠窗口外的点（会被忽略）
 */
internal fun nightHeartRateStats(
    startMillis: Long,
    endMillis: Long,
    timeline: List<Pair<Long, Int>>,
): NightHeartRate {
    val startSec = startMillis / 1000
    val endSec = endMillis / 1000
    if (endSec <= startSec || timeline.isEmpty()) return NightHeartRate(null, null, null)
    val mid = (startSec + endSec) / 2

    var sum = 0L
    var count = 0
    var firstSum = 0L
    var firstCount = 0
    var secondSum = 0L
    var secondCount = 0

    for ((time, bpm) in timeline) {
        if (time < startSec || time > endSec) continue
        sum += bpm
        count++
        if (time < mid) {
            firstSum += bpm
            firstCount++
        } else {
            secondSum += bpm
            secondCount++
        }
    }

    return NightHeartRate(
        average = if (count > 0) (sum / count).toInt() else null,
        firstHalf = if (firstCount > 0) (firstSum / firstCount).toInt() else null,
        secondHalf = if (secondCount > 0) (secondSum / secondCount).toInt() else null,
    )
}

/** 夜间心率三件套；某一段没有采样时对应字段为 null。 */
internal data class NightHeartRate(
    val average: Int?,
    val firstHalf: Int?,
    val secondHalf: Int?,
)
