package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zzzyy.healthcoach.ui.HealthViewModel
import com.zzzyy.healthcoach.ui.MorningSummary
import com.zzzyy.healthcoach.ui.UiState
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.MiniStatRow
import com.zzzyy.healthcoach.ui.components.ScaleRow
import com.zzzyy.healthcoach.ui.theme.HealthColors

/**
 * 睡醒打卡。
 *
 * 每天第一次打开 App 时弹一次，问两件事：昨晚睡得怎么样、现在精力如何。
 *
 * 为什么要在早上问：手环能测时长和分期，测不到「睡醒的感觉」。
 * 而主观感受恰恰是判断恢复质量最直接的一条 —— 同样的 8 小时，
 * 有人醒来清爽有人还是累，差别只有自己知道。
 * 而且必须趁刚醒时问：隔几个小时再回忆，记忆会被白天的状态污染。
 */
@Composable
fun MorningCheckInDialog(
    state: UiState,
    viewModel: HealthViewModel,
) {
    val summary = state.lastNightSummary ?: return
    var sleepQuality by remember { mutableIntStateOf(0) }
    var energy by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { viewModel.dismissMorningCheckIn() }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column {
                    Eyebrow("早上好 · 昨晚 ${summary.date}")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "昨晚睡得怎么样？",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 昨晚客观摘要：先给读数，再让人评价，避免被数字带偏
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    MiniStatRow("睡眠时长", "%.1f 小时".format(summary.hours))
                    MiniStatRow("深睡", "${summary.deepMinutes} 分钟", HealthColors.Sleep)
                    MiniStatRow("快速眼动 REM", "${summary.remMinutes} 分钟", HealthColors.Signal)
                    summary.score?.let { score ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "设备评分",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "$score · ${summary.grade ?: ""}",
                                style = MaterialTheme.typography.titleSmall,
                                color = HealthColors.Sleep,
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScaleRow("睡眠质量", sleepQuality, onChange = { sleepQuality = it })
                    ScaleRow("现在精力", energy, onChange = { energy = it })
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = {
                        Text(
                            "想补充的（比如半夜醒了、做梦多、被吵醒）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = MaterialTheme.shapes.small,
                )

                Text(
                    "设备测得到时长和分期，测不到你醒来的感觉 —— 这一项只有你知道。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { viewModel.dismissMorningCheckIn() }) {
                        Text("今天不评")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            viewModel.submitMorningCheckIn(sleepQuality, energy, note)
                        },
                    ) {
                        Text("记下来")
                    }
                }
            }
        }
    }
}

/** 一个「今日 vs 近 7 日」的趋势小标签。 */
@Composable
fun TrendBadge(
    today: Int,
    recent: List<Int>,
    modifier: Modifier = Modifier,
) {
    if (recent.size < 3) return
    val avg = recent.average().toInt()
    val (label, color) = when {
        // 今天好，但一周整体偏低 → 还在还债，白天可能仍困
        today >= 80 && avg < 75 -> "近 7 日均 $avg · 仍在还债" to HealthColors.Amber
        today - avg >= 6 -> "近 7 日均 $avg · 回升中" to HealthColors.Signal
        avg - today >= 6 -> "近 7 日均 $avg · 下滑" to HealthColors.Brick
        else -> "近 7 日均 $avg · 平稳" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** 单纯展示用的摘要类型别名（避免 UI 层重复声明）。 */
typealias MorningSummaryAlias = MorningSummary

/**
 * 补评某一天的主观感受。
 *
 * 存在的理由：没打分的那天只能用纯睡眠分，看起来会比「打过卡、被主观分拉低」的日子更高。
 * 这是数据缺失造成的不公平，唯一解法是允许事后补上。
 */
@Composable
fun RetroCheckInDialog(
    date: String,
    initialQuality: Int?,
    initialEnergy: Int?,
    onSubmit: (quality: Int, energy: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var sleepQuality by remember { mutableIntStateOf(initialQuality ?: 0) }
    var energy by remember { mutableIntStateOf(initialEnergy ?: 0) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column {
                    Eyebrow("补评 · $date 那一晚")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "那晚睡得怎么样？",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text(
                    "补上之后这一天的分数会和其他日子一样被主观感受修正，不再偏高。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScaleRow("睡眠质量", sleepQuality, onChange = { sleepQuality = it })
                    ScaleRow("现在精力", energy, onChange = { energy = it })
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onSubmit(sleepQuality, energy) },
                        enabled = sleepQuality > 0,
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}
