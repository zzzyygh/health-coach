package com.zzzyy.healthcoach

import com.zzzyy.healthcoach.data.cloud.syncStartSeconds
import com.zzzyy.healthcoach.data.cloud.yesterdayStartMillis
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * 同步窗口判定（2026-11-15 按用户意见定的口径）。
 *
 * 要看住三件事：
 *   1. 首次同步必须全量（否则新装用户拿不到历史）；
 *   2. 之后每天第一次打开只拉「昨天 00:00 起」—— 不能因为上次同步在 23:50 就只拉 10 分钟；
 *   3. 跨月、跨年、时区都要对，不能算成「今天 00:00」少拉一天。
 */
class SyncCadenceTest {

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-11-15 18:00:00 +08 */
    private val now = 1794736800000L

    @Test
    fun `首次同步返回 0 表示全量`() {
        assertEquals(0L, syncStartSeconds(0L, now, shanghai))
    }

    @Test
    fun `每天第一次同步的起点是昨天零点`() {
        val lastSync = now - 24 * 3600 * 1000L
        val start = syncStartSeconds(lastSync, now, shanghai)
        // 2026-11-14 00:00:00 +08
        assertEquals(1794585600L, start)
    }

    @Test
    fun `昨晚 23点50分同步过 仍然拉昨天整天`() {
        // 2026-11-15 00:50 +08 做过同步，按旧逻辑（上次 -6h）只会拉几十分钟
        val lastSync = 1794675000000L
        val start = syncStartSeconds(lastSync, now, shanghai)
        assertEquals(1794585600L, start) // 仍然是 11-14 00:00
    }

    @Test
    fun `跨月边界正确`() {
        // 2026-12-01 09:00 +08 → 昨天是 11-30 00:00
        val dec1 = 1796086800000L
        assertEquals(
            1794585600L + 16L * 86400,
            syncStartSeconds(dec1 - 86400000L, dec1, shanghai),
        )
    }

    @Test
    fun `系统时间回拨时退回全量`() {
        // 「昨天零点」被算成未来时间（系统时间被改到很早以前）
        val weirdNow = 0L
        assertEquals(0L, syncStartSeconds(1L, weirdNow, shanghai))
    }

    @Test
    fun `yesterdayStartMillis 归零时分秒`() {
        val y = yesterdayStartMillis(now, shanghai)
        val cal = java.util.Calendar.getInstance(shanghai).apply { timeInMillis = y }
        assertEquals(0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(java.util.Calendar.MINUTE))
        assertEquals(0, cal.get(java.util.Calendar.SECOND))
        assertEquals(14, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
