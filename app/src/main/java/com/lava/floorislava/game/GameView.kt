package com.lava.floorislava.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lava.floorislava.processing.LevelData
import com.lava.floorislava.ui.theme.CoinGold
import com.lava.floorislava.ui.theme.InkBlack
import com.lava.floorislava.ui.theme.LavaOrange
import com.lava.floorislava.ui.theme.SpringGreen
import java.util.Locale
import kotlin.math.min

/**
 * The playable screen: canvas, game loop, touch controls and the win/lose overlays.
 *
 * The loop is a plain `withFrameNanos` ticker; [GameEngine] owns the whole simulation and
 * this composable only forwards input, advances time and draws the result.
 *
 * Repaints are driven by reading `renderTime` inside the draw lambda: writing it every
 * frame invalidates the **draw phase only**, so nothing recomposes at 60/120 fps. The HUD
 * is driven by [HudState], which is only reassigned when a number on screen really changes.
 */
@Composable
fun GameView(
    level: LevelData,
    onBackToTuning: () -> Unit,
    onNewSketch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = remember(level) { GameEngine(level) }
    val styleScale = remember(level) { GameRenderer.styleScale(level) }
    val haptics = LocalHapticFeedback.current

    var renderTime by remember(engine) { mutableFloatStateOf(0f) }
    var hud by remember(engine) { mutableStateOf(HudState.of(engine)) }
    val cameraFocus = remember(engine) { floatArrayOf(engine.playerCenterX, engine.playerCenterY) }

    // Games should not let the screen sleep mid-jump.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(engine) {
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val delta = if (lastFrameNanos == 0L) {
                    MIN_FRAME_DELTA
                } else {
                    ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
                        .coerceIn(MIN_FRAME_DELTA, MAX_FRAME_DELTA)
                }
                lastFrameNanos = now

                engine.update(delta)

                // Exponential camera smoothing; the constant is per-second, not per-frame.
                val follow = min(1f, delta * 9f)
                cameraFocus[0] += (engine.playerCenterX - cameraFocus[0]) * follow
                cameraFocus[1] += (engine.playerCenterY - cameraFocus[1]) * follow

                renderTime += delta
            }
            val snapshot = HudState.of(engine)
            if (snapshot != hud) hud = snapshot
        }
    }

    Box(modifier = modifier.fillMaxSize().background(InkBlack)) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading renderTime here is what schedules the next repaint.
            val time = renderTime

            // Frame the action around the player rather than the page: a level sampled at
            // 176 columns has a physically bigger player, and must not zoom out with it.
            val visibleRows = engine.playerHeight * if (size.width > size.height) 12f else 16f
            val camera = GameRenderer.followCamera(
                level = level,
                focusX = cameraFocus[0],
                focusY = cameraFocus[1],
                viewportWidth = size.width,
                viewportHeight = size.height,
                visibleRows = visibleRows,
            )

            with(GameRenderer) {
                drawPaper(camera, level.cols, level.rows, styleScale)
                withWorld(camera) {
                    drawSpawnMarker(level, time, styleScale)
                    level.goal?.let { drawGoal(it, time, styleScale) }
                    drawPlatforms(level.platforms, styleScale)
                    drawHazards(level.hazards, time, styleScale)
                    drawCoins(
                        level = level,
                        collected = engine.collected,
                        collectedAt = engine.collectedAt,
                        elapsedSeconds = engine.elapsedSeconds,
                        timeSeconds = time,
                        styleScale = styleScale,
                    )
                    drawPlayer(engine, time)
                }
                drawScrims()
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            GameHud(hud = hud, onLeave = onBackToTuning, modifier = Modifier.fillMaxWidth())

            TouchControls(
                enabled = hud.status == GameStatus.PLAYING,
                onLeft = { engine.moveLeft = it },
                onRight = { engine.moveRight = it },
                onJump = { pressed ->
                    if (pressed) {
                        engine.pressJump()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        engine.releaseJump()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AnimatedVisibility(
            visible = hud.status != GameStatus.PLAYING,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            ResultOverlay(
                hud = hud,
                onRetry = {
                    engine.moveLeft = false
                    engine.moveRight = false
                    engine.restart()
                    cameraFocus[0] = engine.playerCenterX
                    cameraFocus[1] = engine.playerCenterY
                },
                onTune = onBackToTuning,
                onNewSketch = onNewSketch,
            )
        }
    }
}

private const val MIN_FRAME_DELTA = 1f / 240f
private const val MAX_FRAME_DELTA = 1f / 15f

/** The slice of engine state the HUD shows, quantised so it changes ~10x a second. */
data class HudState(
    val coins: Int,
    val totalCoins: Int,
    val status: GameStatus,
    val tenthsOfSecond: Int,
    val attempts: Int,
) {
    val timeLabel: String get() = String.format(Locale.US, "%.1fs", tenthsOfSecond / 10f)

    companion object {
        fun of(engine: GameEngine) = HudState(
            coins = engine.coinsCollected,
            totalCoins = engine.totalCoins,
            status = engine.status,
            tenthsOfSecond = (engine.elapsedSeconds * 10f).toInt(),
            attempts = engine.attempts,
        )
    }
}

@Composable
private fun GameHud(hud: HudState, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onLeave) {
            Icon(Icons.Rounded.Close, contentDescription = "Leave level", tint = Color.White)
        }
        HudPill(text = "${hud.coins}/${hud.totalCoins}", accent = CoinGold, label = "Coins")
        HudPill(text = hud.timeLabel, accent = Color.White, label = "Time")
        if (hud.attempts > 1) {
            HudPill(text = "#${hud.attempts}", accent = LavaOrange, label = "Attempt")
        }
    }
}

