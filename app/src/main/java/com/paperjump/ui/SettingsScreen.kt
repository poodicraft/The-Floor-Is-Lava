package com.paperjump.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.paperjump.ai.AiProvider
import com.paperjump.data.AppSettings
import com.paperjump.data.ThemeChoice
import com.paperjump.ui.components.PaperOutlineButton
import com.paperjump.ui.components.ScreenHeader
import com.paperjump.ui.components.SectionLabel
import com.paperjump.ui.components.SegmentedChoice
import com.paperjump.ui.components.ToggleRow
import kotlin.math.roundToInt

/** Everything the player can change, and the door to wiping saved data. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    savedLevelCount: Int,
    versionName: String,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearLevels: () -> Unit,
    onClearRecords: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingClearLevels by remember { mutableStateOf(false) }
    var confirmingClearRecords by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(title = "Settings", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Appearance")
            SegmentedChoice(
                options = ThemeChoice.entries.toList(),
                selected = settings.theme,
                label = { it.label },
                onSelect = { choice -> onSettingsChange { it.copy(theme = choice) } },
            )

            SectionLabel("Controls")
            ToggleRow(
                title = "Jump button on the right",
                subtitle = if (settings.jumpOnRight) {
                    "Move with the left thumb, jump with the right"
                } else {
                    "Move with the right thumb, jump with the left"
                },
                checked = settings.jumpOnRight,
                onCheckedChange = { value -> onSettingsChange { it.copy(jumpOnRight = value) } },
            )
            ToggleRow(
                title = "Vibration",
                subtitle = "A short buzz on jump",
                checked = settings.haptics,
                onCheckedChange = { value -> onSettingsChange { it.copy(haptics = value) } },
            )
            ControlSizeSetting(
                scale = settings.controlScale,
                onChange = { value -> onSettingsChange { it.copy(controlScale = value) } },
            )

            SectionLabel("Heads-up display")
            ToggleRow(
                title = "Show the timer",
                subtitle = "Time attack always shows its countdown",
                checked = settings.showTimer,
                onCheckedChange = { value -> onSettingsChange { it.copy(showTimer = value) } },
            )

            SectionLabel("AI level designer")
            AiKeySetting(
                settings = settings,
                onSettingsChange = onSettingsChange,
            )

            SectionLabel("Saved data")
            PaperOutlineButton(
                text = "Clear personal bests",
                icon = Icons.Rounded.Restore,
                onClick = { confirmingClearRecords = true },
                modifier = Modifier.fillMaxWidth(),
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PaperOutlineButton(
                text = if (savedLevelCount > 0) {
                    "Delete all $savedLevelCount saved levels"
                } else {
                    "No saved levels"
                },
                icon = Icons.Rounded.DeleteSweep,
                onClick = { confirmingClearLevels = true },
                enabled = savedLevelCount > 0,
                modifier = Modifier.fillMaxWidth(),
                accent = MaterialTheme.colorScheme.error,
            )

            SectionLabel("About")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("PaperEngine $versionName", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Photograph or draw a level, and play it four ways. Everything " +
                            "stays on this device and there is no account — the only thing " +
                            "that ever goes online is the optional AI level designer, and " +
                            "only when you ask it to.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmingClearLevels) {
        ConfirmDialog(
            title = "Delete all saved levels?",
            message = "All $savedLevelCount levels in your library will be removed. This cannot be undone.",
            confirmLabel = "Delete all",
            destructive = true,
            onConfirm = {
                onClearLevels()
                confirmingClearLevels = false
            },
            onDismiss = { confirmingClearLevels = false },
        )
    }

    if (confirmingClearRecords) {
        ConfirmDialog(
            title = "Clear personal bests?",
            message = "Best times and coin counts for every level and mode will be reset. " +
                "Your saved levels are not affected.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                onClearRecords()
                confirmingClearRecords = false
            },
            onDismiss = { confirmingClearRecords = false },
        )
    }
}

/**
 * What the settings screen says about the level designer, which is as little as possible.
 *
 * When the build already carries a key — the ordinary case, since it comes from a
 * repository secret at build time — this is one line saying so and nothing to fill in. A
 * key box on a screen where no key is needed is not a setting, it is homework, and it made
 * a feature that works look like one that is waiting for you.
 *
 * The boxes are still there for the build that has no key, and behind "Change the key" for
 * anyone who wants a different one. Nobody else ever sees them.
 */
@Composable
private fun AiKeySetting(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    // With no key there is nothing to reveal and no point in a "change it" button: the
    // boxes *are* the screen. With one, they stay shut until asked for.
    val ready = settings.canUseAi
    val showFields = editing || !ready

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    settings.aiProxyUrl.isNotBlank() ->
                        "Ready. This build asks your own server, which holds the key — there " +
                            "is nothing to fill in."
                    ready ->
                        "Ready to use, with ${settings.aiProvider.label}. The key came with " +
                            "this build and stays on this phone."
                    else ->
                        "\"Draw anything\" sends your drawing to a vision model and builds the " +
                            "level it designs back. It needs a free key from " +
                            "${AiProvider.GOOGLE.keyHome} — that tier reads pictures without a " +
                            "card on file. A Hugging Face token works too, if you have one."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (ready && !editing) {
                TextButton(
                    onClick = { editing = true },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Use a different key")
                }
            }

            if (showFields) {
                OutlinedTextField(
                    value = settings.aiProxyUrl,
                    onValueChange = { value ->
                        onSettingsChange { it.copy(aiProxyUrl = value.trim()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Server that holds the key (optional)") },
                    placeholder = { Text("https://…workers.dev") },
                )

                OutlinedTextField(
                    value = settings.aiToken,
                    enabled = settings.aiProxyUrl.isBlank(),
                    onValueChange = { value ->
                        onSettingsChange { it.copy(aiToken = value.trim()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Key") },
                    placeholder = { Text("AQ.… or AIza…") },
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show")
                        }
                    },
                )

                OutlinedTextField(
                    value = settings.aiModel,
                    onValueChange = { value ->
                        onSettingsChange { it.copy(aiModel = value.trim()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model (optional)") },
                    placeholder = { Text(settings.aiProvider.defaultModel) },
                    supportingText = {
                        Text("Empty means whatever is being served — the usual answer.")
                    },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperOutlineButton(
                        text = "Forget key",
                        onClick = {
                            onSettingsChange { it.copy(aiToken = "", aiModel = "") }
                        },
                        enabled = settings.aiToken.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        accent = MaterialTheme.colorScheme.error,
                    )
                    if (editing) {
                        PaperOutlineButton(
                            text = "Done",
                            onClick = { editing = false },
                            modifier = Modifier.weight(1f),
                            accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlSizeSetting(scale: Float, onChange: (Float) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Button size",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(scale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = scale,
                onValueChange = onChange,
                valueRange = AppSettings.MIN_CONTROL_SCALE..AppSettings.MAX_CONTROL_SCALE,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
