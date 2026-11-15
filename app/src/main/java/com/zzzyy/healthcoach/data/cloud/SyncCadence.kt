package com.zzzyy.healthcoach.data.cloud

/**
 * 决定「这次同步要拉多久的数据」。
 *
 * 背景：早期实现每次都按用户选的窗口（30/90 天）**全量重拉**，
 * 一次 90 天大约要发 60~80 个请求（steps 13 页、heart_rate 19 页、spo2 19 页…）。
 * 一天里多点几次「同步」，请求量就翻几倍 —— 这才是真正的风控风险来源，
 * 而不是「同步这件事本身」。
 *
 * 规则：
 *  1. 从没同步过 / 距上次**全量**超过 [fullIntervalMs] → 返回 0（做一次全量，兜住云端延迟补数据）；
 *  2. 否则只拉「上次同步时间 - [overlapSeconds]」之后的部分。
 *     留重叠是为了防止跨零点、以及云端把昨夜数据延迟几小时才写入时被漏掉。
 */
internal fun incrementalSinceSeconds(
    lastSyncAtMillis: Long,
    lastFullSyncAtMillis: Long,
    nowMillis: Long,
    overlapSeconds: Long,
    fullIntervalMs: Long,
): Long {
    if (lastSyncAtMillis <= 0L) return 0L
    val fullDue = lastFullSyncAtMillis <= 0L || nowMillis - lastFullSyncAtMillis > fullIntervalMs
    if (fullDue) return 0L
    val since = lastSyncAtMillis / 1000L - overlapSeconds
    // 时间出现倒流（改过系统时间）时退回全量，别算出个未来时间导致一条都不拉
    return if (since <= 0L || since * 1000L > nowMillis) 0L else since
}
