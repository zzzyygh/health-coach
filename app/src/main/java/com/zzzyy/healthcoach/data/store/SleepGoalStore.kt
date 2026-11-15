package com.zzzyy.healthcoach.data.store

import android.content.Context
import com.zzzyy.healthcoach.data.analysis.SleepScorer

/**
 * 用户手动设定的作息目标。
 *
 * 为什么让用户自己定、而不是从数据推断：
 *   - 设备推断出来的是「你最近实际几点睡」，不是「你希望几点睡」；
 *   - 早睡早起的改善无法在「对比历史习惯」的评分里体现出来 ——
 *     你连续早睡一周，基线也跟着提前，评分永远不会变好。
 * 所以节律维度的锚点必须是人定的目标，设备的记录只用来算偏差。
 *
 * 存储格式：从 20:00 起算的分钟数（与 [SleepScorer] 内部一致），-1 表示未设定。
 */
class SleepGoalStore(context: Context) {

    private val prefs = context.getSharedPreferences("sleep_goal", Context.MODE_PRIVATE)

    fun load(): SleepScorer.Goal = SleepScorer.Goal(
        bedtimeMinutes = prefs.getInt(KEY_BEDTIME, UNSET).takeIf { it != UNSET },
        wakeMinutes = prefs.getInt(KEY_WAKE, UNSET).takeIf { it != UNSET },
    )

    fun save(goal: SleepScorer.Goal) {
        prefs.edit()
            .putInt(KEY_BEDTIME, goal.bedtimeMinutes ?: UNSET)
            .putInt(KEY_WAKE, goal.wakeMinutes ?: UNSET)
            .apply()
    }

    private companion object {
        const val KEY_BEDTIME = "target_bedtime"
        const val KEY_WAKE = "target_wake"
        const val UNSET = -1
    }
}
