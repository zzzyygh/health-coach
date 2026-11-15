package com.zzzyy.healthcoach.data.analysis

import android.util.Log
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.SleepSummary
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 睡眠评分（100 分制，分项计分）。
 *
 * ## 为什么用「分项计分」而不是「归一化加权」
 *
 * 归一化加权有个致命问题：四项满分时，一项 66 分会被平均成 93 分 —— 看不出问题。
 * 改成分项计分（深睡 22 分满分，只拿 8 分就是丢 14 分）后，**丢分多少一眼可见**，
 * 而且每项都是独立可解释的。
 *
 * ## 维度、满分与依据
 *
 * | 维度 | 满分 | 依据 |
 * |---|---|---|
 * | 深睡 | 22 | Ohayon 2004（成人 N3 比例随年龄下降）+ Oura 用分钟数判深睡（青年理想 90min、老年 45min）。这里百分比与分钟**取高分** |
 * | REM | 10 | AASM / Oura：成人 REM 占 20~25% |
 * | 夜间平均心率 | 12 | 与**个人 30 天基线**比（个体差异极大，不用固定值）；恢复质量的核心信号，权重上调 |
 * | 夜间 HRV | 7 | 与个人基线比；同上 |
 * | 前后半夜心率差 | 6 | 前半夜深睡占优 → 心率应 ≤ 后半夜。⚠️ 文献表明夜间心率不下降主要预测**心血管风险**、与主观睡眠质量无相关（Huang 2018；Leiva Sisnieguez 2025） |
 * | 睡眠效率 | 10 | AASM：≥85% 为正常 |
 * | 连续性 | 10 | AASM：WASO <30 分钟；觉醒次数少 |
 * | 时长 | 7 | **7~8 小时最优**（Cappuccio 2010：7~8h 为最低风险参照；9h RR 1.30 / 11h 1.41），双向扣分 |
 * | 节律 | 8 | 对比**用户手动设定的目标入睡/起床时间**，入睡与起床各 4 分 |
 * | 夜间最低血氧 | 5 | 用**近 7 天中位数**判（消费级设备误差 ±2~3%，单晚最低值不可靠）；<92% 持续出现才提示呼吸问题 |
 * | 入睡潜伏期 | 3 | Oura Latency：理想 10~20 分钟；<5 分钟可能是睡眠不足 |
 *
 * 合计 22+10+12+7+6+10+10+7+8+5+3 = 100。
 * **不再设「全局额外扣分」**：任一维度都要能落在自己的满分里，
 * 否则界面上的分项加起来对不上总分。
 *
 * ## 关于「睡太久」的重要说明
 *
 * 睡眠时长与全因死亡率是 **U 型**关系：Cappuccio 2010（138 万人、16 项前瞻研究）
 * 显示 9 小时以上死亡风险 RR 1.30，**比睡不足（≤6h，RR 1.12）还高**；
 * GeroScience 2025（79 项队列）复现：长睡 +34%、短睡 +14%。
 *
 * 但 García-Perdomo 2018（39 项研究）特别指出：长睡与死亡率的关联
 * **可能反映的是「潜在的全身性或神经系统疾病」**（这类疾病会造成睡眠碎片化、
 * 质量下降、微觉醒增多，人于是睡得更久却休息不好）——
 * **长睡是标志物，不一定是原因**。所以这个分数只用来提示「值得留意」，
 * 不能读成「少睡点更健康」。
 *
 * ## 边界
 *
 * 消费级设备的分期有误差（Oura 官方称与多导睡眠图四期一致率约 76%），
 * 所以这是**估计**，不是诊断。看趋势，不看单日。
 */
object SleepScorer {

    private const val TAG = "HealthCoach.SleepScore"

    /** 用户设定的作息目标。 */
    data class Goal(
        /** 目标入睡时刻，从 20:00 起算的分钟数 */
        val bedtimeMinutes: Int? = null,
        /** 目标起床时刻，从 20:00 起算的分钟数（可 > 600，即次日早晨） */
        val wakeMinutes: Int? = null,
    ) {
        val isSet: Boolean get() = bedtimeMinutes != null && wakeMinutes != null
    }

    /** 个人基线（由历史数据算出，只用目标日之前的记录）。 */
    data class Baseline(
        val bedtimeMinutes: Int? = null,
        val wakeMinutes: Int? = null,
        val sleepHeartRate: Int? = null,
        val sleepHrv: Int? = null,
        /** 近 7 天夜间最低血氧的中位数（用来抵消单晚 ±3% 的设备误差） */
        val recentMinSpo2: Int? = null,
        /** 近 7 天深睡占睡着时间百分比的中位数 */
        val recentDeepPercent: Double? = null,
        /** 近 7 天深睡分钟数的中位数 */
        val recentDeepMinutes: Int? = null,
        /** 近 7 日的评分均值（由上层算好传入，用于趋势修正） */
        val recentAverageScore: Int? = null,
        val sampleDays: Int = 0,
    )

