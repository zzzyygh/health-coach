package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzzyy.healthcoach.data.analysis.SleepScorer
import com.zzzyy.healthcoach.data.model.DataSource
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.ui.HealthBaselines
import com.zzzyy.healthcoach.ui.MetricKind
import com.zzzyy.healthcoach.ui.MetricSeries
import com.zzzyy.healthcoach.ui.UiState
import com.zzzyy.healthcoach.ui.HealthViewModel
import com.zzzyy.healthcoach.ui.components.BaselineSparkline
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.MetricCard
import com.zzzyy.healthcoach.ui.components.MiniStatRow
import com.zzzyy.healthcoach.ui.components.PanelCard
import com.zzzyy.healthcoach.ui.components.SleepCompositionBar
import com.zzzyy.healthcoach.ui.components.SleepCompositionSegment
import com.zzzyy.healthcoach.ui.components.SleepStageLegend
import com.zzzyy.healthcoach.ui.components.SleepStageTimeline
import com.zzzyy.healthcoach.ui.components.SectionHeader
import com.zzzyy.healthcoach.ui.components.StatCell
import com.zzzyy.healthcoach.ui.components.StatusPill
import com.zzzyy.healthcoach.ui.theme.HealthColors
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontFamily

/**
 * 概览页：**一眼看到答案**。
 *
 * 结构（从上到下就是「结论 → 细节 → 状态」）：
 *   1. 最新日期 + 数据来源
 *   2. 主读数（步数）大数字 + 与基线的偏离 + 14 天迷你走势
 *   3. 四个副指标（静息心率 / 睡眠 / 平均心率 / 血氧）
 *   4. 今日其余读数（距离、消耗、站立、深浅睡…）
 *   5. 数据接入状态
 */
