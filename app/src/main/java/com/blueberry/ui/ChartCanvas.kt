package com.blueberry.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blueberry.router.ChartKind
import com.blueberry.router.ChartSpec

/**
 * The canvas, drawn in Compose rather than a WebView.
 *
 * No untrusted HTML, no sandbox to reason about, no WebView cold start, and it themes with the rest
 * of the app for free. The model never emits markup — it picks one of a small fixed set of shapes
 * and supplies the numbers, and this owns the rendering, so a malformed visual is not a state the
 * app can reach.
 */
@Composable
fun ChartCanvas(spec: ChartSpec, modifier: Modifier = Modifier) {
    val values = spec.series.firstOrNull()?.values.orEmpty()
    if (values.isEmpty()) return

    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (spec.kind) {
            ChartKind.LINE, ChartKind.TIMELINE -> LinePlot(values, accent)
            ChartKind.TABLE, ChartKind.STEPS, ChartKind.GRAPH -> Rows(spec, accent, muted)
            ChartKind.BAR -> BarPlot(values, accent)
        }

        if (spec.kind == ChartKind.BAR || spec.kind == ChartKind.LINE || spec.kind == ChartKind.TIMELINE) {
            AxisLabels(spec, muted)
        }
    }
}

@Composable
private fun BarPlot(values: List<Double>, accent: Color) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(vertical = 4.dp),
    ) {
        val gap = size.width / (values.size * 5f).coerceAtLeast(1f)
        val barWidth = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            val h = (value / max).toFloat().coerceIn(0f, 1f) * size.height
            drawRoundRect(
                color = accent,
                topLeft = Offset(index * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 4f, barWidth / 4f),
                alpha = 0.55f + 0.45f * (value / max).toFloat(),
            )
        }
    }
}

@Composable
private fun LinePlot(values: List<Double>, accent: Color) {
    val max = values.maxOrNull() ?: 1.0
    val min = values.minOrNull() ?: 0.0
    val span = (max - min).takeIf { it > 0 } ?: 1.0

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(vertical = 4.dp),
    ) {
        if (values.size == 1) {
            drawCircle(accent, radius = 6.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
            return@Canvas
        }
        val stepX = size.width / (values.size - 1)
        fun pointAt(i: Int) = Offset(
            x = i * stepX,
            y = size.height - ((values[i] - min) / span).toFloat() * size.height,
        )

        val path = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
        for (i in values.indices) drawCircle(accent, radius = 3.dp.toPx(), center = pointAt(i))
    }
}

/** Table, steps and node-graph all degrade to a labelled list — legible beats decorative. */
@Composable
private fun Rows(spec: ChartSpec, accent: Color, muted: Color) {
    val values = spec.series.firstOrNull()?.values.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { index, value ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = spec.labels.getOrNull(index) ?: "${index + 1}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = format(value),
                    style = MaterialTheme.typography.bodyLarge,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun AxisLabels(spec: ChartSpec, muted: Color) {
    if (spec.labels.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (label in spec.labels.take(6)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun format(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.1f", value)