    /** 一项维度的评分结果。 */
    data class Dimension(
        val key: String,
        val label: String,
        /** 实得分；null 表示数据缺失 */
        val score: Int?,
        /** 该项满分 */
        val maxScore: Int,
        /** 实际读数，如「8.7 小时」 */
        val reading: String,
        /** 判定依据或解释 */
        val basis: String,
    )

    data class Result(
        /** 各维度实得分之和（满分 100） */
        val subtotal: Int,
        /**
         * 趋势修正（-10 ~ +10）。
         *
         * 单晚数据本身是噪声很大的测量，所以向「近 7 日均值」收缩：
         *   - 近期都差、今天突然好 → 大概率只是波动，扣掉一部分虚高（负修正）；
         *   - 近期都好、今天偶尔差 → 大概率是偶然，补回一部分（正修正）。
         * 这样「一次好睡」不足以把分刷上去，「一次没睡好」也不会被重罚。
         */
        val trendAdjust: Int,
        /** 最终分 = subtotal + trendAdjust（限制在 0~100） */
        val total: Int,
        val grade: String,
        val dimensions: List<Dimension>,
        /** 因数据缺失而拿不到的分数合计（用于说明「本次满分只有 X」） */
        val missingPoints: Int,
        /** 近 7 日均分（用于解释修正值的来源） */
        val recentAverage: Int? = null,
        /** 短板封顶后的设备分（未封顶时等于 subtotal + trendAdjust） */
        val deviceScore: Int = total,
        /** 被封顶限制到的上限；null = 没触发封顶 */
        val cap: Int? = null,
        /** 封顶原因（界面上要写清楚） */
        val capReason: String? = null,
        /** 主观睡眠质量 1~5（用户打卡填的） */
        val subjectiveQuality: Int? = null,
        /** 主观分折算成 0~100 后的值 */
        val subjectiveScore: Int? = null,
        /** 主观感受带来的加减分（-8 ~ +8）；null = 没打卡，不做调整 */
        val subjectiveAdjust: Int? = null,
    )

    /**
     * 计算某一天的睡眠评分。
     *
     * @param day 目标日期
     * @param allDays 全部历史（用于算个人基线，只用目标日之前的记录）
     * @param goal 用户手动设定的作息目标
     */
    fun score(
        day: HealthDay,
        allDays: List<HealthDay>,
        goal: Goal = Goal(),
        subjectiveQuality: Int? = null,
    ): Result? {
        val sleep = day.sleep ?: return null
        val baseline = baselineOf(allDays, beforeDate = day.date)
        // 近 7 日均分（只用目标日之前的记录，且用「分项和」算，不含趋势修正，
        // 否则修正值会层层递归、越滚越大）
        val recentSubtotals = allDays
            .filter { it.date < day.date && it.sleep != null }
            .takeLast(7)
            .mapNotNull { past ->
                val pastSleep = past.sleep ?: return@mapNotNull null
                scoreSleep(
                    pastSleep,
                    baselineOf(allDays, beforeDate = past.date),
                    goal,
                ).subtotal
            }
        val recentAvg = if (recentSubtotals.size >= 3) recentSubtotals.average().roundToInt() else null
        val result = scoreSleep(
            sleep,
            baseline.copy(recentAverageScore = recentAvg),
            goal,
            subjectiveQuality = subjectiveQuality,
        )

        Log.i(
            TAG,
            "评分 ${day.date}：${result.total}（${result.grade}）" +
                "＝分项${result.subtotal}${if (result.trendAdjust >= 0) "+" else ""}${result.trendAdjust}" +
                "（近7日均${result.recentAverage ?: "-"}）" +
                (result.capReason?.let { "｜封顶：$it" } ?: "") +
                (result.subjectiveAdjust?.let {
                    "｜主观 ${result.subjectiveQuality}分→$it"
                } ?: "") + "｜" +
                result.dimensions.joinToString("；") { d ->
                    "${d.label} ${d.score ?: "-"}/${d.maxScore}（${d.reading}）"
                } +
                "｜基线 入睡${baseline.bedtimeMinutes?.let { fmt(it) } ?: "-"} " +
                "起床${baseline.wakeMinutes?.let { fmt(it) } ?: "-"} " +
                "心率${baseline.sleepHeartRate ?: "-"} HRV${baseline.sleepHrv ?: "-"}" +
                "｜目标 入睡${goal.bedtimeMinutes?.let { fmt(it) } ?: "-"} " +
                "起床${goal.wakeMinutes?.let { fmt(it) } ?: "-"}"
        )
        return result
    }

