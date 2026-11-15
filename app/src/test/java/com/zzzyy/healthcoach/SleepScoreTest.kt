package com.zzzyy.healthcoach

import com.zzzyy.healthcoach.data.analysis.SleepScorer
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.SleepSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 睡眠评分验证。
 *
 * 重点不是「能跑」，而是四件事：
 *   1. 深睡低时**必须明显扣分**（初版这里被其他满分维度稀释掉了，是真实 bug）；
 *   2. 睡太久**也要扣分**（U 型关系，长睡一侧不比短睡轻）；
 *   3. 缺数据时不能把总分拉成 0，也不能假装满分；
 *   4. 跨零点的时间差不能算成 23 小时。
 */
class SleepScoreTest {

    private fun sleep(
        bedHour: Int,
        bedMin: Int,
        hours: Double,
        deep: Int,
        light: Int,
        rem: Int,
        awake: Int = 0,
        awakeCount: Int = 0,
        efficiency: Int? = null,
        hr: Int? = null,
        hrv: Int? = null,
        latency: Int? = null,
        minSpo2: Int? = null,
        firstHalfHr: Int? = null,
        secondHalfHr: Int? = null,
    ): SleepSummary {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 14, bedHour, bedMin, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        return SleepSummary(
            startMillis = start,
            endMillis = start + (hours * 3600_000).toLong(),
            hours = hours,
            deepMinutes = deep,
            lightMinutes = light,
            remMinutes = rem,
            awakeMinutes = awake,
            awakeCount = awakeCount,
            efficiency = efficiency,
            averageHeartRate = hr,
            averageHrv = hrv,
            latencyMinutes = latency,
            minSpo2 = minSpo2,
            firstHalfHeartRate = firstHalfHr,
            secondHalfHeartRate = secondHalfHr,
        )
    }

    private fun dim(result: SleepScorer.Result, key: String) =
        result.dimensions.first { it.key == key }

    // ── 核心回归：深睡 10% 不能得高分 ─────────────────────────────

    @Test
    fun `深睡只有百分之十时必须明显扣分`() {
        // 真实数据：2026-11-14，深睡 48/469 = 10%，其余各项都好
        val s = sleep(
            bedHour = 2, bedMin = 38, hours = 7.82,
            deep = 48, light = 287, rem = 134,
            awake = 0, awakeCount = 0, efficiency = 98,
            hr = 52, hrv = 75, latency = 10, minSpo2 = 93,
            firstHalfHr = 50, secondHalfHr = 53,
        )
        val baseline = SleepScorer.Baseline(sleepHeartRate = 52, sleepHrv = 75)
        val result = SleepScorer.scoreSleep(s, baseline)

        val deep = dim(result, "deep")
        assertTrue("深睡 10% 应拿不到 12/22，实际 ${deep.score}", (deep.score ?: 99) <= 12)
        // 初版这里算出 93 分，因为其他维度满分把它稀释掉了
        assertTrue("总分必须明显低于 90，实际 ${result.total}", result.total < 90)
        println("深睡10%场景总分：${result.total}（${result.grade}），深睡 ${deep.score}/22")
    }

    @Test
    fun `深睡达标时该拿满分`() {
        val s = sleep(
            bedHour = 23, bedMin = 30, hours = 8.0,
            deep = 100, light = 300, rem = 100,   // 深睡 100/500 = 20%
            efficiency = 96,
        )
        assertEquals(22, dim(SleepScorer.scoreSleep(s), "deep").score)
    }

    // ── 长睡必须扣分（U 型） ─────────────────────────────────────

    @Test
    fun `睡太久要扣分且不比睡不够轻`() {
        fun durationOf(hours: Double): Int {
            val s = sleep(23, 0, hours, 80, 300, 100, efficiency = 95)
            return dim(SleepScorer.scoreSleep(s), "duration").score ?: -1
        }
        val optimal = durationOf(7.5)
        val short = durationOf(6.0)
        val long = durationOf(9.5)
        val veryLong = durationOf(10.5)

        assertEquals("7.5 小时应满分", 7, optimal)
        assertTrue("6 小时应扣分，实际 $short", short < optimal)
        assertTrue("9.5 小时应扣分，实际 $long", long < optimal)
        assertTrue("10.5 小时应扣得更多，实际 $veryLong", veryLong < long)
        // U 型：长睡一侧不比短睡轻（Cappuccio 2010：9h RR 已高于 6h）
        assertTrue("9.5 小时($long) 应不高于 6 小时($short)", long <= short)
    }

