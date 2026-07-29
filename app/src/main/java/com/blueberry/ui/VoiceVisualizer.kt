package com.blueberry.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The one thing on screen that has to answer "is it my turn?" without a caption.
 *
 * Four states, each with a motion signature you can read at a glance and out of the corner of your
 * eye — that separation is the whole point, because a single amplitude-reactive blob looks
 * identical whether it is waiting for you or busy ignoring you:
 *
 * | State     | Motion                                          | Reads as       |
 * |-----------|-------------------------------------------------|----------------|
 * | Idle      | five dots, slow synchronised breath             | dormant        |
 * | Listening | bars driven by your voice, centre-weighted      | *your turn*    |
 * | Thinking  | a light sweeping left to right, ignoring input  | *not your turn*|
 * | Speaking  | a standing wave, self-driven                    | its turn       |
 *
 * The distinction that matters most is Listening vs Thinking. Listening only ever moves because
 * *you* moved it, so silence collapses the bars to the floor and the surface visibly waits. Thinking
 * moves on its own at a constant rate and never responds to sound, so talking at it does nothing
 * visible — which is the honest signal, since talking at it does nothing at all.
 */
@Composable
fun VoiceVisualizer(
    state: VoiceVisualState,
    level: Float,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF9B8BF0),
    barCount: Int = 5,
) {
    val transition = rememberInfiniteTransition(label = "voice")

    // Slow shared breath, used by Idle and as a subtle wobble under Listening so the bars never
    // look frozen while you are mid-word.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400)),
        label = "breath",
    )

    // Sweep position for Thinking, in bar units. Runs past the ends so the light enters and leaves
    // rather than bouncing, which would read as reacting to something.
    val sweep by transition.animateFloat(
        initialValue = -1.4f,
        targetValue = barCount + 0.4f,
        animationSpec = infiniteRepeatable(tween(1100)),
        label = "sweep",
    )

    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900)),
        label = "wave",
    )

    // Springing the level does two jobs: it smooths the recogniser's very jumpy RMS, and it gives
    // the bars a little overshoot so speech feels like it is pushing something physical.
    val amplitude by animateFloatAsState(
        targetValue = if (state == VoiceVisualState.LISTENING) level.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "amplitude",
    )

    // Fade the whole thing down when dormant so Idle reads as "off" rather than "quiet".
    val presence by animateFloatAsState(
        targetValue = if (state == VoiceVisualState.IDLE) 0.45f else 1f,
        animationSpec = tween(320),
        label = "presence",
    )

    Canvas(modifier.size(width = 128.dp, height = 72.dp)) {
        val gap = size.width / (barCount * 2.2f)
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val minHeight = barWidth
        val maxHeight = size.height

        for (i in 0 until barCount) {
            val fraction = when (state) {
                VoiceVisualState.IDLE -> idleFraction(breath)
                VoiceVisualState.LISTENING -> listeningFraction(i, barCount, amplitude, breath)
                VoiceVisualState.THINKING -> sweepFraction(i, sweep)
                VoiceVisualState.SPEAKING -> waveFraction(i, wave)
            }

            val height = minHeight + (maxHeight - minHeight) * fraction.coerceIn(0f, 1f)
            val left = i * (barWidth + gap)
            drawBar(
                left = left,
                width = barWidth,
                height = height,
                canvasHeight = size.height,
                radius = radius,
                color = color,
                alpha = presence * (0.55f + 0.45f * fraction.coerceIn(0f, 1f)),
            )
        }
    }
}

/** All five bars together, so it reads as one dormant object rather than activity. */
private fun idleFraction(breath: Float): Float {
    val pulse = sin(breath * 2f * Math.PI.toFloat())
    return 0.04f + 0.03f * (pulse + 1f) / 2f
}

/**
 * Centre-weighted, so loud speech pushes the middle bars hardest — the shape people already read as
 * a voice. The per-bar phase offset keeps neighbouring bars from moving in lockstep, which is what
 * makes an equaliser look mechanical.
 */
private fun listeningFraction(index: Int, count: Int, amplitude: Float, breath: Float): Float {
    val centre = (count - 1) / 2f
    val distance = abs(index - centre) / max(centre, 1f)
    val weight = 1f - 0.55f * distance
    val phase = breath * 2f * Math.PI.toFloat() + index * 1.1f
    val wobble = 0.82f + 0.18f * (sin(phase) + 1f) / 2f
    // A visible floor: at true silence the bars sit just above the dots, so "listening but hearing
    // nothing" still looks alive and awaiting input.
    return 0.10f + amplitude * weight * wobble * 0.9f
}

/** A single travelling light. Deliberately unaffected by the microphone. */
private fun sweepFraction(index: Int, sweep: Float): Float {
    val distance = abs(index - sweep)
    val falloff = (1f - distance / 1.5f).coerceAtLeast(0f)
    return 0.08f + 0.72f * falloff * falloff
}

/** A standing wave — self-driven, so it reads as output rather than input. */
private fun waveFraction(index: Int, wave: Float): Float =
    0.18f + 0.55f * (sin(wave + index * 0.9f) + 1f) / 2f

private fun DrawScope.drawBar(
    left: Float,
    width: Float,
    height: Float,
    canvasHeight: Float,
    radius: CornerRadius,
    color: Color,
    alpha: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(left, (canvasHeight - height) / 2f),
        size = Size(width, height),
        cornerRadius = radius,
        alpha = alpha.coerceIn(0f, 1f),
    )
}

enum class VoiceVisualState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * The recogniser reports RMS in dB, roughly -2 at silence to 10 when someone is speaking at a
 * phone. Map that onto 0..1 rather than feeding raw dB into a height.
 */
fun normaliseRms(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
