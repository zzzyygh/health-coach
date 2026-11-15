package com.zzzyy.healthcoach.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 「康伴」的设计基调：**测量仪器**，不是健身 app。
 *
 * 刻意避开的三件事：
 *   - 不给身体打分、不发徽章、不做连续打卡（那是别的 app 的语法）
 *   - 不用「人群平均」当标准，只跟**自己的 30 天基线**比
 *   - 不用近黑底 + 单一荧光色的那套「科技感」默认皮肤
 *
 * 配色是冷调测量仪：冷白纸面 + 墨绿字 + 信号青，
 * 琥珀表示「值得留意」，砖红只留给真正异常。
 */
object HealthColors {
    // 纸面
    val Paper = Color(0xFFF4F6F5)
    val PaperDark = Color(0xFF101615)
    val Card = Color(0xFFFFFFFF)
    val CardDark = Color(0xFF18211F)
    val Divider = Color(0xFFDDE3E1)
    val DividerDark = Color(0xFF2A3634)

    // 文字
    val Ink = Color(0xFF12211F)
    val InkDark = Color(0xFFE4EBE9)
    val InkMuted = Color(0xFF66736F)
    val InkMutedDark = Color(0xFF9AA8A4)

    // 信号（主色：生物信号的青）
    val Signal = Color(0xFF0F8A7E)
    val SignalSoft = Color(0xFFD8F0EC)
    val SignalDark = Color(0xFF4FD1C0)

    // 睡眠（靛蓝，与青区分开）
    val Sleep = Color(0xFF3D5BD1)
    val SleepSoft = Color(0xFFE0E5FA)

    // 状态
    val Amber = Color(0xFFB87415)
    val AmberSoft = Color(0xFFFBEEDA)
    val Brick = Color(0xFFBC4038)
    val BrickSoft = Color(0xFFFAE4E2)
}

private val LightColors = lightColorScheme(
    primary = HealthColors.Signal,
    onPrimary = Color.White,
    primaryContainer = HealthColors.SignalSoft,
    onPrimaryContainer = Color(0xFF05372F),
    secondary = HealthColors.Sleep,
    onSecondary = Color.White,
    secondaryContainer = HealthColors.SleepSoft,
    onSecondaryContainer = Color(0xFF141F52),
    tertiary = HealthColors.Amber,
    onTertiary = Color.White,
    tertiaryContainer = HealthColors.AmberSoft,
    onTertiaryContainer = Color(0xFF4A2E05),
    error = HealthColors.Brick,
    onError = Color.White,
    errorContainer = HealthColors.BrickSoft,
    onErrorContainer = Color(0xFF4E110D),
    background = HealthColors.Paper,
    onBackground = HealthColors.Ink,
    surface = HealthColors.Card,
    onSurface = HealthColors.Ink,
    surfaceVariant = Color(0xFFEDF1EF),
    onSurfaceVariant = HealthColors.InkMuted,
    outline = HealthColors.Divider,
    outlineVariant = Color(0xFFE8EDEB),
)

private val DarkColors = darkColorScheme(
    primary = HealthColors.SignalDark,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0B4A43),
    onPrimaryContainer = HealthColors.SignalSoft,
    secondary = Color(0xFF9DB1FF),
    onSecondary = Color(0xFF14204F),
    secondaryContainer = Color(0xFF27356B),
    onSecondaryContainer = HealthColors.SleepSoft,
    tertiary = Color(0xFFE0A85A),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF5E3D08),
    onTertiaryContainer = HealthColors.AmberSoft,
    error = Color(0xFFEF8B84),
    onError = Color(0xFF561512),
    errorContainer = Color(0xFF6D2521),
    onErrorContainer = HealthColors.BrickSoft,
    background = HealthColors.PaperDark,
    onBackground = HealthColors.InkDark,
    surface = HealthColors.CardDark,
    onSurface = HealthColors.InkDark,
    surfaceVariant = Color(0xFF212B29),
    onSurfaceVariant = HealthColors.InkMutedDark,
    outline = HealthColors.DividerDark,
    outlineVariant = Color(0xFF303B39),
)

/**
 * 字体：**数据用等宽，标签用无衬线**。
 *
 * 这是「仪器感」的来源 —— 跳动的数字如果用比例字体，
 * 每次刷新宽度都会变，读起来像心电图而不是仪表读数。
 */
private val HealthTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Light,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    // 指标名、单位这类小标签：字距拉开一点，像仪表盘上的丝印字
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp,
        letterSpacing = 0.8.sp,
    ),
)

private val HealthShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun HealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = HealthTypography,
        shapes = HealthShapes,
        content = content,
    )
}
