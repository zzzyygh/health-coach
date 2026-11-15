package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zzzyy.healthcoach.data.model.InterventionEntry
import com.zzzyy.healthcoach.data.model.InterventionPresets
import com.zzzyy.healthcoach.ui.HealthViewModel
import com.zzzyy.healthcoach.ui.UiState
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.PanelCard
import com.zzzyy.healthcoach.ui.theme.HealthColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 干预记录：今天吃了什么、做了什么。
 *
 * 手环测的是结果（睡眠、心率、恢复），测不到原因。
 * 补充剂、咖啡、酒精、运动才是可调的变量 —— 记下来才可能看出因果关系。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InterventionSection(state: UiState, viewModel: HealthViewModel, today: String) {
    val todayItems = state.interventions.filter { it.date == today }
    var editing by remember { mutableStateOf<InterventionPresets.Preset?>(null) }
    var customOpen by remember { mutableStateOf(false) }

    PanelCard {
        Eyebrow("今天做了什么 / 吃了什么")
        Spacer(Modifier.height(4.dp))
        Text(
            "记录补充剂和习惯，才能看出它们和睡眠、精力的关系。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // 快捷标签：点一下预填剂量
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InterventionPresets.ALL.forEach { preset ->
                val taken = todayItems.any { it.name == preset.name }
                Box(
                    Modifier
                        .background(
                            if (taken) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            MaterialTheme.shapes.small,
                        )
                        .border(
                            1.dp,
                            if (taken) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outline,
                            MaterialTheme.shapes.small,
                        )
                        .clickable { editing = preset }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (taken) "✓ ${preset.name}" else preset.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (taken) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Box(
                Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .clickable { customOpen = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "＋ 自定义",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (todayItems.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Eyebrow("今天的记录")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                todayItems.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(14.dp)
                                    .background(HealthColors.Signal, MaterialTheme.shapes.extraSmall)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.display,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            entry.note.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { viewModel.deleteIntervention(entry) }) {
                            Text("×", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }

        // 观察窗口提示：补充剂的效果以周计，别几天没变化就放弃
        if (todayItems.isNotEmpty()) {
            val preset = todayItems.firstNotNullOfOrNull {
                InterventionPresets.presetOf(it.name)
            }
            preset?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "「${it.name}」通常需要连续 ${it.observeWeeks} 周左右才看得出趋势，别几天没变化就停。",
                    style = MaterialTheme.typography.labelSmall,
                    color = HealthColors.Amber,
                )
            }
        }
    }

    // 记录编辑弹窗
    (editing?.let { it } ?: null)?.let { preset ->
        InterventionEditDialog(
            initialName = preset.name,
            initialDose = preset.defaultDose,
            initialUnit = preset.unit,
            onDismiss = { editing = null },
            onConfirm = { name, dose, unit, note ->
                viewModel.addIntervention(
                    InterventionEntry(
                        date = today,
                        name = name,
                        dose = dose,
                        unit = unit,
                        note = note,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                editing = null
            },
        )
    }

    if (customOpen) {
        InterventionEditDialog(
            initialName = "",
            initialDose = null,
            initialUnit = "",
            onDismiss = { customOpen = false },
            onConfirm = { name, dose, unit, note ->
                if (name.isNotBlank()) {
                    viewModel.addIntervention(
                        InterventionEntry(
                            date = today,
                            name = name,
                            dose = dose,
                            unit = unit,
                            note = note,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                }
                customOpen = false
            },
        )
    }
}

@Composable
private fun InterventionEditDialog(
    initialName: String,
    initialDose: Double?,
    initialUnit: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Double?, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var doseText by remember {
        mutableStateOf(initialDose?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "")
    }
    var unit by remember { mutableStateOf(initialUnit) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isBlank()) "自定义干预" else "记录 $initialName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = doseText,
                        onValueChange = { doseText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("剂量") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("单位") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f),
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim(),
                        doseText.trim().toDoubleOrNull(),
                        unit.trim(),
                        note.trim(),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("记录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
