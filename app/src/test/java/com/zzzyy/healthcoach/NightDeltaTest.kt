package com.zzzyy.healthcoach

import com.zzzyy.healthcoach.data.analysis.NOISE_THRESHOLD
import com.zzzyy.healthcoach.data.analysis.bedMinutesFrom20
import com.zzzyy.healthcoach.data.analysis.nightDeltas
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.SleepSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import java.util.Calendar

/**
 * 「昨晚 vs 前晚」的差异判定。
 *
 * 这是这个功能有没有价值的分水岭：
 * 如果只是把两个数字丢给模型，它会为噪声编故事；
 * 只有当差异超过**个人日常波动**时才值得解释。
 *
 * 覆盖：
 *   1. 差值方向与大小正确；
 *   2. 超出 1 SD 才标记 worthExplaining；
 *   3. 历史样本不足时不硬判（sd/z 为 null，但不能崩）；
 *   4. 入睡时刻跨零点不能算成 23 小时的差。
 */
class NightDeltaTest {

    private fun sleep(
        deep: Int,
        rem: Int = 90,
        hours: Double = 8.0,
        startHour: Int = 23,
    ): SleepSummary {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 14, startHour, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        return SleepSummary(
            startMillis = start,
            endMillis = start + (hours * 3600 * 1000).toLong(),
            hours = hours,
            deepMinutes = deep,
            lightMinutes = 300,
            remMinutes = rem,
            awakeMinutes = 10,
            awakeCount = 1,
            efficiency = 95,
            averageHeartRate = 52,
            averageHrv = 70,
            averageSpo2 = 96,
            averageBreath = 15,
            latencyMinutes = 10,
            minSpo2 = 94,
            stages = emptyList(),
        )
    }

    private fun day(date: String, sleep: SleepSummary?) = HealthDay(date = date, sleep = sleep)

    @Test
    fun `差值方向与大小正确`() {
        val prev = day("2026-11-13", sleep(deep = 80))
        val last = day("2026-11-14", sleep(deep = 60))
        val history = listOf(prev, last)
        val deep = nightDeltas(last, prev, history).first { it.label == "深睡" }
        assertEquals(80.0, deep.prev!!, 0.001)
        assertEquals(60.0, deep.last!!, 0.001)
        assertEquals(-20.0, deep.delta!!, 0.001)
    }

    @Test
    fun `小差异落在日常波动内不要求解释`() {
        // 真实场景：深睡本来就在 65~100 分钟之间跳（SD ≈ 12），昨晚比前晚少 3 分钟
        val history = listOf(70, 90, 75, 95, 80, 85, 65, 100, 78, 88).mapIndexed { i, deep ->
            day("2026-11-%02d".format(i + 1), sleep(deep = deep))
        }
        val prev = day("2026-11-11", sleep(deep = 80))
        val last = day("2026-11-12", sleep(deep = 77))
        val deep = nightDeltas(last, prev, history).first { it.label == "深睡" }
        assertNotNull(deep.sd)
        assertNotNull(deep.z)
        assertFalse("|z| 没到阈值不该要求解释", deep.worthExplaining)
    }

    @Test
    fun `大差异超出日常波动需要解释`() {
        val history = listOf(70, 90, 75, 95, 80, 85, 65, 100, 78, 88).mapIndexed { i, deep ->
            day("2026-11-%02d".format(i + 1), sleep(deep = deep))
        }
        val prev = day("2026-11-11", sleep(deep = 80))
        val last = day("2026-11-12", sleep(deep = 30))
        val deep = nightDeltas(last, prev, history).first { it.label == "深睡" }
        assertTrue("掉了 50 分钟远超日常波动", deep.worthExplaining)
        assertTrue(deep.z!! < -NOISE_THRESHOLD)
    }

    @Test
    fun `设备误差内的大 z 不算信号`() {
        // 血氧：历史几乎不变（SD≈1），93→94 会算出 z≈+2，
        // 但 1% 完全在设备误差内（±2~3%），不能报成「超出波动」。
        val history = listOf(93, 94, 93, 93, 94, 93, 94, 93, 93, 94).mapIndexed { i, spo2 ->
            day("2026-11-%02d".format(i + 1), sleep(deep = 70).copy(minSpo2 = spo2))
        }
        val prev = day("2026-11-11", sleep(deep = 70).copy(minSpo2 = 93))
        val last = day("2026-11-12", sleep(deep = 70).copy(minSpo2 = 94))
        val spo2 = nightDeltas(last, prev, history).first { it.label == "最低血氧" }
        assertTrue("z 应该很大", abs(spo2.z!!) >= NOISE_THRESHOLD)
        assertFalse("但没超过设备误差下限，不该解释", spo2.worthExplaining)
    }

    @Test
    fun `历史样本不足时不硬判也不会崩`() {
        val prev = day("2026-11-13", sleep(deep = 80))
        val last = day("2026-11-14", sleep(deep = 40))
        val deep = nightDeltas(last, prev, listOf(prev, last)).first { it.label == "深睡" }
        assertNull(deep.sd)
        assertNull(deep.z)
        // 没有参照时不假装有结论，但仍标记为「有差异值得提」
        assertTrue(deep.worthExplaining)
    }

    @Test
    fun `缺一晚睡眠时不产生差值`() {
        val prev = day("2026-11-13", null)
        val last = day("2026-11-14", sleep(deep = 60))
        nightDeltas(last, prev, listOf(prev, last)).forEach {
            assertNull("${it.label} 不该有差值", it.delta)
            assertFalse(it.worthExplaining)
        }
    }

    @Test
    fun `入睡时刻跨零点按距20点计算`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.NOVEMBER, 14, 23, 50, 0)
        val beforeMidnight = bedMinutesFrom20(cal.timeInMillis)
        cal.set(2026, Calendar.NOVEMBER, 15, 0, 20, 0)
        val afterMidnight = bedMinutesFrom20(cal.timeInMillis)
        assertEquals(230.0, beforeMidnight, 0.001) // 23:50 -> 20:00 起 230 分钟
        assertEquals(260.0, afterMidnight, 0.001)  // 00:20 -> 260 分钟
        assertEquals(30.0, afterMidnight - beforeMidnight, 0.001) // 实际只差 30 分钟
    }
}
