package com.paperjump.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paperjump.ui.components.MenuTile
import com.paperjump.ui.components.PaperBackdrop
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.LavaOrange
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen

/** The front door: pick how to make a level, or go somewhere else in the app. */
@Composable
fun HomeScreen(
    savedLevelCount: Int,
    versionName: String,
    onDraw: () -> Unit,
    onPhotograph: () -> Unit,
    onLibrary: () -> Unit,
    onHowToPlay: () -> Unit,
    onSettings: () -> Unit,
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

            Text(
                text = "Paper Jump",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Draw a level. Play it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MenuTile(
                    title = "Draw a level",
                    subtitle = "Sketch it here with your finger",
                    icon = Icons.Rounded.Brush,
                    accent = LavaOrange,
                    onClick = onDraw,
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
                    subtitle = "What each colour does, and the modes",
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

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