    /** 纯计算，不依赖历史（便于单元测试）。 */
    fun scoreSleep(
        sleep: SleepSummary,
        baseline: Baseline = Baseline(),
        goal: Goal = Goal(),
        subjectiveQuality: Int? = null,
    ): Result {
        val dimensions = listOf(
            deepSleepDimension(sleep, baseline),
            heartRateDimension(sleep, baseline),
            remDimension(sleep),
            efficiencyDimension(sleep),
            continuityDimension(sleep),
            rhythmDimension(sleep, baseline, goal),
            hrvDimension(sleep, baseline),
            durationDimension(sleep),
            heartRateDipDimension(sleep),
            spo2Dimension(sleep, baseline),
            latencyDimension(sleep),
        )

        val subtotal = dimensions.sumOf { it.score ?: 0 }
        val missing = dimensions.filter { it.score == null }.sumOf { it.maxScore }
        val recentAvg = baseline.recentAverageScore
        val adjust = trendAdjustment(subtotal, recentAvg)

        // ① 设备分：分项和 + 趋势修正
        var deviceScore = (subtotal + adjust).coerceIn(0, 100)

        // ② 短板封顶：深睡严重不足时不能进更高等级
        val judgedDeep = judgedDeepPercent(sleep, baseline)
        val cap = capForDeepSleep(judgedDeep)
        val capped = cap != null && deviceScore > cap
        if (capped) deviceScore = cap

        // ③ 主观调整：设备分是主分，自评只在两者明显不一致时做小幅加减
        val subjScore = subjectiveScore(subjectiveQuality)
        val subjAdjust = subjectiveAdjustment(deviceScore, subjectiveQuality)
        val total = (deviceScore + (subjAdjust ?: 0)).coerceIn(0, 100)

        return Result(
            subtotal = subtotal,
            trendAdjust = adjust,
            total = total,
            grade = gradeOf(total),
            dimensions = dimensions,
            missingPoints = missing,
            recentAverage = recentAvg,
            deviceScore = deviceScore,
            cap = if (capped) cap else null,
            capReason = if (capped) {
                "深睡 ${judgedDeep?.roundToInt() ?: "-"}%（<13%），总分上限 $cap"
            } else null,
            subjectiveQuality = subjectiveQuality,
            subjectiveScore = subjScore,
            subjectiveAdjust = subjAdjust,
        )
    }

    // ── 睡眠结构 ──────────────────────────────────────────────────

    /**
     * 深睡（22 分）。
     *
     * 百分比与绝对分钟**取高分**，因为两个标准各自都有成立的理由：
     *   - 只看百分比：睡 6 小时的人 16% 只有 58 分钟，对恢复并不够；
     *   - 只看分钟数：睡 10 小时的人 90 分钟只占 15%，会被误判为不足。
     */
    /**
     * 深睡的「判定值」：今天与近 7 天中位取较差。
     *
     * 连续多天深睡不足时，单晚「回到及格线」不该立刻拿高分 —— 债还没还完。
     * 这个值同时用于打分和短板封顶。
     */
    fun judgedDeepPercent(sleep: SleepSummary, baseline: Baseline): Double? {
        val asleep = sleep.asleepMinutes
        if (asleep <= 0) return null
        val todayPercent = sleep.deepMinutes.toDouble() / asleep * 100.0
        return minOf(todayPercent, baseline.recentDeepPercent ?: todayPercent)
    }

    private fun deepSleepDimension(sleep: SleepSummary, baseline: Baseline): Dimension {
        val asleep = sleep.asleepMinutes
        if (asleep <= 0) {
            return Dimension("deep", "深睡", null, 22, "无分期数据", "目标 ≥16% 或 ≥90 分钟")
        }
        val todayPercent = sleep.deepMinutes.toDouble() / asleep * 100.0
        val judgePercent = judgedDeepPercent(sleep, baseline) ?: todayPercent
        val judgeMinutes = minOf(sleep.deepMinutes, baseline.recentDeepMinutes ?: sleep.deepMinutes)

        val percentScore = when {
            judgePercent >= 16.0 -> 22.0
            judgePercent >= 13.0 -> lerp(judgePercent, 13.0, 16.0, 17.0, 22.0).toDouble()
            judgePercent >= 10.0 -> lerp(judgePercent, 10.0, 13.0, 9.0, 17.0).toDouble()
            else -> lerp(judgePercent, 5.0, 10.0, 0.0, 9.0).toDouble()
        }
        // 分钟数标准：Oura 的理想值是**青年 90 分钟**（老年 45 分钟），
        // 所以满分线定在 90，而不是之前那个过宽的 80。
        val minuteScore = when {
            judgeMinutes >= 90 -> 22.0
            judgeMinutes >= 70 -> lerp(judgeMinutes.toDouble(), 70.0, 90.0, 16.0, 22.0).toDouble()
            judgeMinutes >= 45 -> lerp(judgeMinutes.toDouble(), 45.0, 70.0, 8.0, 16.0).toDouble()
            judgeMinutes >= 20 -> lerp(judgeMinutes.toDouble(), 20.0, 45.0, 0.0, 8.0).toDouble()
            else -> 0.0
        }
        val score = maxOf(percentScore, minuteScore).roundToInt().coerceIn(0, 22)
        val usingTrend = baseline.recentDeepPercent != null &&
            (baseline.recentDeepPercent < todayPercent || (baseline.recentDeepMinutes ?: 0) < sleep.deepMinutes)
        val note = when {
            judgePercent >= 16.0 || judgeMinutes >= 70 -> "达标"
            judgePercent >= 13.0 -> "略低"
            else -> "偏低"
        }
        val reading = if (usingTrend) {
            "${sleep.deepMinutes} 分钟（${todayPercent.roundToInt()}%）· 近 7 天中位 ${judgePercent.roundToInt()}%"
        } else {
            "${sleep.deepMinutes} 分钟（${todayPercent.roundToInt()}%）"
        }
        return Dimension(
            key = "deep",
            label = "深睡",
            score = score,
            maxScore = 22,
            reading = reading,
            basis = "$note · 目标 ≥16% 或 ≥90 分钟（Oura 青年理想值），连续偏低会拖低评分",
        )
    }

