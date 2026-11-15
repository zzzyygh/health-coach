package com.zzzyy.healthcoach.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 签名组件：**基线带折线**。
 *
 * 一条 14 天的曲线 + 一条「你自己的 30 天基线」的参考带。
 * 看一眼就知道：今天比自己的常态高了还是低了。
 *
 * 刻意**不画人群平均值、不画目标线、不打分** ——
 * 「比昨天多走 2000 步」这种信息没有意义，
 * 「比自己的基线低 18%」才有意义。
 */
@Composable
fun BaselineSparkline(
    values: List<Float>,
    baseline: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    showBaselineBand: Boolean = true,
    /** 正常波动半径；null 时退回按基线 8% 估算 */
    band: Float? = null,
) {
    if (values.size < 2) {
        Box(modifier.height(36.dp))
        return
    }

    val minValue = values.min()
    val maxValue = values.max()
    val bandHalf = band
        ?: (baseline ?: 0f).takeIf { it > 0f }?.let { it * 0.08f }
        ?: 0f
    val lower = minOf(minValue, baseline ?: minValue) - bandHalf
    val upper = maxOf(maxValue, baseline ?: maxValue) + bandHalf
    val span = (upper - lower).takeIf { it > 0.0001f } ?: 1f

    Canvas(modifier) {
        val stepX = size.width / (values.size - 1)
        fun yOf(value: Float): Float =
            size.height - ((value - lower) / span) * size.height

        // 基线参考带：不是一条线，是一个「正常波动范围」
        if (showBaselineBand && baseline != null && baseline > 0f && bandHalf > 0f) {
            val top = yOf(baseline + bandHalf)
            val bottom = yOf(baseline - bandHalf)
            drawRect(
                color = color.copy(alpha = 0.10f),
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, (bottom - top).coerceAtLeast(1f)),
            )
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(0f, yOf(baseline)),
                end = Offset(size.width, yOf(baseline)),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // 曲线
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = yOf(value)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        // 当前值：一个实心点
        val lastX = size.width
        val lastY = yOf(values.last())
        drawCircle(color = color.copy(alpha = 0.25f), radius = 5.dp.toPx(), center = Offset(lastX, lastY))
        drawCircle(color = color, radius = 2.6.dp.toPx(), center = Offset(lastX, lastY))
    }
}

/**
 * 大图版本：趋势页 / 详情页主图。
 *
 * **可点击**：点图上任意位置（或左右拖动）会选中最近的那一天，
 * 并通过 [onSelect] 回调把索引交给调用方 —— 调用方把顶部的大数字
 * 换成被选中那天的值。只画一条线不给读数，等于让人凭眼睛估。
 *
 * X 轴标签按天数自动取 4~6 个（不是首/中/尾三个）：
 * 30 天的图只标三个日期，中间那 25 天是读不出来的。
 */
