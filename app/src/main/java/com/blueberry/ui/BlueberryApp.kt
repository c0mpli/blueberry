package com.blueberry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueberry.router.RouterResult

/**
 * One composable, shared by every entry point. Idle, listening, thinking, result — and the drawer
 * over the top of all of them.
 */
@Composable
fun BlueberryApp(
    viewModel: BlueberryViewModel,
    onSpeakRequested: () -> Unit,
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val catalogue by viewModel.catalogue.collectAsStateWithLifecycle()

    // Back must never exit the launcher. On the drawer it returns home; on home it does nothing at
    // all, which is deliberate rather than an oversight.
    BackHandler(enabled = true) {
        when {
            screen == Screen.DRAWER -> viewModel.closeDrawer()
            state !is UiState.Idle -> viewModel.onDismiss()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        VoiceSurface(
            state = state,
            onPressStart = viewModel::onPressStart,
            onSpeak = onSpeakRequested,
            onDismiss = viewModel::onDismiss,
            onSwipeUp = viewModel::openDrawer,
        )

        AnimatedVisibility(
            visible = screen == Screen.DRAWER,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            AppDrawer(
                catalogue = catalogue,
                iconFor = viewModel::icon,
                onLaunch = { pkg ->
                    viewModel.closeDrawer()
                    viewModel.launchApp(pkg)
                },
                onClose = viewModel::closeDrawer,
            )
        }
    }
}

@Composable
private fun VoiceSurface(
    state: UiState,
    onPressStart: () -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The whole screen is the tap target. No keyboard, no input field, no clock, no icons.
            .pointerInput(state) {
                detectTapGestures(
                    // Pre-warm on tap-down, not tap-up: the cheapest latency win in the app.
                    onPress = { onPressStart() },
                    onTap = { if (state is UiState.Idle) onSpeak() else onDismiss() },
                )
            }
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (travelled < -SWIPE_THRESHOLD_PX) onSwipeUp() },
                ) { _, delta -> travelled += delta }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is UiState.Done -> ResultCard(state)
            else -> VoiceStage(state)
        }
    }
}

/**
 * Idle, listening and thinking share one layout so the visualiser never jumps position between
 * them — it changes only its motion. Moving it would read as a new screen rather than a change of
 * turn, which is the thing this is trying to make obvious.
 */
@Composable
private fun VoiceStage(state: UiState) {
    val visual = when (state) {
        is UiState.Listening -> VoiceVisualState.LISTENING
        is UiState.Thinking -> VoiceVisualState.THINKING
        else -> VoiceVisualState.IDLE
    }
    val level = (state as? UiState.Listening)?.let { normaliseRms(it.level) } ?: 0f

    val caption = when (state) {
        is UiState.Idle -> "tap to speak"
        is UiState.Listening -> "listening"
        is UiState.Thinking -> "thinking"
        else -> ""
    }

    val transcript = when (state) {
        is UiState.Listening -> state.partial
        is UiState.Thinking -> state.transcript
        else -> ""
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        VoiceVisualizer(
            state = visual,
            level = level,
            color = MaterialTheme.colorScheme.primary,
        )

        // The caption is belt-and-braces. The motion should already say it, but "listening" costs
        // one line and removes any doubt about whose turn it is.
        Text(
            text = caption,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (state is UiState.Idle) 0.55f else 0.9f),
        )

        if (transcript.isNotBlank()) {
            Text(
                text = transcript,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ResultCard(state: UiState.Done) {
    val (headline, detail) = when (val r = state.result) {
        is RouterResult.Action -> r.label to null
        is RouterResult.Saved -> "Saved" to "${r.text}  ·  ${r.target}"
        is RouterResult.Answer -> r.text to null
        is RouterResult.Failed -> r.reason to null
        is RouterResult.Clarify -> r.question to null
        is RouterResult.Visual -> r.title to r.narration
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineMedium,
                color = if (state.result is RouterResult.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SWIPE_THRESHOLD_PX = 120f
