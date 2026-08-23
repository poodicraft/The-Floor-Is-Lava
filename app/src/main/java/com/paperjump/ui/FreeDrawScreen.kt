package com.paperjump.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.paperjump.draw.DrawingController
import com.paperjump.draw.DrawingScreen
import com.paperjump.draw.DrawingState
import com.paperjump.ui.components.PaperOutlineButton
import com.paperjump.ui.theme.CreatureViolet
import com.paperjump.ui.theme.LavaOrange

/** Where the AI level designer has got to, for the screen to show. */
sealed interface AiBuildState {
    data object Idle : AiBuildState
    data class Working(val step: String) : AiBuildState
    data class Failed(val message: String, val builtAnyway: Boolean) : AiBuildState
}

/**
 * Draw anything at all, and have a model turn it into a level.
 *
 * The same sketchpad as the ordinary one, with the colour code taken away: here the pens
 * are just pens. What the picture *means* is the model's problem, not the player's — which
 * is the whole point of this route existing alongside the exact one rather than instead
 * of it.
 */
@Composable
fun FreeDrawScreen(
    controller: DrawingController,
    hint: String,
    state: AiBuildState,
    hasKey: Boolean,
    onHintChange: (String) -> Unit,
    onBuild: (DrawingState, Float) -> Unit,
    onPlayAnyway: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fellBack = state is AiBuildState.Failed && state.builtAnyway

    DrawingScreen(
        controller = controller,
        // After a failure the level is already built the ordinary way, so the button walks
        // on to it rather than spending another round trip on a model that just refused.
        onPlay = { document, aspect -> if (fellBack) onPlayAnyway() else onBuild(document, aspect) },
        onBack = onBack,
        modifier = modifier,
        title = "Draw anything",
        subtitle = "A castle, a face, a racetrack — it becomes a level",
        actionLabel = when {
            state is AiBuildState.Working -> "Building…"
            fellBack -> "Play it anyway"
            else -> "Turn it into a game"
        },
        actionEnabled = state !is AiBuildState.Working,
        showChecklist = false,
        footer = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Colours mean nothing here — draw however you like.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = hint,
                    onValueChange = { onHintChange(it.take(200)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = state !is AiBuildState.Working,
                    label = { Text("What is it? (optional)") },
                    placeholder = { Text("a dragon guarding a tower") },
                )

                when (state) {
                    is AiBuildState.Idle -> if (!hasKey) MissingKeyNote(onOpenSettings)
                    is AiBuildState.Working -> WorkingNote(state.step)
                    is AiBuildState.Failed -> FailureNote(state, onOpenSettings)
                }
            }
        },
    )
}

@Composable
private fun WorkingNote(step: String) {
    Note(accent = CreatureViolet) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = CreatureViolet,
            )
            Text(step, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MissingKeyNote(onOpenSettings: () -> Unit) {
    Note(accent = MaterialTheme.colorScheme.onSurfaceVariant) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "This needs a free Hugging Face key, which lives on this phone and " +
                    "goes nowhere else.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PaperOutlineButton(
                text = "Add the key in Settings",
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FailureNote(state: AiBuildState.Failed, onOpenSettings: () -> Unit) {
    Note(accent = LavaOrange) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.builtAnyway) {
                Text(
                    text = "Your drawing was read the ordinary way instead, so there is " +
                        "still a level to play.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PaperOutlineButton(
                text = "Check the key and model",
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Note(accent: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}
