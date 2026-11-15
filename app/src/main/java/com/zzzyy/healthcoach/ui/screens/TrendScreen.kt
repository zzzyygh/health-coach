package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zzzyy.healthcoach.ui.HealthBaselines
import com.zzzyy.healthcoach.ui.MetricKind
import com.zzzyy.healthcoach.ui.UiState
import com.zzzyy.healthcoach.ui.components.BaselineLineChart
import com.zzzyy.healthcoach.ui.components.DeviationBar
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.PanelCard
import com.zzzyy.healthcoach.ui.components.ScrollableSelector
import com.zzzyy.healthcoach.ui.components.StatCell
import com.zzzyy.healthcoach.ui.theme.HealthColors
import kotlin.math.abs

/**
 * 趋势页：主图 + 偏离标尺 + 每日明细。
 *
 * 图上有一条**自己的基线带**（不是人群平均、不是目标线），
 * 配一条偏离标尺：竖线 = 基线，圆点 = 今天。
 */
@Composable
fun TrendScreen(state: UiState) {
    val days = state.days
    var metricIndex by remember { mutableIntStateOf(0) }
    // 7 个指标没法等分一行，用横向滚动（仪器面板档位多时也是这么干的）
    val kinds = listOf(
        MetricKind.STEPS,
        MetricKind.RESTING_HR,
        MetricKind.AVG_HR,
        MetricKind.SPO2,
        MetricKind.SLEEP,
        MetricKind.SLEEP_REM,
        MetricKind.SLEEP_DEEP,
        MetricKind.STRESS,
    )
    val kind = kinds[metricIndex.coerceIn(kinds.indices)]
    val series = remember(days, kind) { HealthBaselines.series(days, kind) }
    // 图可点：点/拖切换日期，顶部读数跟着变。默认最新一天（null = 最新）
    var selectedIndex by remember(kind) { mutableStateOf<Int?>(null) }
    val focusIndex = (selectedIndex ?: series.points.lastIndex).coerceIn(0, series.points.lastIndex.coerceAtLeast(0))
    val focusPoint = series.points.getOrNull(focusIndex)
    val focusDeviation = series.baseline?.takeIf { it > 0f }?.let { base ->
        focusPoint?.let { (it.value - base) / base * 100f }
    }
    val accent = when (kind) {
        MetricKind.SLEEP, MetricKind.SLEEP_REM, MetricKind.SLEEP_DEEP -> HealthColors.Sleep
        MetricKind.STRESS -> HealthColors.Amber
        else -> HealthColors.Signal
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScrollableSelector(
            options = kinds.map { it.short },
            selectedIndex = metricIndex,
            onSelect = { metricIndex = it },
        )

        if (series.points.size < 2) {
            PanelCard {
                Text(
                    "「${kind.label}」的数据还不够，至少需要两天。先同步一次。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // ── 主图 ────────────────────────────────────────────────
        PanelCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Eyebrow("${kind.label} · ${focusPoint?.date ?: "-"}")
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            HealthBaselines.format(kind, focusPoint?.value),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            " ${kind.unit}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                focusDeviation?.let { deviation ->
                    Column(horizontalAlignment = Alignment.End) {
                        Eyebrow("相对基线")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${if (deviation > 0) "↑" else "↓"} ${abs(deviation).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = accent,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            BaselineLineChart(
                values = series.values,
                labels = series.labels,
                baseline = series.baseline,
                band = series.baselineBand,
                color = accent,
                valueFormatter = { HealthBaselines.format(kind, it) },
                selectedIndex = focusIndex,
                onSelect = { selectedIndex = it },
            )

            Spacer(Modifier.height(6.dp))
            Text(
                "点或左右拖图切换日期 · 共 ${series.points.size} 天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            DeviationBar(series = series, color = accent)

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "低 ${HealthBaselines.format(kind, series.min)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "高 ${HealthBaselines.format(kind, series.max)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 统计 ────────────────────────────────────────────────
        PanelCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCell(
                    "均值",
                    HealthBaselines.format(kind, series.mean),
                    kind.unit,
                    Modifier.weight(1f),
                )
                StatCell(
                    "基线",
                    HealthBaselines.format(kind, series.baseline),
                    kind.unit,
                    Modifier.weight(1f),
                )
                StatCell(
                    "样本",
                    series.points.size.toString(),
                    "天",
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "基线取最近 ${HealthBaselines.WINDOW_DAYS} 天（不含今天）的中位数，" +
                    "所以单日的异常不会把它带偏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 每日明细 ────────────────────────────────────────────
        PanelCard {
            Eyebrow("每日明细")
            Spacer(Modifier.height(10.dp))
            series.points.reversed().forEach { point ->
                val deviation = series.baseline?.let { base ->
                    if (base <= 0f) null else (point.value - base) / base * 100f
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        point.date,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        HealthBaselines.format(kind, point.value),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        deviation?.let { "%+d%%".format(it.toInt()) } ?: "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            deviation == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            abs(deviation) < 1f -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> accent
                        },
                    )
                }
            }
        }
    }
}
