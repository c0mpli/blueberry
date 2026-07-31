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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The companion's presence: a soft, breathing orb that is always there.
 *
 * This replaces an equaliser as the centrepiece for a reason. Bars are an *instrument* — they read
 * as a meter attached to a microphone. An orb reads as something present in the room, which is what
 * a companion needs to be when it is doing nothing at all. Most of the time this app is idle, and
 * what it looks like while idle is most of what it feels like.
 *
 * Every state is still legible at a glance, which was the point of the earlier visualiser and is
 * not given up here:
 *
 *  * **Idle** — slow deep breath, dim. Alive but not waiting on you.
 *  * **Listening** — swells with your voice and brightens; the halo tracks amplitude directly.
 *  * **Thinking** — the surface churns while the outline holds still, so it reads as working
 *    rather than as hearing.
 *  * **Speaking** — a steady pulse it drives itself.
 */
@Composable
fun CompanionOrb(
    state: VoiceVisualState,
    level: Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 180.dp,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    /** One slow cycle, shared so nothing beats against anything else. */
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(if (state == VoiceVisualState.IDLE) 5200 else 2600)
        ),
        label = "breath",
    )

    /** Churn for the thinking state, deliberately faster than any breath. */
    val churn by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400)),
        label = "churn",
    )

    // Springing amplitude gives the orb weight — it swells into a loud syllable and settles after,
    // rather than snapping, which is what makes it feel like a body rather than a graph.
    val amplitude by animateFloatAsState(
        targetValue = if (state == VoiceVisualState.LISTENING) level.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "amplitude",
    )

    val presence by animateFloatAsState(
        targetValue = when (state) {
            VoiceVisualState.IDLE -> 0.55f
            VoiceVisualState.LISTENING -> 1f
            VoiceVisualState.THINKING -> 0.8f
            VoiceVisualState.SPEAKING -> 0.92f
        },
        animationSpec = tween(500),
        label = "presence",
    )

    Canvas(modifier.size(size)) {
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val base = this.size.minDimension / 2f

        val pulse = when (state) {
            VoiceVisualState.IDLE -> 0.03f * sin(breath)
            VoiceVisualState.LISTENING -> 0.05f * sin(breath) + 0.22f * amplitude
            VoiceVisualState.THINKING -> 0.04f * sin(breath)
            VoiceVisualState.SPEAKING -> 0.09f * sin(breath)
        }
        val radius = base * (0.62f + pulse)

        // Halo first, widest and faintest, so the core sits inside a glow rather than on top of it.
        for (ring in HALO_RINGS downTo 1) {
            val spread = radius * (1f + 0.22f * ring + 0.30f * amplitude)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        HALO.copy(alpha = 0.16f * presence / ring),
                        Color.Transparent,
                    ),
                    center = centre,
                    radius = spread,
                ),
                radius = spread,
                center = centre,
            )
        }

        // The body. Off-centre highlight gives it a light source and stops it reading as a flat disc.
        val highlight = Offset(
            centre.x - radius * 0.28f + if (state == VoiceVisualState.THINKING) cos(churn) * radius * 0.16f else 0f,
            centre.y - radius * 0.30f + if (state == VoiceVisualState.THINKING) sin(churn) * radius * 0.16f else 0f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CORE_LIGHT, CORE, CORE_DEEP),
                center = highlight,
                radius = radius * 1.5f,
            ),
            radius = radius,
            center = centre,
            alpha = presence,
        )

        // A thin rim, brightest where the light is not, which is what sells volume.
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, RIM.copy(alpha = 0.5f * presence)),
                start = Offset(centre.x - radius, centre.y - radius),
                end = Offset(centre.x + radius, centre.y + radius),
            ),
            radius = radius,
            center = centre,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius * 0.045f),
        )
    }
}

private val CORE_LIGHT = Color(0xFFCFC2FF)
private val CORE = Color(0xFF8F7BE8)
private val CORE_DEEP = Color(0xFF4B3A8F)
private val HALO = Color(0xFF9B8BF0)
private val RIM = Color(0xFFE6DEFF)
private const val HALO_RINGS = 3
