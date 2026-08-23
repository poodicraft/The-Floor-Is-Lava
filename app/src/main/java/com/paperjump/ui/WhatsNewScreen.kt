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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paperjump.ui.components.ScreenHeader
import com.paperjump.ui.components.StickerBadge
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.LavaOrange

/**
 * The release notes, in the app.
 *
 * Kept here rather than in a file the app fetches: nothing but the optional AI designer
 * goes online, and a changelog is small enough to ship. The newest release is first, and the app
 * opens this screen once by itself after an update — see [Release.LATEST].
 */
data class Release(val version: String, val headline: String, val changes: List<String>) {
    companion object {
        /** The version whose notes are shown automatically after an update. */
        val LATEST: String get() = ALL.first().version

        val ALL = listOf(
            Release(
                version = "1.7",
                headline = "Draw anything, and the app is now PaperEngine",
                changes = listOf(
                    "New on the menu: \"Draw anything\". Draw a castle, a face, a racetrack " +
                        "— anything at all, with no colour code — and a vision model on " +
                        "Hugging Face designs a level out of it.",
                    "It needs your own free Hugging Face key, which you paste into " +
                        "Settings and which never leaves the phone. Everything else in the " +
                        "app still works with no network at all.",
                    "What the model designs is painted as an ordinary sketch and read by " +
                        "the ordinary detector, so an AI level is saved, re-tuned and " +
                        "replayed like any other — no key needed the second time.",
                    "If the model cannot be reached, your drawing is still read the normal " +
                        "way, so you always come back with something to play.",
                    "The app is called PaperEngine now.",
                ),
            ),
            Release(
                version = "1.6",
                headline = "Creatures, cleaner lines, a proper eraser",
                changes = listOf(
                    "Purple ink becomes a creature. It walks the ledge it was drawn on and " +
                        "ends your run on contact — unless you land on top of it.",
                    "The detector now reads your ink as *lines* instead of as a grid of " +
                        "blocks, so a wobbly hand-drawn ledge comes out as one clean bar at " +
                        "the angle you drew it.",
                    "The eraser rubs out only what is under it, instead of deleting the " +
                        "whole stroke you happened to touch.",
                    "A scoreboard on the home screen, and these release notes.",
                ),
            ),
            Release(
                version = "1.5",
                headline = "Everything presses",
                changes = listOf(
                    "Buttons, tiles and cards sit on a coloured slab and press down into it.",
                    "Rounder, bolder, and a hero tile for the thing the app is actually for.",
                ),
            ),
            Release(
                version = "1.4",
                headline = "Four games, straighter lines, autosave",
                changes = listOf(
                    "Platformer, Maze, Flyer and Runner — four different games from one page.",
                    "Twists: coin hunt, time attack and rising lava.",
                    "Every level is saved to My levels the moment it is read.",
                    "The green start dot no longer punches a hole through the platform " +
                        "it is drawn on.",
                ),
            ),
            Release(
                version = "1.3",
                headline = "Draw levels in the app",
                changes = listOf(
                    "A sketchpad with a pen per element, undo and redo.",
                    "A level library, a how-to-play screen and settings that do something.",
                ),
            ),
            Release(
                version = "1.2",
                headline = "Installs on more phones",
                changes = listOf("A properly signed, non-debuggable release build."),
            ),
            Release(
                version = "1.1",
                headline = "The first playable build",
                changes = listOf(
                    "Photograph a drawing and play it: dark lines are ground, red is lava, " +
                        "yellow is coins, green is the start and blue is the flag.",
                ),
            ),
        )
    }
}

@Composable
fun WhatsNewScreen(
    versionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "What's new",
            subtitle = "You are on version $versionName",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Release.ALL.forEach { release ->
                ReleaseCard(release = release, current = release.version == versionName)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReleaseCard(release: Release, current: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.5.dp,
            if (current) LavaOrange.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Version ${release.version}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (current) StickerBadge(text = "you're here", accent = CoinGold)
            }
            Text(
                text = release.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = LavaOrange,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            release.changes.forEach { change ->
                Text(
                    text = "• $change",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}