    /** REM（10 分）：20~25% 满分，偏离每 2% 扣 1。 */
    private fun remDimension(sleep: SleepSummary): Dimension {
        val asleep = sleep.asleepMinutes
        if (asleep <= 0) {
            return Dimension("rem", "快速眼动 REM", null, 10, "无分期数据", "目标 20~25%")
        }
        val percent = sleep.remMinutes.toDouble() / asleep * 100.0
        val deviation = when {
            percent < 20.0 -> 20.0 - percent
            percent > 25.0 -> percent - 25.0
            else -> 0.0
        }
        val score = (10 - deviation / 2.0).coerceIn(0.0, 10.0).roundToInt()
        val note = when {
            deviation == 0.0 -> "在正常区间"
            percent < 20.0 -> "偏少"
            else -> "偏多"
        }
        return Dimension(
            key = "rem",
            label = "快速眼动 REM",
            score = score,
            maxScore = 10,
            reading = "${sleep.remMinutes} 分钟（${percent.roundToInt()}%）",
            basis = "$note · 目标 20~25%（AASM）",
        )
    }

    /** 入睡潜伏期（5 分）：10~20 分钟理想。 */
    private fun latencyDimension(sleep: SleepSummary): Dimension {
        val latency = sleep.latencyMinutes
        if (latency == null) {
            return Dimension("latency", "入睡潜伏期", null, 3, "设备未记录", "理想 10~20 分钟")
        }
        val score = when {
            latency in 10..20 -> 3
            latency in 5..10 -> 2
            latency < 5 -> 2          // 秒睡往往说明睡眠不足或过度疲劳，不是「睡得好」
            latency in 20..40 -> lerp(latency.toDouble(), 20.0, 40.0, 3.0, 1.0)
            latency in 40..60 -> 1
            else -> 0
        }
        val note = when {
            latency in 10..20 -> "正常"
            latency < 5 -> "秒睡，可能睡眠不足"
            latency <= 40 -> "略长"
            else -> "偏长"
        }
        return Dimension(
            key = "latency",
            label = "入睡潜伏期",
            score = score,
            maxScore = 3,
            reading = "$latency 分钟",
            basis = "$note · 理想 10~20 分钟（Oura Latency）",
        )
    }

    // ── 自主神经与心率 ────────────────────────────────────────────

    /** 夜间平均心率（10 分）：与个人基线比，高 1bpm 扣 2 分。 */
    private fun heartRateDimension(sleep: SleepSummary, baseline: Baseline): Dimension {
        val hr = sleep.averageHeartRate
        val base = baseline.sleepHeartRate
        if (hr == null) {
            return Dimension("hr", "夜间平均心率", null, 12, "无数据", "对比个人基线")
        }
        if (base == null) {
            return Dimension("hr", "夜间平均心率", null, 12, "$hr bpm", "历史不足，暂不评分")
        }
        val delta = hr - base
        // 12 分制：高于基线每 1bpm 扣 2.4 分（约等于原 10 分制的 2 分/bpm）
        val score = (12 - maxOf(0, delta) * 2.4).coerceIn(0.0, 12.0).roundToInt()
        return Dimension(
            key = "hr",
            label = "夜间平均心率",
            score = score,
            maxScore = 12,
            reading = "$hr bpm（基线 $base）",
            basis = if (delta <= 0) "低于/等于基线" else "高于基线 $delta bpm",
        )
    }

