package com.zzzyy.healthcoach.data.advice

import com.zzzyy.healthcoach.data.analysis.NOISE_THRESHOLD
import com.zzzyy.healthcoach.data.analysis.nightDeltas
import com.zzzyy.healthcoach.data.model.FeedbackEntry
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.InterventionEntry
import java.text.SimpleDateFormat
import kotlin.math.roundToInt
import java.util.Date
import java.util.Locale

/**
 * 把健康数据和反馈拼成给模型看的提示词。
 *
 * ⚠️ 只输出**日聚合**字段，不含逐条心率/睡眠分段等明细 —— 明细留在手机本地。
 *
 * 表格列的设计原则：**给的是判断恢复质量必需的量**。
 * 所以睡眠不只有「睡了几小时」，还有深睡/REM/清醒/醒来次数/效率；
 * 心率不只有平均，还有静息（恢复的基线）和夜间 HRV（自主神经状态）。
 * 少了 REM 和 HRV，模型只能靠时长猜，判断质量会明显打折。
 */
object AdvicePrompt {

    fun build(
        days: List<HealthDay>,
        feedback: List<FeedbackEntry>,
        interventions: List<InterventionEntry> = emptyList(),
    ): String {
        val builder = StringBuilder()

        builder.appendLine("## 客观数据（最近 ${days.size} 天，按日期升序）")
        builder.appendLine()
        builder.appendLine("### 活动与心血管")
        builder.appendLine()
        builder.appendLine(
            "日期 | 步数 | 距离(米) | 活动卡路里 | 站立(小时) | 平均心率 | 静息心率 | " +
                "最低血氧 | 压力均值 | 压力峰值"
        )
        days.forEach { day ->
            builder.appendLine(
                listOf(
                    day.date,
                    day.steps.toString(),
                    day.distanceMeters.toString(),
                    day.activeCalories.toString(),
                    day.standHours.toString(),
                    day.heartRate?.average?.toString() ?: "-",
                    day.restingHeartRate?.toString() ?: "-",
                    day.spo2?.min?.toString() ?: "-",
                    day.stress?.average?.toString() ?: "-",
                    day.stress?.max?.toString() ?: "-",
                ).joinToString(" | ")
            )
        }

        builder.appendLine()
        builder.appendLine("### 睡眠")
        builder.appendLine()
        builder.appendLine(
            "日期 | 卧床(小时) | 深睡(分) | 浅睡(分) | REM(分) | 清醒(分) | 醒来次数 | " +
                "睡眠效率(%) | 夜间平均心率 | 夜间HRV"
        )
        days.forEach { day ->
            val sleep = day.sleep
            builder.appendLine(
                listOf(
                    day.date,
                    sleep?.hours?.toString() ?: "-",
                    sleep?.deepMinutes?.toString() ?: "-",
                    sleep?.lightMinutes?.toString() ?: "-",
                    sleep?.remMinutes?.toString() ?: "-",
                    sleep?.awakeMinutes?.toString() ?: "-",
                    sleep?.awakeCount?.toString() ?: "-",
                    sleep?.efficiency?.toString() ?: "-",
                    sleep?.averageHeartRate?.toString() ?: "-",
                    sleep?.averageHrv?.toString() ?: "-",
                ).joinToString(" | ")
            )
        }

        val relevantFeedback = feedback
            .filter { entry -> days.any { it.date == entry.date } }
            .sortedBy { it.timestamp }
        if (relevantFeedback.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("## 用户主观反馈（同一天可能有多条，已合并为一行）")
            builder.appendLine()
            builder.appendLine("日期 | 睡眠质量 | 精力 | 心情 | 酸痛 | 症状 | 备注（按时间合并）")
            // 一天可能记了多条：评分取最新非空，症状取并集，备注按时间拼接
            relevantFeedback.groupBy { it.date }.toSortedMap().forEach { (date, entries) ->
                fun latestOf(getter: (com.zzzyy.healthcoach.data.model.FeedbackEntry) -> Int?): String =
                    entries.lastOrNull { getter(it) != null }?.let { getter(it)?.toString() } ?: "-"
                val symptoms = entries.flatMap { it.symptoms }.distinct()
                val notes = entries.mapNotNull { e ->
                    e.note.replace("\n", " ").takeIf { it.isNotBlank() }
                }
                builder.appendLine(
                    listOf(
                        date,
                        latestOf { it.sleepQuality },
                        latestOf { it.energy },
                        latestOf { it.mood },
                        latestOf { it.soreness },
                        symptoms.joinToString("、").ifEmpty { "-" },
                        notes.joinToString("；").ifEmpty { "-" },
                    ).joinToString(" | ")
                )
            }
        }

        val relevantInterventions = interventions.filter { entry ->
            days.any { it.date == entry.date }
        }
        if (relevantInterventions.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("## 用户主动干预记录（他做了什么 / 吃了什么）")
            builder.appendLine()
            builder.appendLine("日期 | 干预 | 剂量 | 备注")
            relevantInterventions.sortedBy { it.date }.forEach { entry ->
                builder.appendLine(
                    listOf(
                        entry.date,
                        entry.name,
                        entry.dose?.let { d ->
                            (if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()) +
                                " " + entry.unit
                        } ?: entry.unit.ifBlank { "-" },
                        entry.note.replace("\n", " ").ifEmpty { "-" },
                    ).joinToString(" | ")
                )
            }
            builder.appendLine()
            builder.appendLine(
                "注意：这些是用户自己记录的主动行为。分析时请把它们当作**可能的解释变量**，" +
                    "但必须说明观察窗口 —— 补充剂（如维生素 D、Omega-3）的血药浓度变化以周计，" +
                    "记录不足 4 周时不要下「有效/无效」的结论，只能说「继续观察」。"
            )
        }

        builder.appendLine()
        builder.appendLine("## 读数说明")
        builder.appendLine()
        builder.appendLine("- 量表：睡眠质量/精力/心情/酸痛均为 1~5 分，1 最差 5 最好；酸痛是 1 无酸痛 5 很酸痛。")
        builder.appendLine("- 「-」= 该指标当天没有记录（设备未佩戴或未采到），不是 0。")
        builder.appendLine("- 压力值是设备根据 HRV 推算的**相对强度**（1~100，越高越紧张），只能同一个人纵向比。")
        builder.appendLine("- 睡眠效率 = 睡着时间 / 卧床时间。REM（快速眼动）与记忆巩固、情绪调节相关，通常占成人睡眠 20~25%。")
        builder.appendLine("- 夜间 HRV 越高通常表示恢复越好，但个体差异大，看趋势不看绝对值。")
        builder.appendLine()
        builder.appendLine("请给出这 ${days.size} 天的整体健康判断和可执行建议。")

        return builder.toString()
    }

