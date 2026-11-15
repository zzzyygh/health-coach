package com.zzzyy.healthcoach

import com.zzzyy.healthcoach.data.analysis.nightHeartRateStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 夜间心率聚合（无 root 时用云端逐条采样补出评分需要的三个值）。
 *
 * 校验点：
 *   1. 只统计睡眠窗口内的采样，窗口外的点不能污染结果；
 *   2. 前半夜/后半夜按窗口中点切分，口径与本地库一致；
 *   3. 没有采样时要返回 null，而不是 0（0 会被当成真实心率参与评分）。
 */
class NightHeartRateTest {

    @Test
    fun `窗口内采样算平均与前后半夜`() {
        val start = 1_000_000_000_000L // ms
        val startSec = start / 1000
        val end = start + 8 * 3600 * 1000L
        val endSec = end / 1000
        val mid = (startSec + endSec) / 2

        val timeline = listOf(
            startSec + 600 to 50,        // 前半夜
            startSec + 1200 to 52,       // 前半夜
            mid + 600 to 60,             // 后半夜
            mid + 1200 to 62,            // 后半夜
            startSec - 60 to 99,         // 窗口前，必须忽略
            endSec + 60 to 98,           // 窗口后，必须忽略
        )

        val r = nightHeartRateStats(start, end, timeline)
        assertEquals(56, r.average)      // (50+52+60+62)/4 = 56
        assertEquals(51, r.firstHalf)    // (50+52)/2
        assertEquals(61, r.secondHalf)   // (60+62)/2
    }

    @Test
    fun `没有采样时返回 null 而不是 0`() {
        val r = nightHeartRateStats(1_000_000_000_000L, 1_000_000_000_000L + 7 * 3600 * 1000L, emptyList())
        assertNull(r.average)
        assertNull(r.firstHalf)
        assertNull(r.secondHalf)
    }

    @Test
    fun `窗口外的采样不会产生结果`() {
        val start = 1_000_000_000_000L
        val end = start + 7 * 3600 * 1000L
        val timeline = listOf((start / 1000) - 600 to 70, (end / 1000) + 600 to 72)
        val r = nightHeartRateStats(start, end, timeline)
        assertNull(r.average)
    }

    @Test
    fun `只有后半夜采样时前半夜为 null`() {
        val start = 1_000_000_000_000L
        val end = start + 8 * 3600 * 1000L
        val mid = ((start / 1000) + (end / 1000)) / 2
        val r = nightHeartRateStats(start, end, listOf(mid + 300 to 58))
        assertEquals(58, r.average)
        assertNull(r.firstHalf)
        assertEquals(58, r.secondHalf)
    }
}