    /** 夜间 HRV（5 分）：与个人基线比。 */
    private fun hrvDimension(sleep: SleepSummary, baseline: Baseline): Dimension {
        val hrv = sleep.averageHrv
        val base = baseline.sleepHrv
        if (hrv == null) {
            return Dimension("hrv", "夜间 HRV", null, 7, "无数据", "对比个人基线")
        }
        if (base == null || base <= 0) {
            return Dimension("hrv", "夜间 HRV", null, 7, "$hrv", "历史不足，暂不评分")
        }
        val ratio = hrv.toDouble() / base
        val score = when {
            ratio >= 1.0 -> 7
            ratio >= 0.9 -> 6
            ratio >= 0.8 -> 4
            ratio >= 0.7 -> 3
            else -> 1
        }
        return Dimension(
            key = "hrv",
            label = "夜间 HRV",
            score = score,
            maxScore = 7,
            reading = "$hrv（基线 $base）",
            basis = if (ratio >= 1.0) "高于/等于基线"
            else "比基线低 ${((1 - ratio) * 100).roundToInt()}%",
        )
    }

    /**
     * 前后半夜心率差（5 分）。
     *
     * ⚠️ 这**不是**睡眠质量指标：文献显示夜间心率不下降主要与心血管风险相关，
     * 与主观睡眠质量无相关。给 5 分是当「自主神经恢复节律」看，不宜再高。
     */
    private fun heartRateDipDimension(sleep: SleepSummary): Dimension {
        val first = sleep.firstHalfHeartRate
        val second = sleep.secondHalfHeartRate
        if (first == null || second == null) {
            return Dimension("hr_dip", "前后半夜心率", null, 6, "无数据", "前半夜应 ≤ 后半夜")
        }
        val delta = first - second
        val score = if (delta <= 0) 6 else (6 - delta * 1.8).coerceIn(0.0, 6.0).roundToInt()
        return Dimension(
            key = "hr_dip",
            label = "前后半夜心率",
            score = score,
            maxScore = 6,
            reading = "$first → $second bpm",
            basis = if (delta <= 0) "符合正常节律" else "前半夜高 $delta bpm（恢复节律偏弱）",
        )
    }

    // ── 连续性与效率 ──────────────────────────────────────────────

    /** 睡眠效率（10 分）：≥85% 满分，每低 2% 扣 1。 */
    private fun efficiencyDimension(sleep: SleepSummary): Dimension {
        val efficiency = sleep.efficiency
            ?: if (sleep.hours > 0) (sleep.asleepMinutes / (sleep.hours * 60) * 100).roundToInt() else null
        if (efficiency == null) {
            return Dimension("efficiency", "睡眠效率", null, 10, "无数据", "目标 ≥85%")
        }
        val score = if (efficiency >= 85) 10
        else (10 - (85 - efficiency) / 2.0).coerceIn(0.0, 10.0).roundToInt()
        return Dimension(
            key = "efficiency",
            label = "睡眠效率",
            score = score,
            maxScore = 10,
            reading = "$efficiency%",
            basis = if (efficiency >= 85) "达标" else "低于 85%（AASM）",
        )
    }

    /** 连续性（10 分）：WASO 6 分 + 觉醒次数 4 分。 */
    private fun continuityDimension(sleep: SleepSummary): Dimension {
        val waso = sleep.awakeMinutes
        val count = sleep.awakeCount
        val wasoScore = when {
            waso <= 30 -> 6.0
            waso <= 60 -> lerp(waso.toDouble(), 30.0, 60.0, 6.0, 4.0).toDouble()
            else -> lerp(waso.toDouble(), 60.0, 120.0, 4.0, 1.0).toDouble()
        }
        val countScore = when {
            count <= 1 -> 4.0
            count == 2 -> 3.0
            count == 3 -> 2.0
            else -> 1.0
        }
        val score = (wasoScore + countScore).roundToInt().coerceIn(0, 10)
        return Dimension(
            key = "continuity",
            label = "连续性",
            score = score,
            maxScore = 10,
            reading = "醒 $count 次 / $waso 分钟",
            basis = if (waso <= 30) "入睡后清醒达标（<30 分钟）"
            else "入睡后清醒偏长（AASM <30 分钟）",
        )
    }

    // ── 时长与节律 ────────────────────────────────────────────────

