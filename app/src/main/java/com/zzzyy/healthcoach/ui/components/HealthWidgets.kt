package com.zzzyy.healthcoach.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzzyy.healthcoach.data.model.SleepStage
import com.zzzyy.healthcoach.data.model.SleepStageKind
import com.zzzyy.healthcoach.ui.MetricSeries
import com.zzzyy.healthcoach.ui.theme.HealthColors
import kotlin.math.roundToInt

/**
 * 通用小部件。
 *
 * 全部是「测量仪器」语汇：描边面板、分段选择器、刻度条、偏离标尺。
 * 刻意不用 Material 的默认 Card / Chip —— 那些是给普通 app 用的，
 * 这里每个控件都应该看起来像仪表盘上的一格。
 */

/** 带描边的面板：整个 app 的基本容器。 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .border(
                width = 1.dp,
                color = accent ?: MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(16.dp),
        content = content,
    )
}

/** 小节标题：左边是丝印小字，右边可以挂一句说明。 */
@Composable
fun SectionHeader(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(title)
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 分段选择器：像仪器上的档位拨钮，不是 Material 的 Chip。 */
@Composable
fun SegmentedSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                MaterialTheme.shapes.small,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 横向滚动的分段选择器。
 *
 * 指标一多（活动 4 个 + 睡眠 4 个）就没法等分一行，
 * 与其把字缩小，不如让它横向滚 —— 仪器面板上档位多的时候也是这么干的。
 */
@Composable
fun ScrollableSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
    ) {
        items(options.size) { index ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(
                    options[index],
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 可点开的标签（症状、状态）。 */
@Composable
fun ToggleTag(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.outline,
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 1~5 刻度条。再点一次当前值 = 取消。 */
@Composable
fun ScaleRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                scaleLabel(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { score ->
                val filled = score <= value
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            width = 1.dp,
                            color = if (filled) Color.Transparent else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onChange(if (score == value) 0 else score) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$score",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (filled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun scaleLabel(value: Int): String = when (value) {
    0 -> "未记录"
    1 -> "很差"
    2 -> "偏差"
    3 -> "一般"
    4 -> "不错"
    else -> "很好"
}

/** 一个读数格：上面丝印字，下面等宽数字。 */
@Composable
fun StatCell(
    label: String,
    value: String,
    unit: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Eyebrow(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            unit?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    " $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 偏离标尺：一条轨道，左端 = 这段时间的最低，右端 = 最高。
 *
 * 竖线是**你的基线**，圆点是**最新一天**。
 * 圆点在线左边 = 今天低于常态，在右边 = 高于常态。
 * 一眼看出「今天是不是不一样」，而不是去比具体数字。
 */
@Composable
fun DeviationBar(
    series: MetricSeries,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val values = series.values
    if (values.size < 2) {
        Spacer(modifier.height(22.dp))
        return
    }
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0.0001f } ?: 1f
    val latest = series.latest
    val baseline = series.baseline
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier
            .fillMaxWidth()
            .height(22.dp)
    ) {
        val y = size.height / 2f
        drawLine(
            color = trackColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        baseline?.let { base ->
            val x = (((base - min) / span).coerceIn(0f, 1f)) * size.width
            drawLine(
                color = baselineColor.copy(alpha = 0.6f),
                start = Offset(x, 1.dp.toPx()),
                end = Offset(x, size.height - 1.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        latest?.let { value ->
            val x = (((value - min) / span).coerceIn(0f, 1f)) * size.width
            drawCircle(color = color.copy(alpha = 0.22f), radius = 6.dp.toPx(), center = Offset(x, y))
            drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
}

/**
 * 睡眠结构条：一整夜的分期时间轴。
 *
 * 左边是入睡，右边是起床，每一段按真实时长比例上色。
 * 一眼能看出「深睡集中在哪个时段」「REM 有没有出现」「夜里醒了几次」——
 * 这些是只给一个「睡了 8.7 小时」数字完全传达不到的信息。
 */
@Composable
fun SleepStageTimeline(
    stages: List<SleepStage>,
    startMillis: Long,
    endMillis: Long,
    modifier: Modifier = Modifier,
) {
    if (stages.isEmpty() || endMillis <= startMillis) {
        Spacer(modifier.height(34.dp))
        return
    }
    val total = (endMillis - startMillis).toFloat().coerceAtLeast(1f)
    val deep = HealthColors.Sleep
    val light = HealthColors.Sleep.copy(alpha = 0.45f)
    val rem = HealthColors.Signal
    val awake = HealthColors.Amber
    val unknown = MaterialTheme.colorScheme.outlineVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            // 先铺一条底（未覆盖的时段按「未分类」处理）
            drawRect(
                color = unknown,
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height),
            )
            stages.forEach { stage ->
                val left = ((stage.startMillis - startMillis) / total).coerceIn(0f, 1f) * size.width
                val right = ((stage.endMillis - startMillis) / total).coerceIn(0f, 1f) * size.width
                val width = (right - left).coerceAtLeast(0.6f)
                val color = when (stage.kind) {
                    SleepStageKind.DEEP -> deep
                    SleepStageKind.LIGHT -> light
                    SleepStageKind.REM -> rem
                    SleepStageKind.AWAKE -> awake
                    SleepStageKind.UNKNOWN -> unknown
                }
                drawRect(
                    color = color,
                    topLeft = Offset(left, 0f),
                    size = androidx.compose.ui.geometry.Size(width, size.height),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatClock(startMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatClock(endMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 图例：深睡 / 浅睡 / REM / 清醒。 */
@Composable
fun SleepStageLegend(modifier: Modifier = Modifier) {
    val items = listOf(
        Triple("深睡", HealthColors.Sleep, Unit),
        Triple("浅睡", HealthColors.Sleep.copy(alpha = 0.45f), Unit),
        Triple("REM", HealthColors.Signal, Unit),
        Triple("清醒", HealthColors.Amber, Unit),
    )
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.forEach { (label, color, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 10.dp, height = 10.dp)
                        .background(color, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一行「标签 + 数值」，用于睡眠结构明细。 */
@Composable
fun MiniStatRow(
    label: String,
    value: String,
    accent: Color? = null,
    modifier: Modifier = Modifier,
    /** 占整夜的比例（%）；null 时不显示 */
    percent: Int? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            accent?.let {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 12.dp)
                        .background(it, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (percent != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(38.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/**
 * 睡眠结构占比条（横向堆叠）。
 *
 * 时间轴告诉你「什么时候睡的」，这条告诉你**「各占多少」** ——
 * 两个问题是不同的，只给时长（比如「深睡 77 分钟」）读者很难换算成比例。
 *
 * 宽度按分钟数分配（也就是真实比例），段足够宽时直接把百分比写在段里。
 */
@Composable
fun SleepCompositionBar(
    segments: List<SleepCompositionSegment>,
    totalMinutes: Int,
    modifier: Modifier = Modifier,
) {
    if (totalMinutes <= 0 || segments.none { it.minutes > 0 }) {
        Spacer(modifier.height(34.dp))
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(MaterialTheme.shapes.small),
    ) {
        segments.filter { it.minutes > 0 }.forEach { segment ->
            val percent = segment.minutes.toFloat() / totalMinutes * 100f
            Box(
                Modifier
                    .weight(segment.minutes.toFloat().coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(segment.color),
                contentAlignment = Alignment.Center,
            ) {
                // 太窄的段写字会挤成一坨，只留颜色（图例里还有数字）
                if (percent >= 7f) {
                    Text(
                        "${percent.roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = segment.textColor,
                    )
                }
            }
        }
    }
}

/** [SleepCompositionBar] 的一段。 */
data class SleepCompositionSegment(
    val label: String,
    val minutes: Int,
    val color: Color,
    val textColor: Color,
)

private fun formatClock(millis: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(millis))
