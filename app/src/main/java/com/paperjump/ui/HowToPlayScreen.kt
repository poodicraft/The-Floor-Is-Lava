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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.paperjump.game.GameMode
import com.paperjump.game.Twist
import com.paperjump.ui.components.ScreenHeader
import com.paperjump.ui.components.SectionLabel
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.InkBlack
import com.paperjump.ui.theme.LavaRed
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen

/** The manual: what the detector reads, what the modes do, and how to draw a good level. */
@Composable
fun HowToPlayScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(title = "How to play", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("What each colour becomes")
            Card {
                LegendRow(InkBlack, "Dark lines", "Ground you stand on and walls you bump into")
                LegendRow(LavaRed, "Red", "Lava — touching it ends the run")
                LegendRow(CoinGold, "Yellow", "Coins to collect")
                LegendRow(SpringGreen, "Green", "Where you start")
                LegendRow(SkyBlue, "Blue", "The flag — reach it to win")
            }

            SectionLabel("The games")
            Card {
                Text(
                    text = "One drawing, four games. They are not variations on each " +
                        "other — what moves you, what the lines mean and even the buttons " +
                        "change.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                GameMode.entries.forEach { mode ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(mode.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = mode.blurb,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Your lines: ${mode.inkMeaning.lowercase()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            SectionLabel("Twists")
            Card {
                Text(
                    text = "Any twist can be added to any game.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Twist.entries.filter { it != Twist.NONE }.forEach { twist ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(twist.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = twist.blurb,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionLabel("Controls")
            Card {
                Bullet("Platformer: hold left or right to run; tap jump for a small hop, hold it for a full jump.")
                Bullet("Maze: a four-way pad, because there is no gravity to fall back on.")
                Bullet("Flyer and Runner: one button. Everything else is decided for you.")
                Bullet("You can still jump for a moment after stepping off a ledge, and a jump pressed just before landing still counts.")
                Bullet("Button size and which side jump sits on are in Settings.")
            }

            SectionLabel("Drawing a good level")
            Card {
                Bullet("Use a thick, dark pen on light paper — a fine pencil line can be thinner than one grid cell.")
                Bullet("Photograph the page straight on, filling the frame, with even light and no shadow across it.")
                Bullet("A jump clears about a quarter of the page's width and a fifth of its height. Gaps wider than that cannot be crossed.")
                Bullet("Colour dots need to be a few cells across — a tiny speck is treated as noise on purpose.")
                Bullet("Drawing the green start dot on a platform is fine — the ground is rebuilt underneath it.")
                Bullet("Lines meant to be level are straightened automatically; a deliberate slope is kept.")
                Bullet("If the detector reads something wrong, \"Adjust the reading\" on the game screen will usually fix it.")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun LegendRow(color: Color, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(22.dp)) {}
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.padding(top = 7.dp).size(5.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            ) {}
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
