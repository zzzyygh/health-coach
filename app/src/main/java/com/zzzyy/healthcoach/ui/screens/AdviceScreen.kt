package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zzzyy.healthcoach.ui.HealthViewModel
import com.zzzyy.healthcoach.ui.UiState
import com.zzzyy.healthcoach.ui.components.AdviceItem
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.PanelCard
import com.zzzyy.healthcoach.ui.components.ScrollableSelector
import com.zzzyy.healthcoach.ui.theme.HealthColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton

/**
 * 建议页。
 *
 * 版式刻意做成**简短的病历条目**，不是聊天气泡：
 * 标题 → 一段总述 → 编号条目 → 需要留意的信号。
 */
@Composable
fun AdviceScreen(state: UiState, viewModel: HealthViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PanelCard {
            Eyebrow("健康判断")
            Spacer(Modifier.height(6.dp))
            Text(
                "选一个窗口：看趋势用多天，找「昨晚为什么累」用单晚。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            ScrollableSelector(
                options = listOf("近 7 天", "近 14 天", "近 30 天"),
                selectedIndex = when (state.adviceWindowDays) {
                    14 -> 1
                    30 -> 2
                    else -> 0
                },
                onSelect = { index ->
                    viewModel.setAdviceWindowDays(listOf(7, 14, 30)[index])
                },
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.generateAdvice(singleNight = false) },
                enabled = !state.adviceLoading && state.modelConfigured,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = MaterialTheme.shapes.small,
            ) {
                if (state.adviceLoading) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("生成中…")
                } else {
                    Text(
                        if (state.modelConfigured) "分析最近 ${state.adviceWindowDays} 天"
                        else "请先在设置里配置模型 API"
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.generateAdvice(singleNight = true) },
                enabled = !state.adviceLoading && state.modelConfigured,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("聚焦昨晚（带前两晚对照）")
            }

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.generateAdvice(nightCompare = true) },
                enabled = !state.adviceLoading && state.modelConfigured,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("昨晚 vs 前晚（逐项差异 + 噪声判断）")
            }
            Text(
                "差值和个人日常波动都由程序算好，超出 1 个标准差的项才做解释；没超出会直接告诉你「属日常波动」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.generateAdvice(fullCheckup = true) },
                enabled = !state.adviceLoading && state.modelConfigured,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("全身体检（30 天全系统评估）")
            }
            Text(
                "体检按 AHA Life's Essential 8 框架评估，并会明确列出「设备测不到、需要去医院」的项目。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.adviceError?.let { error ->
            PanelCard(accent = HealthColors.Brick) {
                Eyebrow("出错", color = HealthColors.Brick)
                Spacer(Modifier.height(6.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        val advice = state.advice
        if (advice != null && advice.parseFailed) {
            PanelCard(accent = HealthColors.Amber) {
                Eyebrow("这次没解析成功", color = HealthColors.Amber)
                Spacer(Modifier.height(6.dp))
                Text(
                    advice.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.generateAdvice() },
                    enabled = !state.adviceLoading,
                ) {
                    Text("重新生成")
                }
            }
            // 原文默认折叠：它是一坨 JSON，展开只用于排查
            var showRaw by remember { mutableStateOf(false) }
            PanelCard {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Eyebrow("模型原始输出（用于排查）")
                    androidx.compose.material3.TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "收起" else "展开")
                    }
                }
                if (showRaw) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        advice.rawText.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (advice != null && !advice.parseFailed) {
            PanelCard(accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) {
                Text(
                    advice.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (advice.summary.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        advice.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (advice.items.isNotEmpty()) {
                Eyebrow("具体建议")
                advice.items.forEachIndexed { index, item ->
                    AdviceItem(index + 1, item)
                }
            }

            if (advice.warnings.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(HealthColors.Amber, RoundedCornerShape(2.dp))
                    )
                    Text(
                        "需要留意",
                        style = MaterialTheme.typography.labelLarge,
                        color = HealthColors.Amber,
                    )
                }
                advice.warnings.forEach { warning ->
                    PanelCard(accent = HealthColors.Amber.copy(alpha = 0.5f)) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

        } else if (!state.adviceLoading && state.adviceError == null) {
            PanelCard {
                Text(
                    "还没有生成过判断。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 历史建议 ────────────────────────────────────────────
        if (state.adviceHistory.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Eyebrow("历史建议 · 已存 ${state.adviceHistory.size} 条")
            state.adviceHistory.takeLast(10).reversed().forEach { record ->
                var expanded by remember { mutableStateOf(false) }
                PanelCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.headline,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildString {
                                    append(record.date)
                                    append("  ")
                                    append(
                                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                                            .format(java.util.Date(record.timestamp))
                                    )
                                    if (record.model.isNotBlank()) append("  ·  ${record.model}")
                                    if (record.parseFailed) append("  ·  解析失败")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开")
                        }
                    }
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            record.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        record.suggestions.forEachIndexed { index, item ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${index + 1}. $item",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        record.warnings.forEach { warning ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "⚠ $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = HealthColors.Amber,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { viewModel.clearAdviceHistory() }) {
                Text("清空历史", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
