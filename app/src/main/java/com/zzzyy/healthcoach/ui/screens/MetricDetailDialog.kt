package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zzzyy.healthcoach.ui.HealthBaselines
import com.zzzyy.healthcoach.ui.MetricKind
import com.zzzyy.healthcoach.ui.MetricSeries
import com.zzzyy.healthcoach.ui.components.BaselineLineChart
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.PanelCard
import com.zzzyy.healthcoach.ui.theme.HealthColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 指标详情：点概览页的卡片就打开这个。
 *
 * 概览页的卡片只给「一个数字 + 一条迷你走势」，
 * 想看具体哪天是多少，就得有地方能翻 —— 这一页就是那个地方。
 *
 * 图可点，点了上面的读数跟着变；下面还有逐日明细。
 */
@Composable
fun MetricDetailDialog(
    series: MetricSeries,
    onDismiss: () -> Unit,
) {
    val kind = series.kind
    val accent = accentOf(kind)
    // 默认选中最新一天；点/拖图可以换
    var selected by remember(kind) { mutableStateOf<Int?>(null) }
    // 打开弹窗的那一次点击会穿透到刚出现的图上、随机选中一天。
    // 装一个 250ms 的「保险栓」：刚打开时不响应图上的手势。
    var interactive by remember(kind) { mutableStateOf(false) }
    LaunchedEffect(kind) {
        kotlinx.coroutines.delay(400)
        interactive = true
    }
    val focusIndex = (selected ?: (series.points.size - 1)).coerceIn(0, series.points.lastIndex.coerceAtLeast(0))
    val focusPoint = series.points.getOrNull(focusIndex)
    val focusDeviation = series.baseline?.takeIf { it > 0f }?.let { base ->
        focusPoint?.let { (it.value - base) / base * 100f }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Eyebrow(kind.label)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            focusPoint?.date ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                // 选中那天的读数（点图会跟着变）
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
                    Spacer(Modifier.fillMaxWidth(0.06f))
                    focusDeviation?.let { deviation ->
                        Text(
                            "${if (deviation > 0) "↑" else "↓"} ${abs(deviation).roundToInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = accent,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }

                Text(
                    "相对你的基线（${HealthBaselines.format(kind, series.baseline)} ${kind.unit}，" +
                        "${series.baselineSampleDays} 天中位数）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                BaselineLineChart(
                    values = series.values,
                    labels = series.labels,
                    baseline = series.baseline,
                    band = series.baselineBand,
                    color = accent,
                    valueFormatter = { HealthBaselines.format(kind, it) },
                    selectedIndex = focusIndex,
                    onSelect = { if (interactive) selected = it },
                )

                Text(
                    "点或左右拖图切换日期",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 逐日明细
                PanelCard {
                    Eyebrow("逐日明细 · ${series.points.size} 天")
                    Spacer(Modifier.height(8.dp))
                    series.points.reversed().forEach { point ->
                        val deviation = series.baseline?.takeIf { it > 0f }?.let { base ->
                            (point.value - base) / base * 100f
                        }
                        val isFocus = point.date == focusPoint?.date
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isFocus) accent.copy(alpha = 0.10f) else Color.Transparent,
                                    MaterialTheme.shapes.extraSmall,
                                )
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                point.date,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                HealthBaselines.format(kind, point.value),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                deviation?.let { "%+d%%".format(it.roundToInt()) } ?: "—",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (deviation != null && abs(deviation) >= 1f) accent
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun accentOf(kind: MetricKind): Color = when (kind) {
    MetricKind.SLEEP, MetricKind.SLEEP_REM, MetricKind.SLEEP_DEEP -> HealthColors.Sleep
    MetricKind.STRESS -> HealthColors.Amber
    else -> HealthColors.Signal
}