    /**
     * 聚焦诊断：以昨晚为主，**带上前两晚作为参照**。
     *
     * 只看一天的问题是没法判断「这晚算好还是坏」——
     * 没有参照系，8 小时深睡 15% 到底是常态还是偶发？说不清。
     * 所以固定带上前 2 天：既能看出是不是突然变差，又不会像 7 天那样把重点冲淡。
     *
     * @param day 目标日（昨晚）
     * @param previousDays 前几晚（按时间升序，最多取最后 2 天）
     */
    fun buildFocused(
        day: HealthDay,
        previousDays: List<HealthDay>,
        feedback: List<FeedbackEntry>,
        interventions: List<InterventionEntry> = emptyList(),
    ): String {
        val builder = StringBuilder()
        val sleep = day.sleep

        builder.appendLine("## 昨晚（${day.date}）")
        builder.appendLine()
        if (sleep != null) {
            val asleep = sleep.asleepMinutes
            fun pct(v: Int) = if (asleep > 0) "（${(v.toDouble() / asleep * 100).roundToInt()}%）" else ""
            val bed = clockOf(sleep.startMillis)
            val wake = clockOf(sleep.endMillis)
            builder.appendLine("- 卧床 ${sleep.hours} 小时，入睡 $bed，起床 $wake")
            sleep.latencyMinutes?.let { builder.appendLine("- 入睡潜伏期 $it 分钟") }
            builder.appendLine("- 深睡 ${sleep.deepMinutes} 分钟${pct(sleep.deepMinutes)}")
            builder.appendLine("- 浅睡 ${sleep.lightMinutes} 分钟${pct(sleep.lightMinutes)}")
            builder.appendLine("- REM ${sleep.remMinutes} 分钟${pct(sleep.remMinutes)}")
            builder.appendLine("- 夜间清醒 ${sleep.awakeMinutes} 分钟，醒来 ${sleep.awakeCount} 次")
            sleep.efficiency?.let { builder.appendLine("- 睡眠效率 $it%") }
            val hrParts = buildList {
                sleep.averageHeartRate?.let { add("平均 $it bpm") }
                sleep.averageHrv?.let { add("HRV $it") }
                sleep.minSpo2?.let { add("最低血氧 $it%") }
            }
            if (hrParts.isNotEmpty()) builder.appendLine("- 夜间：${hrParts.joinToString("，")}")
            if (sleep.firstHalfHeartRate != null && sleep.secondHalfHeartRate != null) {
                builder.appendLine(
                    "- 前半夜 → 后半夜心率：${sleep.firstHalfHeartRate} → ${sleep.secondHalfHeartRate} bpm"
                )
            }
        } else {
            builder.appendLine("- 这一晚没有睡眠记录（设备未佩戴或未同步）")
        }

        if (previousDays.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("## 前 ${previousDays.size} 晚（作为参照，判断昨晚是偶发还是常态）")
            builder.appendLine()
            builder.appendLine("日期 | 卧床(h) | 深睡 | REM | 清醒(分/次) | 效率 | 夜间心率 | HRV | 最低血氧")
            previousDays.forEach { prev ->
                val ps = prev.sleep
                if (ps != null) {
                    val asleep = ps.asleepMinutes
                    fun p(v: Int) = if (asleep > 0) "${v}(${(v.toDouble() / asleep * 100).roundToInt()}%)" else "$v"
                    builder.appendLine(
                        listOf(
                            prev.date,
                            ps.hours.toString(),
                            p(ps.deepMinutes),
                            p(ps.remMinutes),
                            "${ps.awakeMinutes}/${ps.awakeCount}",
                            ps.efficiency?.toString() ?: "-",
                            ps.averageHeartRate?.toString() ?: "-",
                            ps.averageHrv?.toString() ?: "-",
                            ps.minSpo2?.toString() ?: "-",
                        ).joinToString(" | ")
                    )
                } else {
                    builder.appendLine("${prev.date} | 无睡眠记录")
                }
            }
        }

        builder.appendLine()
        builder.appendLine("## 前一天白天")
        builder.appendLine()
        builder.appendLine(
            "- 步数 ${day.steps}，距离 ${day.distanceMeters} 米，活动消耗 ${day.activeCalories} kcal"
        )
        builder.appendLine("- 有效站立 ${day.standHours} 小时")
        day.heartRate?.let { builder.appendLine("- 白天心率 平均 ${it.average}（${it.min}~${it.max}）") }
        day.stress?.let { builder.appendLine("- 压力 平均 ${it.average}（峰值 ${it.max}）") }

        val dayFeedback = feedback.filter { it.date == day.date }
        if (dayFeedback.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("## 今早的主观感受")
            builder.appendLine()
            dayFeedback.sortedBy { it.timestamp }.forEach { entry ->
                val parts = buildList {
                    entry.sleepQuality?.let { add("睡眠质量 $it/5") }
                    entry.energy?.let { add("精力 $it/5") }
                    entry.mood?.let { add("心情 $it/5") }
                    entry.soreness?.let { add("酸痛 $it/5") }
                    if (entry.symptoms.isNotEmpty()) add("症状：${entry.symptoms.joinToString("、")}")
                    if (entry.note.isNotBlank()) add("备注：${entry.note}")
                }
                if (parts.isNotEmpty()) builder.appendLine("- ${parts.joinToString("，")}")
            }
        }

        val dayInterventions = interventions.filter { it.date == day.date }
        if (dayInterventions.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("## 前一天记录的干预")
            builder.appendLine()
            dayInterventions.forEach {
                builder.appendLine("- ${it.display}${if (it.note.isNotBlank()) "（${it.note}）" else ""}")
            }
        }

        builder.appendLine()
        builder.appendLine("## 要求")
        builder.appendLine()
        builder.appendLine("以**昨晚**为主要分析对象，但必须用前几晚作对照：")
        builder.appendLine("1. 昨晚的短板是哪个（深睡/REM/连续性/时长/节律/心率恢复），用数字说明；")
        builder.appendLine("2. **和前一晚对比**：是突然变差还是连续几晚都这样？这决定要不要当作趋势来处理；")
        builder.appendLine("3. 结合主观感受、前一天的活动和干预，推测最可能的原因；")
        builder.appendLine("4. 给出**今晚就能调整**的 2~3 条具体做法。")
        return builder.toString()
    }

