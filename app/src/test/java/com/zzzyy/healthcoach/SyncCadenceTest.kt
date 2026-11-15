package com.zzzyy.healthcoach

import com.zzzyy.healthcoach.data.cloud.incrementalSinceSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同步频率控制。
 *
 * 这组断言针对的是一个真实问题：早期实现每次都全量重拉 30/90 天，
 * 一次 90 天要发 60~80 个请求，反复点几次就把请求量翻倍 —— 有被风控的风险。
 *
 * 校验点：
 *   1. 从没同步过 → 必须全量（否则新用户拿不到历史）；
 *   2. 刚同步过 → 只拉增量（且带重叠窗口）；
 *   3. 距上次**全量**超过 7 天 → 必须再来一次全量（兜住云端延迟补数据）；
 *   4. 系统时间被改到过去 → 退回全量，不能算出未来时间导致一条都不拉。
 */
class SyncCadenceTest {

    private val now = 1_800_000_000_000L
    private val overlap = 6 * 3600L
    private val fullInterval = 7L * 24 * 3600 * 1000
    private val day = 24L * 3600 * 1000

    @Test
    fun `从未同步过时做全量`() {
        val r = incrementalSinceSeconds(0L, 0L, now, overlap, fullInterval)
        assertEquals(0L, r)
    }

    @Test
    fun `刚同步过只拉增量并留出重叠窗口`() {
        val lastSync = now - 3600_000L      // 1 小时前
        val lastFull = now - 2 * day        // 2 天前做过全量
        val r = incrementalSinceSeconds(lastSync, lastFull, now, overlap, fullInterval)
        assertEquals(lastSync / 1000L - overlap, r)
    }

    @Test
    fun `距上次全量超过七天时必须全量`() {
        val lastSync = now - 3600_000L
        val lastFull = now - 8 * day
        val r = incrementalSinceSeconds(lastSync, lastFull, now, overlap, fullInterval)
        assertEquals(0L, r)
    }

    @Test
    fun `系统时间回拨时退回全量`() {
        // 上次同步时间在「未来」——说明系统时间被改过
        val lastSync = now + 3 * day
        val lastFull = now - 1 * day
        val r = incrementalSinceSeconds(lastSync, lastFull, now, overlap, fullInterval)
        assertEquals(0L, r)
    }

    @Test
    fun `恰好第七天仍算增量边界之外`() {
        val lastSync = now - 3600_000L
        val lastFull = now - 7 * day - 1
        val r = incrementalSinceSeconds(lastSync, lastFull, now, overlap, fullInterval)
        assertEquals(0L, r)
    }
}
