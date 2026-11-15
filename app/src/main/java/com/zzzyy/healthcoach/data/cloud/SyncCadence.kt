package com.zzzyy.healthcoach.data.cloud

import java.util.Calendar
import java.util.TimeZone

/**
 * 同步起点判定。
 *
 * 设计（2026-11-15 按用户意见定的口径）：
 *  - **首次同步**（从没同步过）→ 全量，把历史拉回来；
 *  - **之后每天第一次打开** → 只拉「昨天 00:00 起」，也就是昨天一整天 + 今天；
 *  - 拉完如果**一条新数据都没有** → 不是我们出错，多半是手机上的小米运动健康还没同步，
 *    这时提示用户「先去小米运动健康同步一次，再回来重试」（见 HealthViewModel）。
 *
 * 为什么用「昨天 00:00」而不是「上次同步时间 − 6 小时」：
 *  - 语义更清楚，用户和我都能一眼看懂窗口是哪一段；
 *  - 覆盖完整自然日，云端把昨夜数据延迟几小时补写也落在窗口内；
 *  - 不会因为「上次同步发生在昨晚 23:50」而只拉 10 分钟的数据。
 */
internal fun syncStartSeconds(
    lastSyncAtMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
): Long {
    if (lastSyncAtMillis <= 0L) return 0L
    val startMillis = yesterdayStartMillis(nowMillis, zone)
    val startSeconds = startMillis / 1000
    // 系统时间被改到很早以前时，算出来的起点会是负数或「未来时间」——
    // 这两种情况一律退回全量，避免上游把负数当成「有水位」而一条都不拉。
    return if (startSeconds <= 0L || startMillis > nowMillis) 0L else startSeconds
}

/** 本地时区的「昨天 00:00:00」毫秒时间戳。 */
internal fun yesterdayStartMillis(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val calendar = Calendar.getInstance(zone)
    calendar.timeInMillis = nowMillis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    calendar.add(Calendar.DAY_OF_YEAR, -1)
    return calendar.timeInMillis
}
