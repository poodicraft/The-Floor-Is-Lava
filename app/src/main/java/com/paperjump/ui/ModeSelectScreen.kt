package com.paperjump.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.paperjump.data.LevelRecord
import com.paperjump.game.GameMode
import com.paperjump.processing.LevelData
import com.paperjump.ui.components.ScreenHeader
import com.paperjump.ui.components.StatChip
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.LavaOrange
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen
import java.util.Locale

/**
 * Pick a game type for the level that was just made.
 *
 * Every mode plays every level, so nothing is ever disabled here — but a mode that will be
 * trivial or impossible on *this* drawing says so up front, which is friendlier than
 * letting someone find out after a run.
 */
@Composable
fun ModeSelectScreen(
    level: LevelData,
    recordFor: (GameMode) -> LevelRecord,
    onPlay: (GameMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        ScreenHeader(
            title = "Choose a game type",
            subtitle = "The level plays the same; the rules change",
            onBack = onBack,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatChip(value = level.platforms.size.toString(), label = "platforms")
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GameMode.entries.forEach { mode ->
                ModeCard(
                    mode = mode,
                    level = level,
                    record = recordFor(mode),
                    onClick = { onPlay(mode) },
                )
            }
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

/** A heads-up about how this mode will behave on this particular drawing. */
private fun noteFor(mode: GameMode, level: LevelData): String? {
    if (level.goal == null) {
        return "No blue flag on this page, so there is nothing to reach — draw one to be able to win."
    }
    return when (mode) {
        GameMode.COIN_HUNT -> if (level.coins.isEmpty()) {
            "No coins on this page, so the flag is open from the start."
        } else {
            null
        }
        GameMode.TIME_ATTACK -> {
            val limit = mode.rulesFor(level).timeLimitSeconds ?: return null
            "Clock for this level: ${limit.toInt()} seconds."
        }
        GameMode.RISING_LAVA -> "The page floods in about 55 seconds."
        GameMode.CLASSIC -> null
    }
}

private fun iconOf(mode: GameMode): ImageVector = when (mode) {
    GameMode.CLASSIC -> Icons.Rounded.Flag
    GameMode.COIN_HUNT -> Icons.Rounded.MonetizationOn
    GameMode.TIME_ATTACK -> Icons.Rounded.Timer
    GameMode.RISING_LAVA -> Icons.Rounded.Waves
}

private fun accentOf(mode: GameMode): Color = when (mode) {
    GameMode.CLASSIC -> SkyBlue
    GameMode.COIN_HUNT -> CoinGold
    GameMode.TIME_ATTACK -> SpringGreen
    GameMode.RISING_LAVA -> LavaOrange
}