    /**
     * 两晚对比：**昨晚 vs 前晚**，逐项差值 + 是否超出个人日常波动。
     *
     * 与其他模式的区别：这里不追求「全面」，只回答一个问题 ——
     * 这两晚的差别，是真实的改变，还是本来就这么跳？
     *
     * 差值和个人标准差都由代码算好（[nightDeltas]，有单测），模型只负责解释
     * 那些确实超出波动的项；没超出的必须明说「属日常波动」，不允许硬编原因。
     *
     * @param history 近 30 天（用于估计个人波动）
     */
    fun buildNightCompare(
        last: HealthDay,
        prev: HealthDay,
        history: List<HealthDay>,
        feedback: List<FeedbackEntry>,
        interventions: List<InterventionEntry> = emptyList(),
    ): String {
        val builder = StringBuilder()
        val lastSleep = last.sleep
        val prevSleep = prev.sleep

        builder.appendLine("## 两晚的原始数据")
        builder.appendLine()
        builder.appendLine("指标 | ${prev.date}（前晚） | ${last.date}（昨晚）")
        builder.appendLine("---|---|---")
        builder.appendLine(
            "入睡 | ${prevSleep?.let { clockOf(it.startMillis) } ?: "无记录"} | " +
                "${lastSleep?.let { clockOf(it.startMillis) } ?: "无记录"}"
        )
        builder.appendLine(
            "卧床(h) | ${prevSleep?.hours ?: "-"} | ${lastSleep?.hours ?: "-"}"
        )
        builder.appendLine("深睡(分) | ${prevSleep?.deepMinutes ?: "-"} | ${lastSleep?.deepMinutes ?: "-"}")
        builder.appendLine("REM(分) | ${prevSleep?.remMinutes ?: "-"} | ${lastSleep?.remMinutes ?: "-"}")
        builder.appendLine("清醒(分/次) | ${prevSleep?.let { "${it.awakeMinutes}/${it.awakeCount}" } ?: "-"} | ${lastSleep?.let { "${it.awakeMinutes}/${it.awakeCount}" } ?: "-"}")
        builder.appendLine("效率(%) | ${prevSleep?.efficiency ?: "-"} | ${lastSleep?.efficiency ?: "-"}")
        builder.appendLine("夜间心率 | ${prevSleep?.averageHeartRate ?: "-"} | ${lastSleep?.averageHeartRate ?: "-"}")
        builder.appendLine("夜间 HRV | ${prevSleep?.averageHrv ?: "-"} | ${lastSleep?.averageHrv ?: "-"}")
        builder.appendLine("最低血氧(%) | ${prevSleep?.minSpo2 ?: "-"} | ${lastSleep?.minSpo2 ?: "-"}")
        builder.appendLine()

        val deltas = nightDeltas(last, prev, history)
        builder.appendLine("## 差值（已由程序计算，不要自己重算）")
        builder.appendLine()
        builder.appendLine("指标 | 前晚 | 昨晚 | 差值 | 你近30天波动(SD) | 是否超出日常波动")
        builder.appendLine("---|---|---|---|---|---")
        deltas.forEach { d ->
            val zText = d.z?.let { String.format("%.1fσ", it) } ?: "无参照"
            builder.appendLine(
                listOf(
                    d.label,
                    d.format(d.prev),
                    d.format(d.last),
                    d.delta?.let { (if (it > 0) "+" else "") + d.format(it) } ?: "—",
                    d.sd?.let { d.format(it) } ?: "样本不足",
                    if (d.delta == null) "—" else if (d.worthExplaining) "是（$zText）" else "否（$zText）",
                ).joinToString(" | ")
            )
        }
        builder.appendLine()
        builder.appendLine("判定规则：|差值| ≥ 1 个标准差（|z| ≥ ${NOISE_THRESHOLD.toInt()}）才算超出个人日常波动。")
        builder.appendLine()

        val lastFeedback = feedback.filter { it.date == last.date }
        if (lastFeedback.isNotEmpty()) {
            builder.appendLine("## 今早的主观感受")
            builder.appendLine()
            lastFeedback.sortedBy { it.timestamp }.forEach { entry ->
                val parts = buildList {
                    entry.sleepQuality?.let { add("睡眠质量 $it/5") }
                    entry.energy?.let { add("精力 $it/5") }
                    entry.mood?.let { add("心情 $it/5") }
                    entry.soreness?.let { add("酸痛 $it/5") }
                    if (entry.symptoms.isNotEmpty()) add("症状：${entry.symptoms.joinToString("、")}")
                    if (entry.note.isNotBlank()) add("备注：${entry.note}")
                }
                if (parts.isNotEmpty()) builder.appendLine("- ${parts.joinToString("，")}")
            }
            builder.appendLine()
        }

        val relevantInterventions = interventions.filter { it.date == last.date || it.date == prev.date }
        if (relevantInterventions.isNotEmpty()) {
            builder.appendLine("## 这两天的干预记录")
            builder.appendLine()
            relevantInterventions.forEach { builder.appendLine("- ${it.date} ${it.display}") }
            builder.appendLine()
        }

        builder.appendLine("## 要求（严格遵守）")
        builder.appendLine()
        builder.appendLine("1. **先给结论**：这两晚之间，有没有真正值得注意的差异？一句话回答。")
        builder.appendLine("2. **只解释标记为「是」的项**。标记为「否」的项，直接用一句话说明「属日常波动，不需要关注」，")
        builder.appendLine("   不要为它编原因、不要建议改进。")
        builder.appendLine("3. 如果**所有项都是「否」**，就明确说「两晚没有实质差异」，")
        builder.appendLine("   并指出这本身是好事（说明波动小），不要硬找话题凑内容。")
        builder.appendLine("4. 对标记为「是」的项，逐项说明：可能原因 → 混杂因素（佩戴松紧、酒精、晚饭时间、睡前运动、")
        builder.appendLine("   房间温度）→ 这个差异是单晚偶发还是需要再看几晚。")
        builder.appendLine("5. 单晚数据本身有测量误差（消费级手环分期与 PSG 一致率约 76%），")
        builder.appendLine("   任何结论都要标注这个不确定性，不要用确定语气。")
        builder.appendLine("6. 最后给 **1~2 条**今晚就能做的事，宁可少、要具体；没有把握就说不确定。")
        return builder.toString()
    }

