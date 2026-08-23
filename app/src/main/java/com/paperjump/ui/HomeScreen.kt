package com.paperjump.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.paperjump.data.PlayerStats
import com.paperjump.ui.components.HeroTile
import com.paperjump.ui.components.MenuTile
import com.paperjump.ui.components.PaperBackdrop
import com.paperjump.ui.components.StatChip
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.CreatureViolet
import com.paperjump.ui.theme.LavaOrange
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen
import java.util.Locale

/** The front door: pick how to make a level, or go somewhere else in the app. */
@Composable
fun HomeScreen(
    savedLevelCount: Int,
    versionName: String,
    stats: PlayerStats,
    onDraw: () -> Unit,
    onPhotograph: () -> Unit,
    onLibrary: () -> Unit,
    onHowToPlay: () -> Unit,
    onSettings: () -> Unit,
    onWhatsNew: () -> Unit,
    onFreeDraw: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        PaperBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            Title()

            Spacer(Modifier.height(18.dp))

            // A scoreboard, once there is something on it. An empty one on a first run
            // would be three zeroes telling the player they have done nothing.
            if (stats.hasPlayed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatChip(
                        value = stats.levelsBeaten.toString(),
                        label = "beaten",
                        accent = SpringGreen,
                        modifier = Modifier.weight(1f),
                    )
                    StatChip(
                        value = stats.runs.toString(),
                        label = "runs",
                        accent = SkyBlue,
                        modifier = Modifier.weight(1f),
                    )
                    StatChip(
                        value = stats.bestTimeSeconds
                            ?.let { String.format(Locale.US, "%.1fs", it) }
                            ?: "—",
                        label = "best",
                        accent = CoinGold,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            // The whole point of the app, one tap away and looking like it.
            HeroTile(
                title = "Draw a level",
                subtitle = "Sketch it here with your finger",
                icon = Icons.Rounded.Brush,
                accent = LavaOrange,
                onClick = onDraw,
            )

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MenuTile(
                    title = "Draw anything",
                    subtitle = "Any picture — AI turns it into a level",
                    icon = Icons.Rounded.AutoAwesome,
                    accent = CreatureViolet,
                    onClick = onFreeDraw,
                )
                MenuTile(
                    title = "Photograph a sketch",
                    subtitle = "Turn a real drawing into a level",
                    icon = Icons.Rounded.PhotoCamera,
                    accent = SkyBlue,
                    onClick = onPhotograph,
                )
                MenuTile(
                    title = "My levels",
                    subtitle = if (savedLevelCount == 0) {
                        "Nothing saved yet"
                    } else {
                        "$savedLevelCount saved"
                    },
                    icon = Icons.Rounded.Widgets,
                    accent = CoinGold,
                    onClick = onLibrary,
                    trailing = {
                        if (savedLevelCount > 0) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = CoinGold.copy(alpha = 0.2f),
                                contentColor = CoinGold,
                            ) {
                                Text(
                                    text = savedLevelCount.toString(),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    },
                )
                MenuTile(
                    title = "How to play",
                    subtitle = "Colours, the four games, and tips",
                    icon = Icons.Rounded.HelpOutline,
                    accent = SpringGreen,
                    onClick = onHowToPlay,
                )
                MenuTile(
                    title = "Settings",
                    subtitle = "Theme, controls, saved data",
                    icon = Icons.Rounded.Settings,
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onSettings,
                )
            }

            Spacer(Modifier.height(22.dp))

            // The version is a link to what changed in it, the way every app's About does.
            Surface(
                onClick = onWhatsNew,
                shape = MaterialTheme.shapes.small,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = "Version $versionName  ·  What's new",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The app's name, underlined by hand and with a badge stuck to it.
 *
 * The underline is drawn rather than typed so it wobbles like the drawings the app is made
 * of, and it sweeps in on launch — the one bit of showmanship on an otherwise plain page.
 */
@Composable
private fun Title() {
    val transition = rememberInfiniteTransition(label = "title")
    // A slow breath rather than a one-shot draw: the page is never completely still.
    val sweep by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    val underline = LavaOrange

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "PaperEngine",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Canvas(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(0.72f)
                .height(12.dp),
        ) {
            val stroke = size.height * 0.4f
            val path = Path().apply {
                moveTo(0f, size.height * 0.6f)
                cubicTo(
                    size.width * 0.28f, size.height * 0.05f,
                    size.width * 0.62f, size.height * 1.0f,
                    size.width * sweep, size.height * 0.35f,
                )
            }
            drawPath(
                path = path,
                color = underline,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
