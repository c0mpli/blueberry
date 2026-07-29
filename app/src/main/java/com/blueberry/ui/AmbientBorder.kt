package com.blueberry.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * The glow that runs around the edge of the screen while Blueberry is engaged — the Apple
 * Intelligence / Siri treatment.
 *
 * Two reasons it earns its place rather than being decoration. It is visible in peripheral vision,
 * so you know the phone is listening without looking directly at the middle of the screen. And it
 * scales with your voice, which makes "it can hear me" legible from across a room.
 *
 * Implemented as a rotating sweep gradient stroked around a rounded rectangle, drawn several times
 * at increasing width and falling alpha. That layering is a cheap fake for a blur: `Modifier.blur`
 * needs API 31 and a RenderEffect pass over the whole screen every frame, which on a launcher's
 * idle surface is a real battery cost for a glow.
 */
@Composable
fun AmbientBorder(
    state: VoiceVisualState,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "border")

    // The gradient rotates continuously. Slower while thinking, so the motion reads as "working"
    // rather than "listening hard".
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (state == VoiceVisualState.THINKING) 5200 else 3400)
        ),
        label = "spin",
    )

    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800)),
        label = "breathe",
    )

    // Idle shows nothing at all. The border appearing is itself the signal that the turn started.
    val target = when (state) {
        VoiceVisualState.IDLE -> 0f
        VoiceVisualState.LISTENING -> 0.42f + 0.58f * level.coerceIn(0f, 1f)
        VoiceVisualState.THINKING -> 0.34f + 0.16f * breathe
        VoiceVisualState.SPEAKING -> 0.50f + 0.22f * breathe
    }
    val intensity by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (state == VoiceVisualState.IDLE) 420 else 140),
        label = "intensity",
    )

    if (intensity <= 0.01f) return

    Canvas(modifier.fillMaxSize()) {
        val corner = CornerRadius(CORNER_DP.dp.toPx(), CORNER_DP.dp.toPx())

        // The *gradient* rotates; the rectangle never does. Wrapping the draw in rotate() instead
        // spins the whole frame and the border sweeps across the screen as a tilted box rather
        // than hugging the edge.
        val brush = rotatingSweep(spin / 360f, center)

        // Widest and faintest first, so the layers build into a soft falloff rather than reading
        // as a set of concentric lines.
        for (layer in GLOW_LAYERS downTo 1) {
            val width = (BASE_WIDTH_DP * layer * (0.6f + 0.9f * intensity)).dp.toPx()
            drawRoundRect(
                brush = brush,
                topLeft = Offset(width / 2f, width / 2f),
                size = Size(size.width - width, size.height - width),
                cornerRadius = corner,
                style = Stroke(width = width),
                alpha = (intensity * (0.30f / layer)).coerceIn(0f, 1f),
            )
        }

        // A final crisp pass so the edge reads as a defined line and not only a haze.
        val w = (BASE_WIDTH_DP * (0.8f + 0.7f * intensity)).dp.toPx()
        drawRoundRect(
            brush = brush,
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(size.width - w, size.height - w),
            cornerRadius = corner,
            style = Stroke(width = w),
            alpha = (0.65f * intensity).coerceIn(0f, 1f),
        )
    }
}

/**
 * A sweep gradient whose colours are offset by [phase] turns.
 *
 * Compose has no way to spin a `sweepGradient` in place, so the rotation is baked into the stops:
 * the looped palette is sampled at fixed angular positions with the phase added, which moves the
 * colours around a stationary ring. [SIRI_COLORS] repeats its first colour at the end, so the
 * sample wraps without a visible seam chasing itself around the screen.
 */
private fun rotatingSweep(phase: Float, center: Offset): Brush {
    val stops = Array(SAMPLES + 1) { i ->
        val t = i.toFloat() / SAMPLES
        t to sampleLoop(t + phase)
    }
    return Brush.sweepGradient(colorStops = stops, center = center)
}

private fun sampleLoop(position: Float): Color {
    val wrapped = position - floor(position)
    val scaled = wrapped * (SIRI_COLORS.size - 1)
    val index = floor(scaled).toInt().coerceIn(0, SIRI_COLORS.size - 2)
    return lerp(SIRI_COLORS[index], SIRI_COLORS[index + 1], scaled - index)
}

/**
 * The sweep repeats the first colour at the end so the seam where the gradient wraps is invisible
 * as it rotates — without that there is a hard edge chasing itself around the screen.
 */
private val SIRI_COLORS = listOf(
    Color(0xFFFF5F6D), // coral
    Color(0xFFFF9966), // amber
    Color(0xFFC66BF0), // orchid
    Color(0xFF6A8DFF), // periwinkle
    Color(0xFF39D3F5), // cyan
    Color(0xFFC66BF0), // orchid again, for a longer purple arc
    Color(0xFFFF5F6D), // back to coral: closes the loop seamlessly
)

private const val CORNER_DP = 46f
private const val BASE_WIDTH_DP = 5f
private const val GLOW_LAYERS = 4

/** Angular resolution of the rotating sweep. 24 is smooth to the eye and costs nothing. */
private const val SAMPLES = 24