    private fun clockOf(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))

    /** 供 UI 展示的最近一天摘要。 */
    fun latestSummary(days: List<HealthDay>): String {
        val day = days.lastOrNull() ?: return "暂无数据"
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val sleep = day.sleep
        return buildString {
            append("${day.date}　")
            append("步数 ${day.steps}　")
            append("活动 ${day.activeCalories} kcal　")
            append("平均心率 ${day.heartRate?.average ?: "-"}　")
            append("静息 ${day.restingHeartRate ?: "-"}　")
            append("血氧 ${day.spo2?.average ?: "-"}%　")
            if (sleep != null) {
                append("睡眠 ${sleep.hours}h（深睡 ${sleep.deepMinutes}min / REM ${sleep.remMinutes}min）　")
                append("入睡 ${formatter.format(Date(sleep.startMillis))}")
            }
        }
    }
}

/**
 * 全身体检：把所有能拿到的数据一次性交给模型做系统评估。
 *
 * ## 为什么不发逐日表格
 *
 * 30 天 × 两张表的逐日数据会吃掉大半 token，而体检要的是**整体状态**不是某天细节。
 * 所以这里先做统计聚合（中位、范围、达标天数、异常日计数），把信息密度提上去。
 *
 * ## 框架
 *
 * 按 **AHA Life's Essential 8**（美国心脏协会）组织：饮食、体力活动、烟草、
 * 睡眠、体重、血脂、血糖、血压。其中手环能覆盖的只有**睡眠、体力活动**，
 * 加上心率/血氧/HRV/压力这些设备可测指标；其余必须明确告诉用户「测不到、要去医院」。
 *
 * 这样输出的价值不在「AI 说了什么」，而在**它诚实划分了自己能看和不能看的边界**。
 */
