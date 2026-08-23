package com.paperjump.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperjump.processing.LevelData
import com.paperjump.processing.ProcessingConfig
import com.paperjump.ui.components.LevelPreview
import com.paperjump.ui.components.PaperButton
import com.paperjump.ui.components.PaperOutlineButton
import com.paperjump.ui.components.ScreenHeader
import kotlin.math.roundToInt

/**
 * Step 2: show what the detector saw and let the player fix it before playing.
 *
 * Photos of paper are never perfect — a faint pencil line, a shadow across the page or a
 * highlighter that reads as beige. Rather than hiding that, the sliders re-run the exact
 * same pipeline and the preview draws with the exact same renderer as the game.
 */
@Composable
fun LevelTuneScreen(
    sketch: Bitmap?,
    level: LevelData?,
    config: ProcessingConfig,
    isProcessing: Boolean,
    errorMessage: String?,
    isSaved: Boolean,
    onConfigChange: (ProcessingConfig) -> Unit,
    onSave: (String) -> Unit,
    onRetake: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPhoto by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "Your level",
            subtitle = "How the drawing was read",
            onBack = onRetake,
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (level != null) level.width / level.height else 3f / 2f)
                    .clip(RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (showPhoto && sketch != null) {
                    Image(
                        bitmap = sketch.asImageBitmap(),
                        contentDescription = "The photo of your sketch",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (level != null) {
                    val overlaid = showPhoto && sketch != null
                    LevelPreview(
                        level = level,
                        showPaper = !overlaid,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (overlaid) 0.7f else 1f),
                    )
                }
                if (isProcessing) {
                    CircularProgressIndicator()
                }
            }
        }

        if (sketch != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = showPhoto, onCheckedChange = { showPhoto = it })
                Text("Compare with the photo", style = MaterialTheme.typography.bodyMedium)
            }
        }

        errorMessage?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(message, modifier = Modifier.padding(14.dp))
            }
        }

        level?.let { detected ->
            DetectionSummary(detected)
            if (detected.warnings.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        detected.warnings.forEach { warning ->
                            Text("• $warning", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        SketchLegend(modifier = Modifier.fillMaxWidth(), compact = false)

        TuningSlider(
            label = "Line sensitivity",
            help = "Raise it if platforms are missing, lower it if shadows became walls.",
            value = config.inkSensitivity,
            valueRange = -1f..1f,
            valueLabel = signedLabel(config.inkSensitivity),
            onValueChange = { onConfigChange(config.copy(inkSensitivity = it)) },
        )
        TuningSlider(
            label = "Colour sensitivity",
            help = "Raise it for pale highlighters, lower it if plain ink turns into lava.",
            value = config.colorSensitivity,
            valueRange = -1f..1f,
            valueLabel = signedLabel(config.colorSensitivity),
            onValueChange = { onConfigChange(config.copy(colorSensitivity = it)) },
        )
        TuningSlider(
            label = "Detail",
            help = "How finely the paper is sampled. More detail = thinner platforms.",
            value = config.gridCols.toFloat(),
            valueRange = ProcessingConfig.MIN_GRID_COLS.toFloat()..ProcessingConfig.MAX_GRID_COLS.toFloat(),
            valueLabel = "${config.gridCols} cols",
            onValueChange = { onConfigChange(config.copy(gridCols = it.roundToInt())) },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = config.straightenLines,
                onCheckedChange = { onConfigChange(config.copy(straightenLines = it)) },
            )
            Column {
                Text("Read the ink as lines", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Redraws each run of ink as a clean line instead of following " +
                        "every wobble. Deliberate slopes and corners are kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PaperButton(
            text = "Back to the games",
            icon = Icons.Rounded.PlayArrow,
            onClick = onContinue,
            enabled = level != null,
            modifier = Modifier.fillMaxWidth(),
        )

        PaperOutlineButton(
            text = if (isSaved) "Rename in my levels" else "Save to my levels",
            icon = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            onClick = { naming = true },
            enabled = level != null,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (naming) {
        SaveLevelDialog(
            onDismiss = { naming = false },
            onConfirm = { name ->
                onSave(name)
                naming = false
            },
        )
    }
}

/** Asks for a name before a level joins the library. */
@Composable
private fun SaveLevelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this level") },
        text = {
            Column {
                Text(
                    text = "It is already in My levels — this just gives it a better name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text("My level") },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetectionSummary(level: LevelData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SummaryTile("Lines", level.lineCount.toString(), Modifier.weight(1f))
        SummaryTile("Coins", level.coins.size.toString(), Modifier.weight(1f))
        SummaryTile("Creatures", level.enemies.size.toString(), Modifier.weight(1f))
        SummaryTile("Grid", "${level.cols}×${level.rows}", Modifier.weight(1f))
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    help: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
        Text(
            help,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.size(2.dp))
    }
}

private fun signedLabel(value: Float): String = when {
    value > 0.05f -> "+${(value * 100).roundToInt()}"
    value < -0.05f -> "${(value * 100).roundToInt()}"
    else -> "auto"
}
