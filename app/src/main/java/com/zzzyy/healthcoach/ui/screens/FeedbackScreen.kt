package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zzzyy.healthcoach.data.model.FeedbackEntry
import com.zzzyy.healthcoach.data.model.SymptomTags
import com.zzzyy.healthcoach.ui.HealthViewModel
import com.zzzyy.healthcoach.ui.UiState
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.PanelCard
import com.zzzyy.healthcoach.ui.components.ScaleRow
import com.zzzyy.healthcoach.ui.components.ToggleTag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment

/**
 * 反馈页。
 *
 * 手环能测心率、血氧、睡眠，但「今天累不累」「哪里不舒服」只能自己说。
 * 这一页就是把那另一半数据补上 —— 它对健康判断的价值不比传感器低。
 */
@Composable
fun FeedbackScreen(state: UiState, viewModel: HealthViewModel) {
    var sleepQuality by remember { mutableIntStateOf(0) }
    var energy by remember { mutableIntStateOf(0) }
    var mood by remember { mutableIntStateOf(0) }
    var soreness by remember { mutableIntStateOf(0) }
    var symptoms by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }

    val today = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PanelCard {
            Eyebrow("今天怎么样 · $today")
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScaleRow("睡眠质量", sleepQuality, onChange = { sleepQuality = it })
                ScaleRow("精力", energy, onChange = { energy = it })
                ScaleRow("心情", mood, onChange = { mood = it })
                ScaleRow("肌肉酸痛", soreness, onChange = { soreness = it })
            }
        }

        PanelCard {
            Eyebrow("身体感受")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SymptomTags.ALL.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { tag ->
                            ToggleTag(
                                text = tag,
                                selected = tag in symptoms,
                                onClick = {
                                    symptoms = if (tag in symptoms) symptoms - tag else symptoms + tag
                                },
                            )
                        }
                    }
                }
            }
        }

        PanelCard {
            Eyebrow("想补充点什么")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = {
                    Text(
                        "比如：下午犯困、开会久了肩颈发僵、昨晚喝了酒…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = MaterialTheme.shapes.small,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    viewModel.saveFeedback(
                        FeedbackEntry(
                            date = today,
                            timestamp = System.currentTimeMillis(),
                            sleepQuality = sleepQuality.takeIf { it > 0 },
                            energy = energy.takeIf { it > 0 },
                            mood = mood.takeIf { it > 0 },
                            soreness = soreness.takeIf { it > 0 },
                            symptoms = symptoms.toList(),
                            note = note.trim(),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("记录这条反馈")
            }
            Text(
                "每次记录都是独立一条，不会覆盖之前的（同一天可以记多条）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InterventionSection(state, viewModel, today)

        state.statusMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.feedback.isNotEmpty()) {
            Eyebrow("历史反馈 · 最近 ${minOf(12, state.feedback.size)} 条")
            state.feedback.takeLast(12).reversed().forEach { entry ->
                PanelCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.date,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            buildString {
                                if (entry.isMorningCheckIn) append("早上打卡 · ")
                                append(
                                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                                        .format(java.util.Date(entry.timestamp))
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.isMorningCheckIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            entry.sleepQuality?.let { append("睡眠 $it　") }
                            entry.energy?.let { append("精力 $it　") }
                            entry.mood?.let { append("心情 $it　") }
                            entry.soreness?.let { append("酸痛 $it") }
                        }.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.symptoms.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "症状：${entry.symptoms.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            entry.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
