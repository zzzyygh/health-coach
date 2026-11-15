package com.zzzyy.healthcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzzyy.healthcoach.ui.components.Eyebrow
import com.zzzyy.healthcoach.ui.screens.AdviceScreen
import com.zzzyy.healthcoach.ui.screens.FeedbackScreen
import com.zzzyy.healthcoach.ui.screens.MorningCheckInDialog
import com.zzzyy.healthcoach.ui.screens.OverviewScreen
import com.zzzyy.healthcoach.ui.screens.SettingsDialog
import com.zzzyy.healthcoach.ui.screens.TrendScreen

private enum class AppTab(val label: String) {
    OVERVIEW("概览"), TREND("趋势"), ADVICE("建议"), FEEDBACK("反馈"),
}

/**
 * 应用外壳。
 *
 * 顶栏和底栏都**不用 Material 默认组件**：
 * 底栏改成「刻度标签 + 顶部指示条」，像仪器面板上的功能档位，
 * 而不是 Android 12 那套胶囊按钮。
 */
@Composable
fun HealthCoachApp(viewModel: HealthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(AppTab.OVERVIEW) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 状态栏/导航栏内边距由顶栏、底栏自己处理，避免重复计算
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            HealthTopBar(onSettings = { showSettings = true })
        },
        bottomBar = {
            HealthBottomBar(current = tab, onSelect = { tab = it })
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (tab) {
                AppTab.OVERVIEW -> OverviewScreen(state, viewModel)
                AppTab.TREND -> TrendScreen(state)
                AppTab.ADVICE -> AdviceScreen(state, viewModel)
                AppTab.FEEDBACK -> FeedbackScreen(state, viewModel)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(state, viewModel) { showSettings = false }
    }

    // 每天第一次打开时的睡醒打卡
    if (state.showMorningCheckIn) {
        MorningCheckInDialog(state, viewModel)
    }
}

@Composable
private fun HealthTopBar(onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Eyebrow("个人基线仪表")
                Spacer(Modifier.height(2.dp))
                Text(
                    "康伴",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(onClick = onSettings) {
                Text("设置")
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

@Composable
private fun HealthBottomBar(current: AppTab, onSelect: (AppTab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AppTab.entries.forEach { entry ->
                val active = entry == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Box(
                        Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