fun buildFullCheckup(
    days: List<HealthDay>,
    feedback: List<FeedbackEntry>,
    interventions: List<InterventionEntry> = emptyList(),
): String {
    val builder = StringBuilder()
    if (days.isEmpty()) return "（没有可分析的数据）"

    val sleeps = days.mapNotNull { it.sleep }
    val hrDays = days.mapNotNull { it.heartRate }
    val restingHrs = days.mapNotNull { it.restingHeartRate }
    val stressDays = days.mapNotNull { it.stress }
    val spo2Days = days.mapNotNull { it.spo2 }
    val sleepSpo2 = sleeps.mapNotNull { it.minSpo2 }

    fun pct(values: List<Int>, p: Double): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    builder.appendLine("## 数据窗口")
    builder.appendLine()
    builder.appendLine("${days.first().date} ~ ${days.last().date}，共 ${days.size} 天。")
    builder.appendLine("数据来自消费级手环（小米），**非医疗设备**。")
    builder.appendLine()

    // ── 睡眠 ──────────────────────────────────────────────
    builder.appendLine("## 睡眠（对照 AASM / NSF：成人 7~9 小时）")
    builder.appendLine()
    if (sleeps.isNotEmpty()) {
        val hours = sleeps.map { it.hours }
        val deepPcts = sleeps.mapNotNull { s ->
            val a = s.asleepMinutes
            if (a > 0) s.deepMinutes.toDouble() / a * 100 else null
        }
        val remPcts = sleeps.mapNotNull { s ->
            val a = s.asleepMinutes
            if (a > 0) s.remMinutes.toDouble() / a * 100 else null
        }
        val efficiencies = sleeps.mapNotNull { it.efficiency }
        val awakeCounts = sleeps.map { it.awakeCount }
        val avgHrs = sleeps.mapNotNull { it.averageHeartRate }
        val hrvs = sleeps.mapNotNull { it.averageHrv }

        builder.appendLine("- 卧床时长：中位 ${medianOf(hours, 1)} 小时，范围 ${"%.1f".format(hours.min())}~${"%.1f".format(hours.max())}")
        builder.appendLine("- 深睡占比：中位 ${deepPcts.medianOrZero()}%（范围 ${deepPcts.minOrZero()}~${deepPcts.maxOrZero()}%），达标（≥16%）${deepPcts.count { it >= 16 }} 天")
        builder.appendLine("- REM 占比：中位 ${remPcts.medianOrZero()}%（范围 ${remPcts.minOrZero()}~${remPcts.maxOrZero()}%），达标（20~25%）${remPcts.count { it in 20.0..25.0 }} 天")
        builder.appendLine("- 睡眠效率：中位 ${pct(efficiencies, 0.5)}%，最低 ${efficiencies.minOrNull() ?: "-"}%")
        builder.appendLine("- 夜间醒来次数：中位 ${pct(awakeCounts, 0.5)} 次，最多 ${awakeCounts.maxOrNull() ?: 0} 次")
        builder.appendLine("- 夜间平均心率：中位 ${pct(avgHrs, 0.5)} bpm")
        if (hrvs.isNotEmpty()) builder.appendLine("- 夜间 HRV：中位 ${pct(hrvs, 0.5)}（范围 ${hrvs.min()}~${hrvs.max()}）")
        val latencies = sleeps.mapNotNull { it.latencyMinutes }
        if (latencies.isNotEmpty()) {
            builder.appendLine("- 入睡潜伏期：中位 ${pct(latencies, 0.5)} 分钟")
        }
        builder.appendLine("- 入睡/起床：${clockText(days.first { it.sleep != null }.sleep!!.startMillis)} 前后入睡、${clockText(days.last { it.sleep != null }.sleep!!.endMillis)} 前后起床（按最近一天）")
    } else {
        builder.appendLine("- 无睡眠记录")
    }
    builder.appendLine()

    // ── 心血管 ────────────────────────────────────────────
    builder.appendLine("## 心血管与自主神经（对照：成人静息心率 60~100，长期 >80 风险升高）")
    builder.appendLine()
    if (restingHrs.isNotEmpty()) {
        builder.appendLine("- 静息心率：中位 ${pct(restingHrs, 0.5)} bpm（范围 ${restingHrs.min()}~${restingHrs.max()}），低于 60 的 ${restingHrs.count { it < 60 }} 天")
    } else {
        builder.appendLine("- 静息心率：无记录")
    }
    if (hrDays.isNotEmpty()) {
        val averages = hrDays.map { it.average }
        builder.appendLine("- 白天平均心率：中位 ${pct(averages, 0.5)} bpm（范围 ${averages.min()}~${averages.max()}）")
        builder.appendLine("- 白天最高心率：中位 ${pct(hrDays.map { it.max }, 0.5)} bpm")
    }
    if (sleeps.isNotEmpty()) {
        val firsts = sleeps.mapNotNull { it.firstHalfHeartRate }
        val seconds = sleeps.mapNotNull { it.secondHalfHeartRate }
        if (firsts.isNotEmpty() && seconds.isNotEmpty()) {
            builder.appendLine("- 前半夜/后半夜心率：中位 ${pct(firsts, 0.5)} / ${pct(seconds, 0.5)} bpm")
        }
    }
    if (stressDays.isNotEmpty()) {
        builder.appendLine("- 压力（设备根据 HRV 推算的相对强度，1~100）：中位 ${pct(stressDays.map { it.average }, 0.5)}，峰值最高 ${stressDays.maxOf { it.max }}")
    }
    builder.appendLine()

    // ── 活动 ──────────────────────────────────────────────
    builder.appendLine("## 体力活动（对照 AHA：每周 150 分钟中等强度；一般认为日均 7000~8000 步以上较好）")
    builder.appendLine()
    val steps = days.map { it.steps }
    val stands = days.map { it.standHours }
    val calories = days.map { it.activeCalories }
    builder.appendLine("- 日均步数 ${steps.average().roundToInt()}（范围 ${steps.min()}~${steps.max()}），≥8000 步 ${steps.count { it >= 8000 }} 天，<3000 步 ${steps.count { it < 3000 }} 天")
    builder.appendLine("- 日均有效站立 ${pct(stands, 0.5)} 小时，站立为 0 的 ${stands.count { it == 0 }} 天")
    builder.appendLine("- 日均活动消耗 ${calories.average().roundToInt()} kcal")
    builder.appendLine()

    // ── 呼吸与氧合 ─────────────────────────────────────────
    builder.appendLine("## 呼吸与氧合（对照：健康成人夜间血氧应基本 ≥95%；消费级设备误差 ±2~3%）")
    builder.appendLine()
    if (sleepSpo2.isNotEmpty()) {
        builder.appendLine("- 夜间最低血氧：中位 ${pct(sleepSpo2, 0.5)}%（范围 ${sleepSpo2.min()}~${sleepSpo2.max()}），≥95% 的 ${sleepSpo2.count { it >= 95 }} 天，<92% 的 ${sleepSpo2.count { it < 92 }} 天")
    }
    if (spo2Days.isNotEmpty()) {
        builder.appendLine("- 白天血氧：中位 ${pct(spo2Days.map { it.average }, 0.5)}%")
    }
    val breaths = sleeps.mapNotNull { it.averageBreath }
    if (breaths.isNotEmpty()) {
        builder.appendLine("- 夜间呼吸频率：中位 ${pct(breaths, 0.5)} 次/分（成人静息正常约 12~20）")
    }
    builder.appendLine()

    // ── 主观 ──────────────────────────────────────────────
    val relevantFeedback = feedback.filter { e -> days.any { it.date == e.date } }
    if (relevantFeedback.isNotEmpty()) {
        builder.appendLine("## 主观反馈")
        builder.appendLine()
        val qualities = relevantFeedback.mapNotNull { it.sleepQuality }
        val energies = relevantFeedback.mapNotNull { it.energy }
        val moods = relevantFeedback.mapNotNull { it.mood }
        val aches = relevantFeedback.mapNotNull { it.soreness }
        if (qualities.isNotEmpty()) builder.appendLine("- 睡眠质量均值 ${"%.1f".format(qualities.average())}/5（${qualities.size} 条记录）")
        if (energies.isNotEmpty()) builder.appendLine("- 精力均值 ${"%.1f".format(energies.average())}/5")
        if (moods.isNotEmpty()) builder.appendLine("- 心情均值 ${"%.1f".format(moods.average())}/5")
        if (aches.isNotEmpty()) builder.appendLine("- 酸痛均值 ${"%.1f".format(aches.average())}/5")
        val symptoms = relevantFeedback.flatMap { it.symptoms }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(6)
        if (symptoms.isNotEmpty()) {
            builder.appendLine("- 症状频次：${symptoms.joinToString("、") { "${it.key}×${it.value}" }}")
        }
        val notes = relevantFeedback.mapNotNull { it.note.takeIf { n -> n.isNotBlank() } }.takeLast(5)
        if (notes.isNotEmpty()) {
            builder.appendLine("- 近期备注：${notes.joinToString("；")}")
        }
        builder.appendLine()
    }

    // ── 干预 ──────────────────────────────────────────────
    val relevantInterventions = interventions.filter { e -> days.any { it.date == e.date } }
    if (relevantInterventions.isNotEmpty()) {
        builder.appendLine("## 主动干预")
        builder.appendLine()
        relevantInterventions.groupBy { it.name }.forEach { (name, entries) ->
            val doses = entries.mapNotNull { e -> e.dose?.let { d -> "$d${e.unit}" } }.distinct()
            builder.appendLine("- $name：${entries.size} 天${if (doses.isNotEmpty()) "（${doses.joinToString("/")}）" else ""}")
        }
        builder.appendLine()
    }

    // ── 要求 ──────────────────────────────────────────────
    builder.appendLine("## 输出要求")
    builder.appendLine()
    builder.appendLine("按 **AHA Life's Essential 8** 框架逐项评估，**必须严格区分「设备能判断」和「设备判断不了」**：")
    builder.appendLine()
    builder.appendLine("**第一类：可以从数据判断的**")
    builder.appendLine("- 睡眠（时长/结构/效率/规律性）")
    builder.appendLine("- 体力活动（步数、久坐、活动量）")
    builder.appendLine("- 心血管功能（静息心率趋势、夜间心率下降、HRV 相对个人基线）")
    builder.appendLine("- 呼吸与氧合（夜间血氧、呼吸频率）")
    builder.appendLine("- 自主神经/压力")
    builder.appendLine()
    builder.appendLine("**第二类：设备完全没有数据、必须线下检查的**")
    builder.appendLine("- 血压（目标 <120/80 mmHg）")
    builder.appendLine("- 血脂（non-HDL 胆固醇）")
    builder.appendLine("- 血糖（HbA1c）")
    builder.appendLine("- 体重/BMI（目标 18.5~<25，需要身高体重）")
    builder.appendLine("- 饮食质量、吸烟饮酒")
    builder.appendLine("- 视力、口腔、甲状腺、肝肾功能等其他体检项目")
    builder.appendLine()
    builder.appendLine("**输出格式（严格 JSON）**：")
    builder.appendLine("- headline：本次体检最重要的结论，不超过 20 字")
    builder.appendLine("- summary：整体状态的两三句话判断，≤120 字")
    builder.appendLine("- suggestions：6~8 条，**每条以【系统】开头**（如【睡眠】【活动】【心血管】【呼吸】【自主神经】），每条 ≤50 字，只说数据支持的判断和可执行动作")
    builder.appendLine("- warnings：2~4 条，一条必须列出「本次无法评估、建议线下检查的项目」，其余写数据里的风险信号")
    builder.appendLine()
    builder.appendLine("**禁止**：不要编造没有的指标；不要把消费级设备数据说成诊断结论；不要因为某项正常就推断「没问题」。")
    return builder.toString()
}

/** 时间戳 → HH:mm（top-level 版本，供体检 prompt 使用）。 */
private fun clockText(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))

/** 中位数（保留 1 位小数）。 */
private fun medianOf(values: List<Double>, digits: Int): String {
    if (values.isEmpty()) return "-"
    val sorted = values.sorted()
    val mid = sorted.size / 2
    val m = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    return if (digits == 0) m.roundToInt().toString() else "%.1f".format(m).replace('.', '.')
}

private fun List<Double>.medianOrZero(): Int {
    if (isEmpty()) return 0
    val sorted = sorted()
    val mid = sorted.size / 2
    return (if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2).roundToInt()
}

private fun List<Double>.minOrZero(): Int = if (isEmpty()) 0 else min().roundToInt()
private fun List<Double>.maxOrZero(): Int = if (isEmpty()) 0 else max().roundToInt()