@Composable
fun OverviewScreen(state: UiState, viewModel: HealthViewModel) {
    val days = state.days
    val series = remember(days) { HealthBaselines.all(days) }
    var showRangeMenu by remember { mutableStateOf(false) }
    var detailKind by remember { mutableStateOf<MetricKind?>(null) }
    // 有睡眠记录的日期（升序）；offset=0 表示最近一晚
    val sleepDays = remember(days) { days.filter { it.sleep != null } }
    var sleepOffset by remember { mutableStateOf(0) }
    var retroCheckInDate by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (days.isEmpty()) {
            EmptyOverview(state, viewModel)
            return@Column
        }

        val latest = days.last()

        // ── 头部：日期 + 来源 + 同步 ──────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Eyebrow("最新记录")
                Spacer(Modifier.height(2.dp))
                Text(
                    latest.date,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourcePill(latest.source)
                Spacer(Modifier.width(10.dp))
                Box {
                    TextButton(
                        onClick = { showRangeMenu = true },
                        enabled = !state.syncing,
                    ) {
                        if (state.syncing) {
                            CircularProgressIndicator(
                                Modifier.width(14.dp).height(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("同步中")
                        } else {
                            Text("同步 ▾")
                        }
                    }
                    DropdownMenu(
                        expanded = showRangeMenu,
                        onDismissRequest = { showRangeMenu = false },
                    ) {
                        SYNC_RANGES.forEach { (range, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    showRangeMenu = false
                                    viewModel.sync(range, fullPull = true)
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── 主读数 ────────────────────────────────────────────────
        HeroReadout(
            series = series[MetricKind.STEPS]!!,
            latest = latest,
            onClick = { detailKind = MetricKind.STEPS },
        )

        // 今天的数据还在往里写，跟整天的基线比一定偏低 —— 不说清楚会被误读成「今天状态很差」
        if (latest.date == todayString()) {
            PanelCard(accent = HealthColors.Amber.copy(alpha = 0.5f)) {
                Text(
                    "今天的数据还在累积中",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "睡眠、静息心率这些要到明天才是完整的一天。所以「相对基线」的跌幅，" +
                        "今天看会偏低，明天再看不迟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 副指标 2×2 ────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "静息心率",
                value = latest.restingHeartRate?.toString() ?: "—",
                unit = "bpm",
                history = series[MetricKind.RESTING_HR]!!.values.takeLast(HealthBaselines.SPARK_DAYS),
                baseline = series[MetricKind.RESTING_HR]!!.baseline,
                baselineBand = series[MetricKind.RESTING_HR]!!.baselineBand,
                baselineLabel = baselineLabel(series[MetricKind.RESTING_HR]!!),
                accent = HealthColors.Signal,
                modifier = Modifier.weight(1f),
                onClick = { detailKind = MetricKind.RESTING_HR },
            )
            MetricCard(
                label = "睡眠时长",
                value = latest.sleep?.let { String.format("%.1f", it.hours) } ?: "—",
                unit = "小时",
                history = series[MetricKind.SLEEP]!!.values.takeLast(HealthBaselines.SPARK_DAYS),
                baseline = series[MetricKind.SLEEP]!!.baseline,
                baselineBand = series[MetricKind.SLEEP]!!.baselineBand,
                baselineLabel = baselineLabel(series[MetricKind.SLEEP]!!),
                accent = HealthColors.Sleep,
                modifier = Modifier.weight(1f),
                onClick = { detailKind = MetricKind.SLEEP },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "平均心率",
                value = latest.heartRate?.average?.toString() ?: "—",
                unit = "bpm",
                history = series[MetricKind.AVG_HR]!!.values.takeLast(HealthBaselines.SPARK_DAYS),
                baseline = series[MetricKind.AVG_HR]!!.baseline,
                baselineBand = series[MetricKind.AVG_HR]!!.baselineBand,
                baselineLabel = baselineLabel(series[MetricKind.AVG_HR]!!),
                accent = HealthColors.Signal,
                modifier = Modifier.weight(1f),
                onClick = { detailKind = MetricKind.AVG_HR },
            )
            MetricCard(
                label = "血氧",
                value = latest.spo2?.average?.toString() ?: "—",
                unit = "%",
                history = series[MetricKind.SPO2]!!.values.takeLast(HealthBaselines.SPARK_DAYS),
                baseline = series[MetricKind.SPO2]!!.baseline,
                baselineBand = series[MetricKind.SPO2]!!.baselineBand,
                baselineLabel = baselineLabel(series[MetricKind.SPO2]!!),
                accent = HealthColors.Signal,
                modifier = Modifier.weight(1f),
                onClick = { detailKind = MetricKind.SPO2 },
            )
        }

        // ── 睡眠结构（可切换日期） ────────────────────────────────
        val sleepIndex = (sleepDays.lastIndex - sleepOffset).coerceIn(0, sleepDays.lastIndex.coerceAtLeast(0))
        val sleepDay = sleepDays.getOrNull(sleepIndex)
        val sleep = sleepDay?.sleep
        if (sleepDay != null && sleep != null) {
            // 打卡里的「睡眠质量」就是这台设备测不到的那一半：直接参与加权
            // 一天可能有多条记录（早上打卡 + 白天随手记），取最新一条带评分的
            val subjective = state.feedback
                .filter { it.date == sleepDay.date && it.sleepQuality != null }
                .maxByOrNull { it.timestamp }
                ?.sleepQuality
            val openedScore = remember(sleepDay.date, days, state.sleepGoal, subjective) {
                SleepScorer.score(sleepDay, days, state.sleepGoal, subjectiveQuality = subjective)
            }
            PanelCard(accent = HealthColors.Sleep.copy(alpha = 0.4f)) {
                SectionHeader(
                    if (sleepOffset == 0) "昨晚睡眠" else "历史睡眠",
                    trailing = if (sleep.hasStages) "${sleep.stages.size} 段分期" else "无分期数据",
                )

                // 日期切换：‹ 前一天 · 后一天 ›
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DayNavButton(
                        text = "‹ 前一天",
                        enabled = sleepOffset < sleepDays.lastIndex,
                        onClick = { sleepOffset += 1 },
                    )
                    Text(
                        sleepDay.date,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    DayNavButton(
                        text = "后一天 ›",
                        enabled = sleepOffset > 0,
                        onClick = { sleepOffset -= 1 },
                    )
                }

                // 这一天还没填主观感受 → 提供补填入口（否则它只会显示纯睡眠分，看起来偏高）
                val existingFeedback = state.feedback
                    .filter { it.date == sleepDay.date && it.sleepQuality != null }
                    .maxByOrNull { it.timestamp }
                if (existingFeedback == null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { retroCheckInDate = sleepDay.date },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            "＋ 补评这天的感受（补上后分数才和其他日子一样会被修正）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 评分
                openedScore?.let { score ->
                    // 近 30 天的评分分布：让人知道「今天这个分在自己历史里算正常还是异常」
                    val recentScores = remember(days, state.sleepGoal) {
                        days.filter { it.sleep != null }
                            .takeLast(30)
                            .mapNotNull { day ->
                                val q = state.feedback
                                    .filter { it.date == day.date && it.sleepQuality != null }
                                    .maxByOrNull { it.timestamp }
                                    ?.sleepQuality
                                SleepScorer.score(day, days, state.sleepGoal, q)?.total
                            }
                    }
                    SleepScoreBlock(score, recentScores)
                    Spacer(Modifier.height(16.dp))
                }

                // 占比基数 = 卧床时间（深+浅+REM+清醒），四项加起来正好 100%
                val totalMinutes = sleep.deepMinutes + sleep.lightMinutes +
                    sleep.remMinutes + sleep.awakeMinutes
                fun pct(m: Int): Int =
                    if (totalMinutes <= 0) 0 else Math.round(m.toFloat() / totalMinutes * 100)

                if (sleep.hasStages) {
                    Eyebrow("时间顺序")
                    Spacer(Modifier.height(6.dp))
                    SleepStageTimeline(
                        stages = sleep.stages,
                        startMillis = sleep.startMillis,
                        endMillis = sleep.endMillis,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                Eyebrow("各阶段占比")
                Spacer(Modifier.height(6.dp))
                SleepCompositionBar(
                    segments = listOf(
                        SleepCompositionSegment("深睡", sleep.deepMinutes, HealthColors.Sleep, Color.White),
                        SleepCompositionSegment(
                            "浅睡", sleep.lightMinutes,
                            HealthColors.Sleep.copy(alpha = 0.45f), Color.White,
                        ),
                        SleepCompositionSegment("REM", sleep.remMinutes, HealthColors.Signal, Color.White),
                        SleepCompositionSegment("清醒", sleep.awakeMinutes, HealthColors.Amber, Color.White),
                    ),
                    totalMinutes = totalMinutes,
                )

                Spacer(Modifier.height(14.dp))
                if (sleep.hasStages) SleepStageLegend()
                Spacer(Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStatRow(
                        "深睡", formatMinutes(sleep.deepMinutes), HealthColors.Sleep,
                        percent = pct(sleep.deepMinutes),
                    )
                    MiniStatRow(
                        "浅睡", formatMinutes(sleep.lightMinutes), HealthColors.Sleep.copy(alpha = 0.45f),
                        percent = pct(sleep.lightMinutes),
                    )
                    MiniStatRow(
                        "快速眼动 REM", formatMinutes(sleep.remMinutes), HealthColors.Signal,
                        percent = pct(sleep.remMinutes),
                    )
                    MiniStatRow(
                        "清醒",
                        formatMinutes(sleep.awakeMinutes) +
                            if (sleep.awakeCount > 0) "（醒 ${sleep.awakeCount} 次）" else "",
                        HealthColors.Amber,
                        percent = pct(sleep.awakeMinutes),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sleep.efficiency?.let { MiniStatRow("睡眠效率", "$it%") }
                    if (sleep.averageHeartRate != null || sleep.averageHrv != null) {
                        MiniStatRow(
                            "夜间心率 / HRV",
                            "${sleep.averageHeartRate ?: "-"} bpm　/　${sleep.averageHrv ?: "-"}",
                        )
                    }
                }

                val remRatio = if (sleep.asleepMinutes > 0) {
                    sleep.remMinutes.toFloat() / sleep.asleepMinutes * 100f
                } else null
                remRatio?.let { ratio ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "REM 占睡着时间 ${ratio.roundToInt()}%（成人常见 20~25%）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "评分基于消费级手环的分期数据，不是医学诊断；单日波动不必在意，看 1~2 周趋势。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 今日其余读数 ──────────────────────────────────────────
        PanelCard {
            SectionHeader("今日其余读数")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCell("距离", latest.distanceMeters.toString(), "米", Modifier.weight(1f))
                    StatCell("活动消耗", latest.activeCalories.toString(), "kcal", Modifier.weight(1f))
                    StatCell("有效站立", latest.standHours.toString(), "小时", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCell(
                        "心率区间",
                        latest.heartRate?.let { "${it.min}~${it.max}" } ?: "—",
                        "bpm",
                        Modifier.weight(1f),
                    )
                    StatCell(
                        "最低血氧",
                        latest.spo2?.min?.toString() ?: "—",
                        "%",
                        Modifier.weight(1f),
                    )
                    StatCell(
                        "压力均值",
                        latest.stress?.average?.toString() ?: "—",
                        if (latest.stress != null) "峰 ${latest.stress.max}" else "",
                        Modifier.weight(1f),
                    )
                }
            }
        }

        // ── 数据接入状态 ──────────────────────────────────────────
        PanelCard {
            SectionHeader("数据接入")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    "本机数据库",
                    state.rootAvailable,
                    if (state.rootAvailable) "root 可用 · 含静息心率 / HRV / 压力"
                    else "未授权 · 只能读云端指标",
                )
                StatusPill(
                    "小米云端",
                    state.tokenUsable,
                    if (state.tokenUsable) "已登录 · 用于跨设备同步"
                    else "未登录 · 可跳过",
                )
                StatusPill(
                    "模型 API",
                    state.modelConfigured,
                    if (state.modelConfigured) "已配置 · 可生成健康判断"
                    else "未配置 · 去设置里填 API Key",
                )
            }
        }

        state.syncDetail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.statusMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    retroCheckInDate?.let { date ->
        val existing = state.feedback.firstOrNull { it.date == date }
        RetroCheckInDialog(
            date = date,
            initialQuality = existing?.sleepQuality,
            initialEnergy = existing?.energy,
            onSubmit = { quality, energy ->
                viewModel.submitCheckInForDate(date, quality, energy, "")
                retroCheckInDate = null
            },
            onDismiss = { retroCheckInDate = null },
        )
    }

    detailKind?.let { kind ->
        MetricDetailDialog(
            series = series[kind] ?: HealthBaselines.series(days, kind),
            onDismiss = { detailKind = null },
        )
    }
}

/** 主读数：大号数字 + 与基线的偏离 + 走势。 */
@Composable
private fun HeroReadout(series: MetricSeries, latest: HealthDay, onClick: () -> Unit) {
    PanelCard(
        accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Eyebrow("今日步数")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        latest.steps.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "步",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            val deviation = series.deviationPercent
            if (deviation != null && kotlin.math.abs(deviation) >= 1f) {
                Column(horizontalAlignment = Alignment.End) {
                    Eyebrow("相对基线")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${if (deviation > 0) "↑" else "↓"} ${kotlin.math.abs(deviation).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = HealthColors.Signal,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        BaselineSparkline(
            values = series.values.takeLast(HealthBaselines.SPARK_DAYS),
            baseline = series.baseline,
            band = series.baselineBand,
            color = HealthColors.Signal,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                baselineLabel(series) ?: "基线样本不足",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "近 ${HealthBaselines.SPARK_DAYS} 天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 数据来源标签。 */
@Composable
private fun SourcePill(source: DataSource) {
    val (text, color) = when (source) {
        DataSource.LOCAL -> "本机" to HealthColors.Signal
        DataSource.CLOUD -> "云端" to HealthColors.Sleep
        DataSource.UNKNOWN -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * 同步范围选项。
 *
 * 90 天云端会比较慢（心率一天约 686 条，要翻几十页），
 * 但本地库读取不吃这一套 —— 有 root 时 90 天也就几秒。
 */
private val SYNC_RANGES = listOf(
    14 to "最近 14 天",
    30 to "最近 30 天",
    90 to "最近 90 天（云端较慢）",
)

/** 分钟 → 「1 小时 17 分」/「4 分」。 */
private fun formatMinutes(minutes: Int): String = when {
    minutes <= 0 -> "—"
    minutes < 60 -> "$minutes 分"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}

private fun todayString(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())

private fun baselineLabel(series: MetricSeries): String? {
    val baseline = series.baseline ?: return null
    val value = HealthBaselines.format(series.kind, baseline)
    val unit = series.kind.unit
    return "基线 $value $unit · ${series.baselineSampleDays} 天中位数"
}

@Composable
private fun EmptyOverview(state: UiState, viewModel: HealthViewModel) {
    PanelCard {
        Text(
            "还没有数据",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "点右上角「同步」：手机有 root 权限时会直接读取本机小米运动健康数据库" +
                "（静息心率、HRV、压力都在里面）；没有的话需要先在设置里登录小米账号。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        state.syncDetail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SYNC_RANGES.forEach { (range, label) ->
            TextButton(onClick = { viewModel.sync(range, fullPull = true) }, enabled = !state.syncing) {
                Text(label)
            }
        }
    }
}

/** 日期切换的小按钮（‹ 前一天 / 后一天 ›）。 */
@Composable
private fun DayNavButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/**
 * 睡眠评分块。
 *
 * 版式上刻意把**每一维的原始分与判定标准都列出来**，
 * 而不是只给一个大数字 —— 一个大数字没法验证，也没法据此改进。
 */
@Composable
private fun SleepScoreBlock(score: SleepScorer.Result, recent: List<Int>) {
    val color = when {
        score.total >= 85 -> HealthColors.Signal
        score.total >= 70 -> HealthColors.Sleep
        score.total >= 60 -> HealthColors.Amber
        else -> HealthColors.Brick
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Eyebrow("睡眠评分")
            Spacer(Modifier.height(2.dp))
            // 主数字永远是**纯睡眠分**（设备算出来的那个），主观评分只在后面做加减
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${score.deviceScore}",
                    style = MaterialTheme.typography.displayMedium,
                    color = color,
                )
                score.subjectiveAdjust?.let { adjust ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (adjust >= 0) "+ $adjust" else "− ${kotlin.math.abs(adjust)}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (adjust >= 0) HealthColors.Signal else HealthColors.Amber,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "=",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${score.total}",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .padding(bottom = 2.dp),
                ) {
                    Text(
                        score.grade,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                }
            }
            // 「今日 vs 近 7 日」：单晚好不代表债还清了
            Spacer(Modifier.height(6.dp))
            TrendBadge(today = score.total, recent = recent)
        }
        if (recent.isNotEmpty()) {
            Text(
                "近 ${recent.size} 天 ${recent.min()}~${recent.max()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 封顶说明（写清楚原因，不偷偷扣）
    score.capReason?.let { reason ->
        Spacer(Modifier.height(6.dp))
        Text(
            reason,
            style = MaterialTheme.typography.labelSmall,
            color = HealthColors.Amber,
        )
    }

    // 自评与设备明显不一致时，提示比扣分更有价值
    if (score.subjectiveQuality != null &&
        SleepScorer.hasSubjectiveConflict(score.deviceScore, score.subjectiveQuality)
    ) {
        Spacer(Modifier.height(6.dp))
        Text(
            if (score.subjectiveScore!! < score.deviceScore) {
                "你感觉比设备数据差很多 —— 可能设备高估了深睡/低估了不安稳，也可能是疲劳来自睡眠之外（压力、白天活动）。"
            } else {
                "你感觉比设备数据好很多 —— 设备可能低估了实际恢复。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = HealthColors.Amber,
        )
    }

    // 分项 → 修正 → 最终，让每一步都能对上
    if (score.trendAdjust != 0) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "分项合计 ${score.subtotal}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${if (score.trendAdjust > 0) "+" else ""}${score.trendAdjust}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (score.trendAdjust > 0) HealthColors.Signal else HealthColors.Amber,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (score.trendAdjust > 0) {
                    "近 7 日均 ${score.recentAverage}，今天偏低属偶然，补回"
                } else {
                    "近 7 日均 ${score.recentAverage}，今天偏高可能只是波动"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // 总分进度条
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(score.deviceScore / 100f)
                .height(6.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
    }

    Spacer(Modifier.height(12.dp))

    // 分项明细：每项都显示「实得/满分」，丢分一眼可见
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        score.dimensions.forEach { dimension ->
            val ratio = dimension.score?.let { it.toFloat() / dimension.maxScore } ?: 0f
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dimension.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            dimension.reading,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        dimension.basis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (dimension.score == null) "—"
                        else "${dimension.score}/${dimension.maxScore}",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                        color = when {
                            dimension.score == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            ratio >= 0.9f -> HealthColors.Signal
                            ratio >= 0.7f -> MaterialTheme.colorScheme.onSurface
                            ratio >= 0.4f -> HealthColors.Amber
                            else -> HealthColors.Brick
                        },
                    )
                    // 该维度的小进度条
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(
                                    when {
                                        ratio >= 0.9f -> HealthColors.Signal
                                        ratio >= 0.7f -> HealthColors.Sleep
                                        ratio >= 0.4f -> HealthColors.Amber
                                        else -> HealthColors.Brick
                                    },
                                    RoundedCornerShape(2.dp),
                                )
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    Text(
        "依据：深睡 Ohayon 2004 / Oura（≥16% 或 ≥60 分钟）· REM AASM 20~25% · " +
            "潜伏期 Oura Latency 10~20 分钟 · 效率 AASM ≥85% · 连续性 WASO<30 分钟 · " +
            "时长 Cappuccio 2010（7~8 小时最优，9 小时以上风险已升高）· " +
            "心率/HRV 对比你个人的 30 天基线 · 节律对比你设定的目标时间。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (score.missingPoints > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "有 ${score.missingPoints} 分因数据缺失未计入（设备未记录对应指标）。",
            style = MaterialTheme.typography.labelSmall,
            color = HealthColors.Amber,
        )
    }
}
