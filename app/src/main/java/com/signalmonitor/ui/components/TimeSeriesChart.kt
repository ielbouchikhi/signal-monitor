package com.signalmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChartPoint(val timestampMs: Long, val value: Float)

/**
 * Minimal Compose Canvas time-series line chart.
 *
 * - Null gaps are respected: line segments are broken where data is absent.
 * - [secondarySeries] renders as a dotted overlay (used for active probe vs passive).
 * - Axis labels auto-scale to the data range.
 */
@Composable
fun TimeSeriesChart(
    points: List<ChartPoint?>,          // null = gap in data
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    secondarySeries: List<ChartPoint?> = emptyList(),
    secondaryColor: Color = Color(0xFFEF9A9A),
    yLabel: String = "",
    yMin: Float? = null,
    yMax: Float? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Color.Gray)

    val nonNull = remember(points) { points.filterNotNull() }
    val allValues = remember(points, secondarySeries) {
        (points + secondarySeries).filterNotNull().map { it.value }
    }

    if (nonNull.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
        return
    }

    val dataYMin = yMin ?: (allValues.minOrNull() ?: 0f)
    val dataYMax = yMax ?: (allValues.maxOrNull() ?: 1f)
    val yRange = (dataYMax - dataYMin).takeIf { it > 0f } ?: 1f

    val timeMin = nonNull.minOf { it.timestampMs }.toFloat()
    val timeMax = nonNull.maxOf { it.timestampMs }.toFloat()
    val timeRange = (timeMax - timeMin).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier.padding(start = 32.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
        val w = size.width
        val h = size.height

        fun xOf(ts: Long) = ((ts - timeMin) / timeRange) * w
        fun yOf(v: Float) = h - ((v - dataYMin) / yRange) * h

        // Grid lines (3 horizontal)
        val gridPaint = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        for (i in 0..2) {
            val y = h * i / 2f
            drawLine(
                color = Color.Gray.copy(alpha = 0.2f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
                pathEffect = gridPaint,
            )
            val value = dataYMax - (yRange * i / 2f)
            drawText(
                textMeasurer,
                text = formatAxisLabel(value),
                topLeft = Offset(-30.dp.toPx(), y - 6.dp.toPx()),
                style = labelStyle,
            )
        }

        // Primary series
        drawSeries(points, ::xOf, ::yOf, lineColor, dashed = false)

        // Secondary series (active probe overlay)
        if (secondarySeries.isNotEmpty()) {
            drawSeries(secondarySeries, ::xOf, ::yOf, secondaryColor, dashed = true)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    points: List<ChartPoint?>,
    xOf: (Long) -> Float,
    yOf: (Float) -> Float,
    color: Color,
    dashed: Boolean,
) {
    val pathEffect = if (dashed)
        PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
    else null

    var path: Path? = null

    for (point in points) {
        if (point == null) {
            path?.let {
                drawPath(it, color = color, style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round,
                    pathEffect = pathEffect,
                ))
            }
            path = null
            continue
        }
        val x = xOf(point.timestampMs)
        val y = yOf(point.value)
        if (path == null) {
            path = Path().apply { moveTo(x, y) }
        } else {
            path.lineTo(x, y)
        }
    }
    path?.let {
        drawPath(it, color = color, style = Stroke(
            width = 2.5f,
            cap = StrokeCap.Round,
            pathEffect = pathEffect,
        ))
    }
}

private fun formatAxisLabel(value: Float): String = when {
    value >= 1000 -> "${"%.0f".format(value / 1000)}k"
    value == kotlin.math.floor(value.toDouble()).toFloat() -> value.toInt().toString()
    else -> "%.1f".format(value)
}