@Composable
private fun HudPill(text: String, accent: Color, label: String) {
    Surface(
        color = InkBlack.copy(alpha = 0.55f),
        contentColor = accent,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { contentDescription = "$label: $text" },
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun TouchControls(
    enabled: Boolean,
    onLeft: (Boolean) -> Unit,
    onRight: (Boolean) -> Unit,
    onJump: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Movement controls are physical, not textual: in an RTL locale the button that moves
    // the player left must still sit on the left and point left.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .alpha(if (enabled) 1f else 0.35f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HoldButton(description = "Move left", enabled = enabled, onPressedChange = onLeft) {
                    Icon(
                        Icons.Rounded.ChevronLeft,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
                HoldButton(description = "Move right", enabled = enabled, onPressedChange = onRight) {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            HoldButton(
                description = "Jump",
                enabled = enabled,
                accent = LavaOrange,
                diameter = 88.dp,
                onPressedChange = onJump,
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}

/**
 * A button that reports press *and* release, which `Button`'s click callback cannot do:
 * holding left has to keep moving the player, and releasing jump has to cut it short.
 */
@Composable
private fun HoldButton(
    description: String,
    enabled: Boolean,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.White,
    diameter: Dp = 76.dp,
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    // Release the input if the control goes away or is disabled mid-press, so the player
    // never ends up running into a wall forever after dying.
    DisposableEffect(enabled) {
        if (!enabled && pressed) {
            pressed = false
            onPressedChange(false)
        }
        onDispose {
            if (pressed) {
                pressed = false
                onPressedChange(false)
            }
        }
    }

    Surface(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPressedChange(true)
                    waitForUpOrCancellation()
                    pressed = false
                    onPressedChange(false)
                }
            },
        shape = CircleShape,
        color = if (pressed) accent.copy(alpha = 0.35f) else InkBlack.copy(alpha = 0.45f),
        contentColor = accent,
        border = BorderStroke(2.dp, accent.copy(alpha = 0.7f)),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ResultOverlay(
    hud: HudState,
    onRetry: () -> Unit,
    onTune: () -> Unit,
    onNewSketch: () -> Unit,
) {
    val won = hud.status == GameStatus.WON
    Card(
        modifier = Modifier.padding(24.dp).width(320.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (won) "Level complete!" else "The floor was lava",
                style = MaterialTheme.typography.headlineSmall,
                color = if (won) SpringGreen else LavaOrange,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (won) {
                    "Coins ${hud.coins}/${hud.totalCoins}  ·  ${hud.timeLabel}  ·  attempt ${hud.attempts}"
                } else {
                    "You touched lava or fell off the page."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(if (won) "Play again" else "Try again")
            }
            OutlinedButton(onClick = onTune, modifier = Modifier.fillMaxWidth()) {
                Text("Tune this level")
            }
            TextButton(onClick = onNewSketch, modifier = Modifier.fillMaxWidth()) {
                Text("Draw a new level")
            }
        }
    }
}
