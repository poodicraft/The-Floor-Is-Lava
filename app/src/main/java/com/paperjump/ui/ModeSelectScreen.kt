package com.paperjump.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paperjump.data.LevelRecord
import com.paperjump.game.GameMode
import com.paperjump.game.GameSetup
import com.paperjump.game.Twist
import com.paperjump.processing.LevelData
import com.paperjump.ui.components.ScreenHeader
import com.paperjump.ui.components.SectionLabel
import com.paperjump.ui.components.SegmentedChoice
import com.paperjump.ui.components.StatChip
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.LavaOrange
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen
import java.util.Locale

/**
 * Pick a game for the drawing that was just made.
 *
 * This sits directly after making a level, because *what you are playing* is the first
 * interesting decision — the same page is a platformer, a maze, a flying course or a
 * runner's track, and the detection settings are a detail you only need when something
 * came out wrong.
 */
@Composable
fun ModeSelectScreen(
    level: LevelData?,
    isProcessing: Boolean,
    errorMessage: String?,
    twist: Twist,
    recordFor: (GameSetup) -> LevelRecord,
    onTwistChange: (Twist) -> Unit,
    onPlay: (GameSetup) -> Unit,
    onTune: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        ScreenHeader(
            title = "Choose a game",
            subtitle = "Same drawing, four different games",
            onBack = onBack,
        )

        if (level == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Reading your drawing…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatChip(value = level.platforms.size.toString(), label = "lines")
            StatChip(value = level.coins.size.toString(), label = "coins", accent = CoinGold)
            StatChip(
                value = if (level.goal != null) "yes" else "none",
                label = "flag",
                accent = if (level.goal != null) SkyBlue else MaterialTheme.colorScheme.error,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isProcessing) {
                Text(
                    text = "Re-reading the drawing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (level.goal == null) {
                Text(
                    text = "There is no blue flag on this page, so no game can be won. " +
                        "Add one, or adjust how the drawing was read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            GameMode.entries.forEach { mode ->
                ModeCard(
                    mode = mode,
                    level = level,
                    record = recordFor(GameSetup(mode, twist)),
                    onClick = { onPlay(GameSetup(mode, twist)) },
                )
            }

            SectionLabel("Add a twist")
            SegmentedChoice(
                options = Twist.entries.toList(),
                selected = twist,
                label = { it.title },
                onSelect = onTwistChange,
            )
            Text(
                text = twist.blurb + if (twist == Twist.COIN_HUNT && level.coins.isEmpty()) {
                    "  (there are no coins on this page, so the flag opens at once)"
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onTune, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Something look wrong? Adjust the reading")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModeCard(
    mode: GameMode,
    level: LevelData,
    record: LevelRecord,
    onClick: () -> Unit,
) {
    val accent = accentOf(mode)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(shape = CircleShape, color = accent.copy(alpha = 0.18f), contentColor = accent) {
                    Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Icon(iconOf(mode), contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = mode.tagline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent,
                    )
                }
                if (record.hasBeenWon) {
                    Column(horizontalAlignment = Alignment.End) {
                        record.bestTimeSeconds?.let {
                            Text(
                                text = String.format(Locale.US, "%.1fs", it),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            text = "best",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = mode.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )

            // The same lines mean something different in each game, which is the whole point.
            Text(
                text = "Your lines: ${mode.inkMeaning.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                modifier = Modifier.padding(top = 6.dp),
            )

            noteFor(mode, level)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** A heads-up about how this game will behave on this particular drawing. */
private fun noteFor(mode: GameMode, level: LevelData): String? = when (mode) {
    GameMode.FLYER -> if (level.platforms.size > 40) {
        "This page is busy — there may not be much room to fly through."
    } else {
        null
    }
    GameMode.MAZE -> if (level.platforms.size < 3) {
        "Barely any walls here, so there is not much of a maze."
    } else {
        null
    }
    else -> null
}

private fun iconOf(mode: GameMode): ImageVector = when (mode) {
    GameMode.PLATFORMER -> Icons.Rounded.Terrain
    GameMode.MAZE -> Icons.Rounded.Explore
    GameMode.FLYER -> Icons.Rounded.Air
    GameMode.RUNNER -> Icons.Rounded.DirectionsRun
}

private fun accentOf(mode: GameMode): Color = when (mode) {
    GameMode.PLATFORMER -> SkyBlue
    GameMode.MAZE -> SpringGreen
    GameMode.FLYER -> CoinGold
    GameMode.RUNNER -> LavaOrange
}