@Composable
fun BaselineLineChart(
    values: List<Float>,
    labels: List<String>,
    baseline: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    valueFormatter: (Float) -> String = { it.toInt().toString() },
    /** 正常波动半径；null 时退回按基线 8% 估算 */
    band: Float? = null,
    /** 当前选中的点（外部控制） */
    selectedIndex: Int? = null,
    /** 点击/拖动选中回调；为 null 时图不可交互 */
    onSelect: ((Int) -> Unit)? = null,
) {
    if (values.size < 2) {
        Box(modifier.height(180.dp))
        return
    }

    val minValue = values.min()
    val maxValue = values.max()
    val bandHalf = band
        ?: (baseline ?: 0f).takeIf { it > 0f }?.let { it * 0.08f }
        ?: 0f
    val lower = minOf(minValue, baseline ?: minValue) - bandHalf
    val upper = maxOf(maxValue, baseline ?: maxValue) + bandHalf
    val span = (upper - lower).takeIf { it > 0.0001f } ?: 1f
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    /** 把 X 坐标换算成最近的点的索引 */
    fun indexAt(x: Float, width: Float): Int {
        if (width <= 0f) return 0
        val step = width / (values.size - 1)
        return (x / step).roundToInt().coerceIn(0, values.size - 1)
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .then(
                    if (onSelect == null) Modifier
                    else Modifier.pointerInput(values.size) {
                        detectTapGestures { offset ->
                            onSelect(indexAt(offset.x, size.width.toFloat()))
                        }
                    }
                )
                .then(
                    if (onSelect == null) Modifier
                    else Modifier.pointerInput(values.size) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                onSelect(indexAt(offset.x, size.width.toFloat()))
                            }
                        ) { change, _ ->
                            onSelect(indexAt(change.position.x, size.width.toFloat()))
                        }
                    }
                )
        ) {
            val stepX = size.width / (values.size - 1)
            fun yOf(value: Float): Float = size.height - ((value - lower) / span) * size.height

            // 网格
            repeat(4) { i ->
                val y = size.height * i / 3f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            if (baseline != null && baseline > 0f && bandHalf > 0f) {
                val top = yOf(baseline + bandHalf)
                val bottom = yOf(baseline - bandHalf)
                drawRect(
                    color = color.copy(alpha = 0.09f),
                    topLeft = Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(size.width, (bottom - top).coerceAtLeast(1f)),
                )
                drawLine(
                    color = color.copy(alpha = 0.4f),
                    start = Offset(0f, yOf(baseline)),
                    end = Offset(size.width, yOf(baseline)),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val path = Path()
            values.forEachIndexed { index, value ->
                val x = index * stepX
                val y = yOf(value)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // 选中游标：竖线 + 高亮点（选中时才画）
            val focus = selectedIndex?.coerceIn(0, values.size - 1)
            if (focus != null) {
                val fx = focus * stepX
                val fy = yOf(values[focus])
                drawLine(
                    color = color.copy(alpha = 0.35f),
                    start = Offset(fx, 0f),
                    end = Offset(fx, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(color = color.copy(alpha = 0.22f), radius = 8.dp.toPx(), center = Offset(fx, fy))
                drawCircle(color = color, radius = 3.6.dp.toPx(), center = Offset(fx, fy))
            } else {
                val lastX = size.width
                val lastY = yOf(values.last())
                drawCircle(color = color.copy(alpha = 0.22f), radius = 7.dp.toPx(), center = Offset(lastX, lastY))
                drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
            }
        }

        // X 轴：按天数自动取 4~6 个刻度，而不是首/中/尾三个
        val tickCount = when {
            labels.size <= 7 -> labels.size
            labels.size <= 14 -> 4
            labels.size <= 30 -> 5
            else -> 6
        }
        val tickIndexes = if (tickCount <= 1) {
            listOf(0)
        } else {
            (0 until tickCount).map { i ->
                (i * (labels.size - 1).toFloat() / (tickCount - 1)).roundToInt()
            }.distinct()
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            tickIndexes.forEach { index ->
                Text(
                    labels.getOrNull(index)?.takeLast(5).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (baseline != null && baseline > 0f) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 2.dp)
                        .background(color.copy(alpha = 0.4f))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "你的基线 ${valueFormatter(baseline)}（阴影带是正常波动范围）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 小标签，像仪表盘上的丝印字。 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 指标卡：今日数值 + 与基线的偏移 + 迷你折线。
 *
 * 「与基线的偏移」用**颜色 + 方向箭头**，不用红绿好坏判断 ——
 * 静息心率比基线低是好事，步数比基线低是不是好事取决于你自己的目标。
 */
@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String,
    history: List<Float>,
    baseline: Float?,
    baselineLabel: String?,
    accent: Color,
    baselineBand: Float? = null,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    /** 点击整张卡片（打开该指标的逐日明细） */
    onClick: (() -> Unit)? = null,
) {
    val delta = if (baseline != null && baseline > 0f) {
        (history.lastOrNull()?.minus(baseline) ?: 0f) / baseline * 100f
    } else {
        null
    }

    Column(
        modifier
            .fillMaxWidth()
            .then(
                // 不用 Modifier.clickable：它在慢速拖动时仍可能被判定为点击，
                // 表现是「想滚动列表，却打开了卡片详情」。detectTapGestures 对手势位移更严格。
                if (onClick != null) Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { onClick() })
                } else Modifier
            )
            .background(
                if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surface,
                MaterialTheme.shapes.medium,
            )
            .border(
                width = if (highlight) 0.dp else 1.dp,
                color = if (highlight) Color.Transparent else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(label)
            if (delta != null && abs(delta) >= 1f) {
                Text(
                    "${if (delta > 0) "↑" else "↓"} ${kotlin.math.abs(delta).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                unit,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        baselineLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        BaselineSparkline(
            values = history,
            baseline = baseline,
            band = baselineBand,
            color = accent,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
        )

        if (onClick != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "逐日明细 ›",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
    }
}

/** 接入状态药丸。 */
@Composable
fun StatusPill(
    name: String,
    ok: Boolean,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(
                    if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape,
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 建议条目：序号 + 正文，像一份简短的病历条目而不是聊天气泡。 */
@Composable
fun AdviceItem(index: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