    /**
     * 时长（10 分）：**7~8 小时最优**，双向扣分。
     *
     * 为什么最优区间是 7~8 而不是 7~9：
     * Cappuccio 2010（138 万人）里 7~8 小时是风险最低的参照组，
     * 9 小时组 RR 已经到 1.30；GeroScience 2025（79 项队列）同样以 7~8 小时为参照。
     *
     * 长睡一侧扣得比短睡略重 —— 但要注意这是**关联不是因果**：
     * 长睡更可能是潜在疾病（睡眠碎片化、微觉醒）的**标志物**，
     * 所以界面上的说法是「值得留意」，不是「你应该少睡」。
     */
    private fun durationDimension(sleep: SleepSummary): Dimension {
        val hours = sleep.hours
        val score = when {
            hours in 7.0..8.0 -> 7
            // 短睡一侧：6h→3.5 分，5h→0 分
            hours >= 6.0 && hours < 7.0 -> lerp(hours, 6.0, 7.0, 3.5, 7.0)
            hours >= 5.0 && hours < 6.0 -> lerp(hours, 5.0, 6.0, 0.0, 3.5)
            hours < 5.0 -> 0
            // 长睡一侧：与短睡对称（9h→3.5 分，10h→0 分），
            // 因为 9h 的死亡率风险（RR 1.30）其实比 6h（RR 1.12）更高，不该比短睡宽容
            hours > 8.0 && hours <= 9.0 -> lerp(hours, 8.0, 9.0, 7.0, 3.5)
            hours > 9.0 && hours <= 10.0 -> lerp(hours, 9.0, 10.0, 3.5, 0.0)
            else -> 0
        }
        val note = when {
            hours in 7.0..8.0 -> "在最优区间"
            hours < 7.0 -> "偏短"
            hours <= 9.0 -> "略长"
            else -> "偏长"
        }
        return Dimension(
            key = "duration",
            label = "睡眠时长",
            score = score,
            maxScore = 7,
            reading = "%.1f 小时".format(hours),
            basis = "$note · 最优 7~8 小时（Cappuccio 2010：9h 以上风险已升高）",
        )
    }

    /**
     * 节律（10 分）：对比**用户手动设定的目标时间**。
     *
     * 有目标就用目标；没设就退回个人历史习惯（这时依据文案会说明是「与你的常态比」）。
     */
    private fun rhythmDimension(sleep: SleepSummary, baseline: Baseline, goal: Goal): Dimension {
        val bedtimeTarget = goal.bedtimeMinutes ?: baseline.bedtimeMinutes
        val wakeTarget = goal.wakeMinutes ?: baseline.wakeMinutes
        val actualBed = bedtimeMinutesOf(sleep.startMillis)
        val actualWake = wakeMinutesOf(sleep.endMillis)
        if (bedtimeTarget == null || wakeTarget == null || actualBed == null || actualWake == null) {
            return Dimension("rhythm", "作息节律", null, 8, "缺目标或数据", "对比你设定的目标时间")
        }
        val bedDelta = abs(circularDelta(actualBed, bedtimeTarget))
        val wakeDelta = abs(circularDelta(actualWake, wakeTarget))
        // 8 分制：入睡 4 分 + 起床 4 分（两边都考核，熬夜和晚起是两件事）
        fun points(delta: Int): Double = when {
            delta <= 30 -> 4.0
            delta <= 60 -> lerp(delta.toDouble(), 30.0, 60.0, 4.0, 2.4).toDouble()
            delta <= 120 -> lerp(delta.toDouble(), 60.0, 120.0, 2.4, 0.8).toDouble()
            else -> 0.0
        }
        val score = (points(bedDelta) + points(wakeDelta)).roundToInt().coerceIn(0, 8)
        return Dimension(
            key = "rhythm",
            label = "作息节律",
            score = score,
            maxScore = 8,
            reading = "入睡差 ${bedDelta} 分 / 起床差 ${wakeDelta} 分",
            basis = if (goal.isSet) {
                "对比目标 ${fmt(bedtimeTarget)} / ${fmt(wakeTarget)}"
            } else {
                "未设目标，对比你的常态 ${fmt(bedtimeTarget)} / ${fmt(wakeTarget)}"
            },
        )
    }

    /**
     * 夜间最低血氧（5 分）。
     *
     * ⚠️ 用**近 7 天中位数**判，不用单晚最低值。理由：
     *   - 健康成人夜间血氧本来就会短暂掉到 92~94%（深睡呼吸变浅）；
     *   - 消费级穿戴的血氧误差是 **±2~3 个百分点**，单晚 93% 落在误差范围内，
     *     拿它判「0 分 + 额外扣分」等于在给测量噪声打分；
     *   - 所有资料都强调「看趋势不看单次读数」。
     * 连续一周中位数都 ≤93% 才是值得提示呼吸问题的信号。
     */
    private fun spo2Dimension(sleep: SleepSummary, baseline: Baseline): Dimension {
        val tonight = sleep.minSpo2
        if (tonight == null) {
            return Dimension("spo2", "夜间最低血氧", null, 5, "无数据", "目标 ≥95%")
        }
        val judging = baseline.recentMinSpo2 ?: tonight
        val usingTrend = baseline.recentMinSpo2 != null
        // 95% 满分，每低 1 个百分点扣 1.25 分（91% 归零）
        val score = ((judging - 91) / 4.0 * 5.0).coerceIn(0.0, 5.0).roundToInt()
        val reading = if (usingTrend) "$tonight%（近 7 天中位 $judging%）" else "$tonight%"
        return Dimension(
            key = "spo2",
            label = "夜间最低血氧",
            score = score,
            maxScore = 5,
            reading = reading,
            basis = when {
                judging >= 95 -> "正常"
                judging >= 93 -> "偏低但在设备误差范围内，看趋势"
                else -> "持续偏低，建议关注呼吸"
            },
        )
    }