    @Test
    fun `七到八小时是最优区间`() {
        fun durationOf(hours: Double) =
            dim(
                SleepScorer.scoreSleep(sleep(23, 0, hours, 80, 300, 100, efficiency = 95)),
                "duration",
            ).score
        assertEquals(7, durationOf(7.0))
        assertEquals(7, durationOf(7.5))
        assertEquals(7, durationOf(8.0))
        assertTrue("8.5 小时应开始扣分", (durationOf(8.5) ?: 7) < 7)
    }

    // ── 其他维度 ─────────────────────────────────────────────────

    @Test
    fun `理想睡眠应该优秀`() {
        val s = sleep(
            bedHour = 23, bedMin = 30, hours = 7.8,
            deep = 90, light = 310, rem = 90,
            awake = 5, awakeCount = 1, efficiency = 96,
            hr = 50, hrv = 80, latency = 15, minSpo2 = 96,
            firstHalfHr = 48, secondHalfHr = 52,
        )
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = SleepScorer.bedtimeMinutesOf(s.startMillis),
            wakeMinutes = SleepScorer.wakeMinutesOf(s.endMillis),
            sleepHeartRate = 50,
            sleepHrv = 80,
        )
        val result = SleepScorer.scoreSleep(s, baseline)
        assertTrue("理想睡眠应 ≥85，实际 ${result.total}", result.total >= 85)
        assertEquals("优秀", result.grade)
    }

    @Test
    fun `REM偏少要扣分`() {
        val low = sleep(23, 0, 8.0, deep = 90, light = 400, rem = 40, efficiency = 95)   // REM 7.5%
        val fine = sleep(23, 0, 8.0, deep = 90, light = 300, rem = 110, efficiency = 95)  // REM 22%
        val lowScore = dim(SleepScorer.scoreSleep(low), "rem").score!!
        val fineScore = dim(SleepScorer.scoreSleep(fine), "rem").score!!
        assertEquals(10, fineScore)
        assertTrue("REM 7.5% 应扣到 4 分以下，实际 $lowScore", lowScore <= 4)
    }

    @Test
    fun `秒睡不算好`() {
        val instant = sleep(23, 0, 8.0, 90, 300, 110, efficiency = 95, latency = 2)
        val normal = sleep(23, 0, 8.0, 90, 300, 110, efficiency = 95, latency = 15)
        val instantScore = dim(SleepScorer.scoreSleep(instant), "latency").score!!
        val normalScore = dim(SleepScorer.scoreSleep(normal), "latency").score!!
        assertEquals(3, normalScore)
        assertTrue("2 分钟入睡不该满分（Oura：秒睡可能是睡眠不足）", instantScore < normalScore)
    }

    @Test
    fun `夜间心率高于基线要扣分`() {
        val s = sleep(23, 0, 8.0, 90, 300, 110, efficiency = 95, hr = 58)
        val baseline = SleepScorer.Baseline(sleepHeartRate = 50)
        assertEquals(0, dim(SleepScorer.scoreSleep(s, baseline), "hr").score)
    }

    @Test
    fun `前半夜心率高于后半夜要扣分`() {
        val reversed = sleep(23, 0, 8.0, 90, 300, 110, firstHalfHr = 58, secondHalfHr = 50)
        assertEquals(0, dim(SleepScorer.scoreSleep(reversed), "hr_dip").score)
    }

    // ── 数据缺失与边界 ───────────────────────────────────────────

    @Test
    fun `缺分期数据时深睡和REM不计分且说明缺失分数`() {
        val s = sleep(23, 0, 8.0, deep = 0, light = 0, rem = 0, efficiency = 95)
        val result = SleepScorer.scoreSleep(s)
        assertNull(dim(result, "deep").score)
        assertNull(dim(result, "rem").score)
        assertTrue("应提示缺失 32 分（深睡22+REM10）", result.missingPoints >= 32)
        // 缺深睡(22)+REM(10) 后只剩 时长7+效率10+连续性10 = 27
        assertTrue("缺两项也不该把总分弄成 0，实际 ${result.total}", result.total >= 25)
    }

    @Test
    fun `跨零点的入睡时间差不能算成23小时`() {
        val a = SleepScorer.bedtimeMinutesOf(
            Calendar.getInstance().apply {
                set(2026, Calendar.NOVEMBER, 14, 23, 50, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        )!!
        val b = SleepScorer.bedtimeMinutesOf(
            Calendar.getInstance().apply {
                set(2026, Calendar.NOVEMBER, 15, 0, 10, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        )!!
        assertTrue(
            "23:50 与 00:10 的差应 ≤20 分钟，实际 ${kotlin.math.abs(a - b)}",
            kotlin.math.abs(a - b) <= 20,
        )
    }

    @Test
    fun `节律用用户设定的目标而不是历史习惯`() {
        val s = sleep(23, 30, 8.0, 90, 300, 110, efficiency = 95)
        val matching = SleepScorer.Goal(
            bedtimeMinutes = SleepScorer.bedtimeMinutesOf(s.startMillis),
            wakeMinutes = SleepScorer.wakeMinutesOf(s.endMillis),
        )
        val far = SleepScorer.Goal(
            bedtimeMinutes = matching.bedtimeMinutes!! + 180,
            wakeMinutes = matching.wakeMinutes!! + 180,
        )
        val nearScore = dim(SleepScorer.scoreSleep(s, SleepScorer.Baseline(), matching), "rhythm").score!!
        val farScore = dim(SleepScorer.scoreSleep(s, SleepScorer.Baseline(), far), "rhythm").score!!
        assertEquals("节律满分是 8（入睡 4 + 起床 4）", 8, nearScore)
        assertTrue("偏离目标 3 小时应扣到 1 分以内，实际 $farScore", farScore <= 1)
    }

    @Test
    fun `基线只用目标日之前的数据`() {
        fun day(date: String, bedHour: Int) = HealthDay(
            date = date,
            sleep = sleep(bedHour, 0, 8.0, 90, 300, 110, 0, 0, 95),
        )
        val days = listOf(
            day("2026-11-01", 22),
            day("2026-11-02", 22),
            day("2026-11-03", 22),
            day("2026-11-04", 3),   // 目标日当天
            day("2026-11-05", 3),
        )
        val baseline = SleepScorer.baselineOf(days, beforeDate = "2026-11-04")
        assertNotNull(baseline.bedtimeMinutes)
        assertEquals(120, baseline.bedtimeMinutes)
    }

    @Test
    fun `血氧按近七天中位数判而不是单晚最低值`() {
        // 今晚 93%（设备 ±3% 误差范围内），但近 7 天中位 95% → 不该判低分
        val s = sleep(23, 0, 8.0, 90, 300, 110, efficiency = 95, minSpo2 = 93)
        val withTrend = SleepScorer.Baseline(recentMinSpo2 = 95)
        val withoutTrend = SleepScorer.Baseline()

        val trendScore = dim(SleepScorer.scoreSleep(s, withTrend), "spo2").score!!
        val singleScore = dim(SleepScorer.scoreSleep(s, withoutTrend), "spo2").score!!

        assertTrue("近 7 天中位 95% 应拿高分，实际 $trendScore/5", trendScore >= 4)
        assertTrue("单晚 93% 会被判低（所以才要看趋势），实际 $singleScore/5", singleScore <= 3)
    }

    @Test
    fun `血氧长期偏低才算问题`() {
        val s = sleep(23, 0, 8.0, 90, 300, 110, efficiency = 95, minSpo2 = 92)
        val bad = SleepScorer.Baseline(recentMinSpo2 = 92)
        assertEquals(1, dim(SleepScorer.scoreSleep(s, bad), "spo2").score)
    }

    @Test
    fun `近期都差时今天突然好要打折`() {
        // 近 7 日均 78，今天 95 → 高出 17 → 修正约 -9（上限 -10）
        val adjust = SleepScorer.trendAdjustment(today = 95, recentAverage = 78)
        assertTrue("应打折，实际 $adjust", adjust <= -5)
        val result = SleepScorer.scoreSleep(
            sleep(23, 0, 7.5, 90, 300, 110, efficiency = 95, hr = 45, hrv = 90,
                latency = 15, minSpo2 = 97, firstHalfHr = 45, secondHalfHr = 50),
            SleepScorer.Baseline(
                bedtimeMinutes = 180, wakeMinutes = 660,
                sleepHeartRate = 50, sleepHrv = 80,
                recentMinSpo2 = 97, recentDeepPercent = 18.0, recentDeepMinutes = 90,
                recentAverageScore = 78,
            ),
        )
        assertTrue("最终分应低于分项和，${result.subtotal} → ${result.total}",
            result.total < result.subtotal)
    }

    @Test
    fun `近期都好时今天偶尔差要补回`() {
        // 近 7 日均 88，今天 70 → 低 18 → 修正 +9
        val adjust = SleepScorer.trendAdjustment(today = 70, recentAverage = 88)
        assertTrue("应补回（上限 +6），实际 $adjust", adjust in 3..6)
    }

    @Test
    fun `正常波动不调整`() {
        // 日与日差 8 分以内算正常，不能动 —— 否则所有分数都被拉向均值
        assertEquals(0, SleepScorer.trendAdjustment(today = 84, recentAverage = 81))
        assertEquals(0, SleepScorer.trendAdjustment(today = 78, recentAverage = 85))
        assertEquals(0, SleepScorer.trendAdjustment(today = 80, recentAverage = 80))
        assertEquals(0, SleepScorer.trendAdjustment(today = 95, recentAverage = null))
    }

    @Test
    fun `只有异常高才惩罚且惩罚比奖励重`() {
        // 异常高：近 7 日均 78，今天 95（超出 17）→ 扣 (17-8)×0.7 ≈ 6
        val penalty = SleepScorer.trendAdjustment(today = 95, recentAverage = 78)
        assertTrue("异常高应扣分，实际 $penalty", penalty <= -5)
        // 异常低：近 7 日均 86，今天 72（低于 14）→ 补 (14-8)×0.4 ≈ 2
        val reward = SleepScorer.trendAdjustment(today = 72, recentAverage = 86)
        assertTrue("异常低应补回，实际 $reward", reward in 1..4)
        // 同等偏离下，惩罚幅度必须大于奖励（睡得差是真差，不该被均值掩盖）
        assertTrue(
            "惩罚($penalty) 幅度应大于奖励($reward)",
            kotlin.math.abs(penalty) > reward,
        )
    }

    @Test
    fun `趋势修正有上下限`() {
        assertEquals(-10, SleepScorer.trendAdjustment(today = 100, recentAverage = 50))
        assertEquals(6, SleepScorer.trendAdjustment(today = 20, recentAverage = 95))
    }

    @Test
    fun `异常低的一天不会被均值抬太多`() {
        // 深睡只有 6% 那种差的一晚，不能被「近期都不错」补成好看的分
        val s = sleep(1, 0, 8.8, deep = 30, light = 341, rem = 148, efficiency = 93, awake = 6, awakeCount = 2)
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = 100, wakeMinutes = 700,
            sleepHeartRate = 52, sleepHrv = 70,
            recentMinSpo2 = 94, recentDeepPercent = 14.0, recentDeepMinutes = 72,
            recentAverageScore = 86,
        )
        val result = SleepScorer.scoreSleep(s, baseline)
        assertTrue("分项和应该低，实际 ${result.subtotal}", result.subtotal < 80)
        assertTrue("修正最多 +6，实际 ${result.trendAdjust}", result.trendAdjust <= 6)
        assertTrue("最终也不该被抬到 85 以上，实际 ${result.total}", result.total < 85)
    }

    @Test
    fun `深睡连续偏低时按近期中位判而不是按今天`() {
        // 今天深睡 15%（77 分钟），但近 7 天中位只有 10%
        val s = sleep(23, 0, 8.7, deep = 77, light = 324, rem = 117, efficiency = 97)
        val withTrend = SleepScorer.Baseline(recentDeepPercent = 10.0, recentDeepMinutes = 48)
        val withoutTrend = SleepScorer.Baseline()

        val trendScore = dim(SleepScorer.scoreSleep(s, withTrend), "deep").score!!
        val singleScore = dim(SleepScorer.scoreSleep(s, withoutTrend), "deep").score!!

        assertTrue("按今天 15% 判应该拿高分，实际 $singleScore/22", singleScore >= 18)
        assertTrue("按近期中位 10% 判应明显更低，实际 $trendScore/22", trendScore < singleScore)
        assertTrue("差距应显著（≥6 分），实际差 ${singleScore - trendScore}", singleScore - trendScore >= 6)
    }

    @Test
    fun `深睡低于百分之十时总分封顶75`() {
        val s = sleep(
            bedHour = 1, bedMin = 0, hours = 8.0,
            deep = 40, light = 300, rem = 120,   // 深睡 8.7%
            awake = 0, awakeCount = 0, efficiency = 98,
            hr = 45, hrv = 90, latency = 15, minSpo2 = 97,
            firstHalfHr = 45, secondHalfHr = 50,
        )
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = 300, wakeMinutes = 700,
            sleepHeartRate = 50, sleepHrv = 80,
            recentMinSpo2 = 96, recentDeepPercent = 9.0, recentDeepMinutes = 45,
        )
        val result = SleepScorer.scoreSleep(s, baseline)
        assertEquals("深睡 <10% 应封顶 75", 75, result.cap)
        assertTrue("最终分不得超过上限，实际 ${result.total}", result.total <= 75)
    }

    @Test
    fun `深睡十到十三之间封顶82`() {
        val s = sleep(1, 0, 8.0, deep = 60, light = 300, rem = 110, efficiency = 97,
            hr = 45, hrv = 90, latency = 15, minSpo2 = 97, firstHalfHr = 45, secondHalfHr = 50)
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = 300, wakeMinutes = 700, sleepHeartRate = 50, sleepHrv = 80,
            recentMinSpo2 = 96, recentDeepPercent = 12.0, recentDeepMinutes = 58,
        )
        val result = SleepScorer.scoreSleep(s, baseline)
        assertEquals("深睡 10~13% 封顶 82", 82, result.cap)
        assertTrue(result.total <= 82)
    }

    @Test
    fun `深睡达标时不封顶`() {
        val s = sleep(23, 0, 8.0, deep = 110, light = 290, rem = 100, efficiency = 97,
            hr = 45, hrv = 90, latency = 15, minSpo2 = 97, firstHalfHr = 45, secondHalfHr = 50)
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = 300, wakeMinutes = 700, sleepHeartRate = 50, sleepHrv = 80,
            recentMinSpo2 = 96, recentDeepPercent = 20.0, recentDeepMinutes = 100,
        )
        val result = SleepScorer.scoreSleep(s, baseline)
        assertNull("深睡 20% 不该封顶", result.cap)
        assertTrue("应接近满分，实际 ${result.total}", result.total >= 90)
    }

    @Test
    fun `主观分是附加调整而不是加权`() {
        val s = sleep(1, 0, 8.7, deep = 77, light = 324, rem = 117, awake = 4, awakeCount = 1,
            efficiency = 97, hr = 50, hrv = 82, latency = 10, minSpo2 = 93,
            firstHalfHr = 48, secondHalfHr = 51)
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = 340, wakeMinutes = 800, sleepHeartRate = 53, sleepHrv = 70,
            recentMinSpo2 = 94, recentDeepPercent = 10.0, recentDeepMinutes = 48,
            recentAverageScore = 81,
        )
        val noSubjective = SleepScorer.scoreSleep(s, baseline)
        val withLow = SleepScorer.scoreSleep(s, baseline, subjectiveQuality = 2)
        val withHigh = SleepScorer.scoreSleep(s, baseline, subjectiveQuality = 5)

        assertNull("未打卡时不做调整", noSubjective.subjectiveAdjust)
        assertEquals(noSubjective.deviceScore, noSubjective.total)

        // 附加调整：最多 ±8，设备分始终是主分
        assertTrue("自评差应减分，实际 ${withLow.subjectiveAdjust}", (withLow.subjectiveAdjust ?: 0) <= -5)
        assertTrue("自评好应加分，实际 ${withHigh.subjectiveAdjust}", (withHigh.subjectiveAdjust ?: 0) >= 1)
        assertTrue("调整幅度不得超过 8", kotlin.math.abs(withLow.subjectiveAdjust!!) <= 8)
        assertTrue("调整幅度不得超过 8", kotlin.math.abs(withHigh.subjectiveAdjust!!) <= 8)
        // 关键：打 5 分也不能把设备分推成「优秀」
        assertTrue(
            "设备 ${noSubjective.deviceScore} 打 5 分不该变成 ≥85，实际 ${withHigh.total}",
            withHigh.total <= noSubjective.deviceScore + 8,
        )
        println("设备 ${noSubjective.total} / 自评2分 ${withLow.total}（${withLow.subjectiveAdjust}）/ 自评5分 ${withHigh.total}（${withHigh.subjectiveAdjust}）")
    }

    @Test
    fun `自评一分也保留底分`() {
        assertEquals(15, SleepScorer.subjectiveScore(1))
        assertEquals(35, SleepScorer.subjectiveScore(2))
        assertEquals(55, SleepScorer.subjectiveScore(3))
        assertEquals(75, SleepScorer.subjectiveScore(4))
        assertEquals(95, SleepScorer.subjectiveScore(5))
        assertNull(SleepScorer.subjectiveScore(null))
        assertNull(SleepScorer.subjectiveScore(0))
    }

    @Test
    fun `各项满分加起来等于100`() {
        // 用一份完全理想的数据，检查分项满分总和是 100
        val s = sleep(
            bedHour = 23, bedMin = 30, hours = 7.5,
            deep = 100, light = 300, rem = 100,
            awake = 0, awakeCount = 0, efficiency = 100,
            hr = 45, hrv = 100, latency = 15, minSpo2 = 98,
            firstHalfHr = 45, secondHalfHr = 50,
        )
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = SleepScorer.bedtimeMinutesOf(s.startMillis),
            wakeMinutes = SleepScorer.wakeMinutesOf(s.endMillis),
            sleepHeartRate = 50,
            sleepHrv = 80,
            recentMinSpo2 = 98,
        )
        val result = SleepScorer.scoreSleep(s, baseline)
        val maxSum = result.dimensions.sumOf { it.maxScore }
        assertEquals("分项满分必须正好 100，否则总分不可解释", 100, maxSum)
        assertTrue("理想数据应接近满分，实际 ${result.total}", result.total >= 95)
    }

    @Test
    fun `完美数据不超过100分`() {
        val best = sleep(
            bedHour = 23, bedMin = 30, hours = 7.5,
            deep = 100, light = 300, rem = 100,
            awake = 0, awakeCount = 0, efficiency = 100,
            hr = 45, hrv = 100, latency = 15, minSpo2 = 98,
            firstHalfHr = 45, secondHalfHr = 50,
        )
        val baseline = SleepScorer.Baseline(
            bedtimeMinutes = SleepScorer.bedtimeMinutesOf(best.startMillis),
            wakeMinutes = SleepScorer.wakeMinutesOf(best.endMillis),
            sleepHeartRate = 50,
            sleepHrv = 80,
        )
        val result = SleepScorer.scoreSleep(best, baseline)
        assertTrue("总分应 ≤100，实际 ${result.total}", result.total <= 100)
        assertTrue("理想数据应 ≥90，实际 ${result.total}", result.total >= 90)
    }
}
