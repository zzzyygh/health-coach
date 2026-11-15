package com.zzzyy.healthcoach.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zzzyy.healthcoach.data.advice.ModelConfig
import com.zzzyy.healthcoach.ui.HealthViewModel
import com.zzzyy.healthcoach.ui.UiState
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.components.PanelCard
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.text.font.FontFamily
import com.zzzyy.healthcoach.data.analysis.SleepScorer
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import com.zzzyy.healthcoach.data.advice.ModelPreset
import androidx.compose.material3.Switch
import com.zzzyy.healthcoach.data.backup.WebDavConfig
import com.zzzyy.healthcoach.ui.theme.HealthColors

/**
 * 设置。
 *
 * ⚠️ API Key 只能由用户**在手机上手动填写** ——
 * 绝不能通过 adb 命令行传入（adb 命令会进 logcat，等于把密钥写在日志里）。
 */
@Composable
fun SettingsDialog(
    state: UiState,
    viewModel: HealthViewModel,
    onDismiss: () -> Unit,
) {
    // 从已存的配置初始化，而不是写死默认值 —— 否则切预设时之前填的 Key 会丢
    val context = LocalContext.current
    val store = remember { com.zzzyy.healthcoach.data.advice.ModelConfigStore(context) }
    var preset by remember { mutableStateOf(ModelPreset.ofId(state.modelPresetId)) }
    var baseUrl by remember { mutableStateOf(state.modelBaseUrl) }
    var model by remember { mutableStateOf(state.modelName) }
    var apiKey by remember { mutableStateOf(state.modelApiKey) }

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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("设置", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Eyebrow("模型 API")
                // 预设：点一下就把 Base URL 与模型名填好，只需再填 Key
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModelPreset.entries.forEach { item ->
                        val selected = preset == item
                        Box(
                            Modifier
                                .weight(1f)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    MaterialTheme.shapes.small,
                                )
                                .border(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .clickable {
                                    preset = item
                                    if (item != ModelPreset.CUSTOM) {
                                        baseUrl = item.baseUrl
                                        model = item.model
                                    }
                                    // 换服务商就把 Key 空掉，避免把上一家的 Key 发给这一家
                                    apiKey = ""
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    preset.hint.ifBlank { "填任意 Anthropic 兼容端点" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                Text(
                    "密钥只保存在这台手机里，不会上传到除模型端点以外的任何地方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = {
                            viewModel.saveModelConfig(
                                ModelConfig(
                                    presetId = preset.id,
                                    baseUrl = baseUrl.trim(),
                                    model = model.trim(),
                                    apiKey = apiKey.trim(),
                                )
                            )
                            onDismiss()
                        },
                    ) {
                        Text("保存模型配置")
                    }
                }

                SleepGoalSection(state, viewModel)

                WebDavSection(state, viewModel)

                Eyebrow("小米账号")
                if (state.tokenUsable) {
                    Text(
                        "已登录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { viewModel.refreshTokenWithPassToken() }) {
                        Text("用已保存的 passToken 续期")
                    }
                } else {
                    Text(
                        "未登录。不登录也能用 —— 手机有 root 时走本机数据库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.startQrLogin() }) {
                    Text("获取登录二维码")
                }
                state.qrBitmap?.let { bitmap ->
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier.size(180.dp),
                    )
                }
                if (state.qrImageUrl != null) {
                    Text(
                        "正在等待扫码…",
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
        }
    }
}

/**
 * 作息目标设置。
 *
 * 这一项直接决定睡眠评分里「节律」那 10 分怎么算 ——
 * 用你定的目标当锚点，而不是设备推断的习惯（否则你越改越早，基线也跟着变，分数永远不动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepGoalSection(state: UiState, viewModel: HealthViewModel) {
    var showBedPicker by remember { mutableStateOf(false) }
    var showWakePicker by remember { mutableStateOf(false) }
    val goal = state.sleepGoal

    Eyebrow("作息目标（用于评分）")
    Text(
        if (goal.isSet) "评分里的「节律」维度对比这两个时间"
        else "还没设定：目前用你最近 30 天的实际习惯当参照",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    GoalTimeRow(
        label = "目标入睡",
        minutes = goal.bedtimeMinutes,
        onPick = { showBedPicker = true },
    )
    GoalTimeRow(
        label = "目标起床",
        minutes = goal.wakeMinutes,
        onPick = { showWakePicker = true },
    )

    if (showBedPicker) {
        val initial = goal.bedtimeMinutes ?: DEFAULT_BEDTIME_MINUTES
        val pickerState = rememberTimePickerState(
            initialHour = ((20 * 60 + initial) / 60) % 24,
            initialMinute = (20 * 60 + initial) % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showBedPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = minutesFrom20(pickerState.hour, pickerState.minute)
                    viewModel.saveSleepGoal(goal.copy(bedtimeMinutes = minutes))
                    showBedPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showBedPicker = false }) { Text("取消") } },
            text = { TimePicker(state = pickerState) },
        )
    }

    if (showWakePicker) {
        val initial = goal.wakeMinutes ?: DEFAULT_WAKE_MINUTES
        val pickerState = rememberTimePickerState(
            initialHour = ((20 * 60 + initial) / 60) % 24,
            initialMinute = (20 * 60 + initial) % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showWakePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = minutesFrom20(pickerState.hour, pickerState.minute)
                    viewModel.saveSleepGoal(goal.copy(wakeMinutes = minutes))
                    showWakePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showWakePicker = false }) { Text("取消") } },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@Composable
private fun GoalTimeRow(label: String, minutes: Int?, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                minutes?.let { SleepScorer.fmt(it) } ?: "未设定",
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (minutes == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "修改 ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** HH:mm → 从 20:00 起算的分钟数。 */
private fun minutesFrom20(hour: Int, minute: Int): Int {
    var total = hour * 60 + minute
    if (total < 20 * 60) total += 24 * 60
    return total - 20 * 60
}

private const val DEFAULT_BEDTIME_MINUTES = 4 * 60 + 60   // 01:00（从 20:00 起算 300 分钟）
private const val DEFAULT_WAKE_MINUTES = 11 * 60 + 60     // 08:00（从 20:00 起算 720 分钟）

/**
 * WebDAV 备份设置。
 *
 * 用 WebDAV 而不是自建服务：群晖 / 威联通 / Nextcloud / 坚果云都自带，
 * 填地址和账号就能用，不需要在 NAS 上跑任何东西。
 */
@Composable
fun WebDavSection(state: UiState, viewModel: HealthViewModel) {
    var baseUrl by remember { mutableStateOf(state.webDav.baseUrl) }
    var username by remember { mutableStateOf(state.webDav.username) }
    var password by remember { mutableStateOf(state.webDav.password) }
    var folder by remember { mutableStateOf(state.webDav.folder) }
    var autoBackup by remember { mutableStateOf(state.webDav.autoBackup) }

    Eyebrow("数据备份（WebDAV）")
    Text(
        "每天第一次打开时自动把日聚合数据备份到你的 NAS；不含逐条心率明细。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text("服务地址") },
        placeholder = { Text("https://nas.example.com:5006/health") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
        )
    }
    OutlinedTextField(
        value = folder,
        onValueChange = { folder = it },
        label = { Text("子目录（会自动创建）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = autoBackup, onCheckedChange = { autoBackup = it })
        Spacer(Modifier.width(8.dp))
        Text("每天第一次打开时自动备份", style = MaterialTheme.typography.bodyMedium)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                viewModel.saveWebDavConfig(
                    WebDavConfig(
                        baseUrl = baseUrl.trim(),
                        username = username.trim(),
                        password = password,
                        folder = folder.trim(),
                        autoBackup = autoBackup,
                    )
                )
            },
        ) { Text("保存配置") }
        TextButton(
            onClick = {
                viewModel.saveWebDavConfig(
                    WebDavConfig(
                        baseUrl = baseUrl.trim(),
                        username = username.trim(),
                        password = password,
                        folder = folder.trim(),
                        autoBackup = autoBackup,
                    )
                )
                viewModel.testWebDavConnection()
            },
        ) { Text("测试连接") }
        TextButton(
            onClick = {
                viewModel.saveWebDavConfig(
                    WebDavConfig(
                        baseUrl = baseUrl.trim(),
                        username = username.trim(),
                        password = password,
                        folder = folder.trim(),
                        autoBackup = autoBackup,
                    )
                )
                viewModel.backupNow()
            },
            enabled = !state.backingUp,
        ) { Text(if (state.backingUp) "备份中…" else "立即备份") }
    }

    state.backupStatus?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = if (it.contains("失败")) HealthColors.Brick else MaterialTheme.colorScheme.primary,
        )
    }
}