    // ── 基线 ──────────────────────────────────────────────────────

    /**
     * 个人基线：只用目标日**之前**最多 30 天的记录。
     *
     * 用「之前」而不是「全部」，避免拿未来的数据解释过去。
     */
    fun baselineOf(allDays: List<HealthDay>, beforeDate: String): Baseline {
        val history = allDays.filter { it.date < beforeDate && it.sleep != null }.takeLast(30)
        if (history.isEmpty()) return Baseline()
        val bedtimes = history.mapNotNull { bedtimeMinutesOf(it.sleep!!.startMillis) }
        val wakes = history.mapNotNull { wakeMinutesOf(it.sleep!!.endMillis) }
        val hrs = history.mapNotNull { it.sleep?.averageHeartRate }
        val hrvs = history.mapNotNull { it.sleep?.averageHrv }
        val recentSpo2 = history.takeLast(7).mapNotNull { it.sleep?.minSpo2 }
        val recent7 = history.takeLast(7).mapNotNull { it.sleep }
        val recentDeepPercents = recent7.mapNotNull { s ->
            val asleep = s.asleepMinutes
            if (asleep > 0) s.deepMinutes.toDouble() / asleep * 100.0 else null
        }
        val recentDeepMinutes = recent7.map { it.deepMinutes }
        return Baseline(
            bedtimeMinutes = if (bedtimes.size >= 3) medianOf(bedtimes) else null,
            wakeMinutes = if (wakes.size >= 3) medianOf(wakes) else null,
            sleepHeartRate = if (hrs.size >= 3) medianOf(hrs) else null,
            sleepHrv = if (hrvs.size >= 3) medianOf(hrvs) else null,
            recentMinSpo2 = if (recentSpo2.size >= 3) medianOf(recentSpo2) else null,
            recentDeepPercent = if (recentDeepPercents.size >= 3) {
                recentDeepPercents.sorted().let { v ->
                    val mid = v.size / 2
                    if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2.0
                }
            } else null,
            recentDeepMinutes = if (recentDeepMinutes.size >= 3) medianOf(recentDeepMinutes) else null,
            sampleDays = history.size,
        )
    }

    /** 只算入睡基线（兼容旧调用）。 */
    fun bedtimeBaselineMinutes(allDays: List<HealthDay>, beforeDate: String): Int? =
        baselineOf(allDays, beforeDate).bedtimeMinutes

    // ── 时间工具 ──────────────────────────────────────────────────

    /**
     * 入睡/起床时刻统一换算成「从 20:00 起算的分钟数」。
     *
     * 凌晨 01:30 → 330、早上 07:00 → 660，这样跨零点的比较才有意义
     * （否则 23:50 和 00:10 会被算成相差 23 小时）。
     */
    fun bedtimeMinutesOf(millis: Long): Int? = clockMinutesOf(millis)

    fun wakeMinutesOf(millis: Long): Int? = clockMinutesOf(millis)

    private fun clockMinutesOf(millis: Long): Int? {
        if (millis <= 0) return null
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        var total = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(java.util.Calendar.MINUTE)
        if (total < 20 * 60) total += 24 * 60
        return total - 20 * 60
    }

    private fun circularDelta(a: Int, b: Int): Int {
        val day = 24 * 60
        var diff = a - b
        if (diff > day / 2) diff -= day
        if (diff < -day / 2) diff += day
        return diff
    }

    /** 分钟数（从 20:00 起算）→ HH:mm。 */
    fun fmt(minutesFrom20: Int): String {
        val total = ((20 * 60 + minutesFrom20) % (24 * 60) + 24 * 60) % (24 * 60)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun medianOf(values: List<Int>): Int {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun lerp(x: Double, x0: Double, x1: Double, y0: Double, y1: Double): Int {
        if (x1 == x0) return y1.roundToInt()
        val t = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
        return (y0 + (y1 - y0) * t).roundToInt().coerceIn(0, 100)
    }

    /** 被认为「正常波动」的区间：日与日之间差 8 分以内不调整。 */
    private const val ANOMALY_THRESHOLD = 8

    /** 超出阈值后，每高 1 分扣多少。 */
    private const val PENALTY_RATE = 0.7

    /** 超出阈值后，每低 1 分补多少（奖励比惩罚保守一倍）。 */
    private const val REWARD_RATE = 0.4

    /**
     * 趋势修正（-10 ~ +6）。
     *
     * ⚠️ 只对**异常**做调整，不是把所有分数往均值拉。
     *
     * 早期版本用的是连续收缩（`-0.5 × 差值`），结果是每天都被拉向近 7 日均：
     * 高的一天被压低、低的一天被抬起来，整体分数被压平，
     * 连「深睡只有 6%」那种明显很差的一晚都被抬到 79 分 —— 这是错的。
     *
     * 现在的规则：
     *   - 与近 7 日均分相差 **8 分以内视为正常波动，不调整**；
     *   - 高出 8 分以上（今天异常地好）→ 按超出部分扣分，最多 -10，
     *     因为近期都差时「突然睡好一晚」多半只是波动，身体也没来得及恢复；
     *   - 低于 8 分以上（今天异常地差）→ 按超出部分补回，最多 +6，
     *     奖励只给惩罚的 0.4 倍：睡得差是真差，不该被均值掩盖太多。
     *
     * 举例：近 7 日均 78，今天 95 → 超出 17，扣 (17-8)×0.7 ≈ 6 → 89。
     *       近 7 日均 86，今天 72 → 低于 14，补 (14-8)×0.4 ≈ 2 → 74。
     *       近 7 日均 81，今天 84 → 差 3，落在正常波动内 → 84（不动）。
     */
    fun trendAdjustment(today: Int, recentAverage: Int?): Int {
        if (recentAverage == null) return 0
        val diff = today - recentAverage
        return when {
            diff > ANOMALY_THRESHOLD ->
                -(((diff - ANOMALY_THRESHOLD) * PENALTY_RATE).roundToInt()).coerceIn(0, 10)
            diff < -ANOMALY_THRESHOLD ->
                (((-diff - ANOMALY_THRESHOLD) * REWARD_RATE).roundToInt()).coerceIn(0, 6)
            else -> 0
        }
    }

    /**
     * 短板封顶。
     *
     * 深睡（慢波睡眠）负责生长激素分泌、记忆巩固、脑内代谢废物清除，
     * 它严重不足时，其他维度再好也不能算「优秀」——
     * 这正是之前「深睡 10% 却得 93 分」的根源：十项优秀把一项短板稀释掉了。
     *
     * 依据 Ohayon 2004：成人 SWS 应占 12~15%（中年），低于 10% 属明显不足。
     *
     * @return 上限分数；null 表示不封顶
     */
    fun capForDeepSleep(judgedPercent: Double?): Int? = when {
        judgedPercent == null -> null
        judgedPercent < 10.0 -> 75
        judgedPercent < 13.0 -> 82
        else -> null
    }

    /**
     * 主观睡眠质量 1~5 折算成 0~100。
     *
     * 用 15/35/55/75/95 而不是 0/25/50/75/100：
     * 即使打 1 分也保留一点底分（可能只是当晚特别累），
     * 打 5 分也不给满分（"感觉很好"不等于客观满分）。
     */
    fun subjectiveScore(quality: Int?): Int? =
        quality?.takeIf { it in 1..5 }?.let { (it - 1) * 20 + 15 }

    /**
     * 主观感受是**附加调整**，不是把总分按比例混起来。
     *
     * 早期版本用「设备 80% + 主观 20%」的加权平均，问题有两个：
     *   1. 主观分变成了「半个主人」，自评 5 分能把设备 82 推成 85（其实数据没那么好）；
     *   2. 没打卡的日子不动、打卡的日子被拉低，看起来像「打卡反而扣分」。
     *
     * 现在改成：拿**你的自评与设备分的差值**做小幅加减，最多 ±5。
     * 设备分始终是主分，主观只负责在两者明显不一致时微调。
     *
     * 幅度刻意压得很小（≈ 总分 5%）：单晚的主观打分本身也不可靠
     * （可能只是今天特别累），不该因为「打了卡」就把分数明显拉低 ——
     * 那会让打卡变成惩罚，人就不愿意填了。
     * 两者差距很大时，更有价值的是**提示矛盾**，而不是硬扣分。
     */
    private const val SUBJECTIVE_RATE = 0.1
    private const val SUBJECTIVE_MAX = 5

    /** 自评与设备分差距超过这个值，就认为两者矛盾，值得提示。 */
    const val SUBJECTIVE_CONFLICT_GAP = 25

    /** 自评与设备分是否明显矛盾（用于界面提示）。 */
    fun hasSubjectiveConflict(deviceScore: Int, quality: Int?): Boolean {
        val subj = subjectiveScore(quality) ?: return false
        return kotlin.math.abs(subj - deviceScore) > SUBJECTIVE_CONFLICT_GAP
    }

    /** 主观调整量：自评明显低于设备分则减，明显高于则加，最多 ±5。 */
    fun subjectiveAdjustment(deviceScore: Int, quality: Int?): Int? {
        val subj = subjectiveScore(quality) ?: return null
        return ((subj - deviceScore) * SUBJECTIVE_RATE)
            .roundToInt()
            .coerceIn(-SUBJECTIVE_MAX, SUBJECTIVE_MAX)
    }

    /** 等级门槛：参照 Oura 官方（85 优秀 / 70 良好 / 60 一般）。 */
    fun gradeOf(total: Int): String = when {
        total >= 85 -> "优秀"
        total >= 70 -> "良好"
        total >= 60 -> "一般"
        else -> "偏差"
    }
}
