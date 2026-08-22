package com.paperjump.game

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
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.paperjump.data.AppSettings
import com.paperjump.data.LevelRecord
import com.paperjump.processing.LevelData
import com.paperjump.ui.components.PaperButton
import com.paperjump.ui.components.PaperOutlineButton
import com.paperjump.ui.components.PaperQuietButton
import com.paperjump.ui.components.StickerBadge
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.InkBlack
import com.paperjump.ui.theme.LavaOrange
import com.paperjump.ui.theme.LavaRed
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen
import java.util.Locale
import kotlin.math.min

/**
 * The playable screen: canvas, game loop, touch controls, pause and the result overlay.
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
    setup: GameSetup,
    settings: AppSettings,
    record: LevelRecord,
    wasPersonalBest: Boolean,
    onRunFinished: (won: Boolean, seconds: Float, coins: Int) -> Unit,
    onChangeMode: () -> Unit,
    onTune: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = remember(level, setup) { GameEngine(level, setup) }
    val mode = setup.mode
    val styleScale = remember(level) { GameRenderer.styleScale(level) }
    val haptics = LocalHapticFeedback.current

    var renderTime by remember(engine) { mutableFloatStateOf(0f) }
    var hud by remember(engine) { mutableStateOf(HudState.of(engine)) }
    var isPaused by remember(engine) { mutableStateOf(false) }
    val cameraFocus = remember(engine) { floatArrayOf(engine.playerCenterX, engine.playerCenterY) }

    // The loop lives for the whole screen, so it must not capture a stale callback.
    val reportRun by rememberUpdatedState(onRunFinished)

    // Games should not let the screen sleep mid-jump.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(engine) {
        var lastFrameNanos = 0L
        var reportedAttempt = 0
        while (true) {
            withFrameNanos { now ->
                val delta = if (lastFrameNanos == 0L) {
                    MIN_FRAME_DELTA
                } else {
                    ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
                        .coerceIn(MIN_FRAME_DELTA, MAX_FRAME_DELTA)
                }
                lastFrameNanos = now

                if (!isPaused) {
                    engine.update(delta)

                    // Exponential camera smoothing; the constant is per-second, not per-frame.
                    val follow = min(1f, delta * 9f)
                    cameraFocus[0] += (engine.playerCenterX - cameraFocus[0]) * follow
                    cameraFocus[1] += (engine.playerCenterY - cameraFocus[1]) * follow
                }

                renderTime += delta
            }

            val snapshot = HudState.of(engine)
            if (snapshot != hud) hud = snapshot

            // Report each run exactly once, however many frames it stays on the overlay.
            if (engine.status != GameStatus.PLAYING && reportedAttempt != engine.attempts) {
                reportedAttempt = engine.attempts
                reportRun(
                    engine.status == GameStatus.WON,
                    engine.elapsedSeconds,
                    engine.coinsCollected,
                )
            }
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
                    level.goal?.let { drawGoal(it, time, styleScale, locked = engine.isGoalLocked) }
                    drawPlatforms(
                        platforms = level.platforms,
                        strokes = level.platformStrokes,
                        styleScale = styleScale,
                        deadly = mode.inkIsDeadly,
                    )
                    drawHazards(level.hazards, time, styleScale, level.hazardStrokes)
                    drawEnemies(level, engine, time, styleScale)
                    drawCoins(
                        level = level,
                        collected = engine.collected,
                        collectedAt = engine.collectedAt,
                        elapsedSeconds = engine.elapsedSeconds,
                        timeSeconds = time,
                        styleScale = styleScale,
                    )
                    drawPlayer(engine, time)
                    engine.lavaSurfaceY?.let { drawRisingLava(it, level, time, styleScale) }
                }
                drawScrims()
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            GameHud(
                hud = hud,
                setup = setup,
                showTimer = settings.showTimer,
                onPause = { isPaused = true },
                modifier = Modifier.fillMaxWidth(),
            )

            TouchControls(
                scheme = mode.controls,
                enabled = hud.status == GameStatus.PLAYING && !isPaused,
                jumpOnRight = settings.jumpOnRight,
                scale = settings.controlScale,
                onLeft = { engine.moveLeft = it },
                onRight = { engine.moveRight = it },
                onUp = { engine.moveUp = it },
                onDown = { engine.moveDown = it },
                onJump = { pressed ->
                    if (pressed) {
                        engine.pressJump()
                        if (settings.haptics) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else {
                        engine.releaseJump()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val restart = {
            engine.moveLeft = false
            engine.moveRight = false
            engine.moveUp = false
            engine.moveDown = false
            engine.restart()
            cameraFocus[0] = engine.playerCenterX
            cameraFocus[1] = engine.playerCenterY
        }

        AnimatedVisibility(
            visible = hud.status != GameStatus.PLAYING,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            ResultOverlay(
                hud = hud,
                setup = setup,
                record = record,
                wasPersonalBest = wasPersonalBest,
                onRetry = restart,
                onChangeMode = onChangeMode,
                onTune = onTune,
                onQuit = onQuit,
            )
        }

        AnimatedVisibility(
            visible = isPaused && hud.status == GameStatus.PLAYING,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            PauseOverlay(
                setup = setup,
                onResume = { isPaused = false },
                onRestart = {
                    restart()
                    isPaused = false
                },
                onChangeMode = onChangeMode,
                onQuit = onQuit,
            )
        }
    }
}

private const val MIN_FRAME_DELTA = 1f / 240f
private const val MAX_FRAME_DELTA = 1f / 15f

/** Under this many seconds left, the countdown turns red and starts nagging. */
private const val CLOCK_PANIC_SECONDS = 5f

/** The slice of engine state the HUD shows, quantised so it changes ~10x a second. */
data class HudState(
    val coins: Int,
    val totalCoins: Int,
    val status: GameStatus,
    val tenthsOfSecond: Int,
    val attempts: Int,
    val remainingTenths: Int?,
    val goalLocked: Boolean,
    val deathCause: DeathCause,
) {
    val seconds: Float get() = tenthsOfSecond / 10f
    val timeLabel: String get() = String.format(Locale.US, "%.1fs", seconds)
    val remainingLabel: String?
        get() = remainingTenths?.let { String.format(Locale.US, "%.1fs", it / 10f) }
    val isPanicking: Boolean
        get() = remainingTenths != null && remainingTenths <= (CLOCK_PANIC_SECONDS * 10).toInt()

    companion object {
        fun of(engine: GameEngine) = HudState(
            coins = engine.coinsCollected,
            totalCoins = engine.totalCoins,
            status = engine.status,
            tenthsOfSecond = (engine.elapsedSeconds * 10f).toInt(),
            attempts = engine.attempts,
            remainingTenths = engine.timeRemaining?.let { (it * 10f).toInt() },
            goalLocked = engine.isGoalLocked,
            deathCause = engine.deathCause,
        )
    }
}

@Composable
private fun GameHud(
    hud: HudState,
    setup: GameSetup,
    showTimer: Boolean,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onPause) {
            Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = Color.White)
        }

        // Coin hunt always shows the counter, even at 0/0, because the flag's lock
        // depends on it.
        if (hud.totalCoins > 0 || setup.twist == Twist.COIN_HUNT) {
            HudPill(
                text = "${hud.coins}/${hud.totalCoins}",
                accent = if (hud.goalLocked) CoinGold else SpringGreen,
                label = if (hud.goalLocked) "Coins still to collect" else "Coins",
            )
        }

        hud.remainingLabel?.let { remaining ->
            HudPill(
                text = remaining,
                accent = if (hud.isPanicking) LavaRed else Color.White,
                label = "Time left",
            )
        }

        if (showTimer && hud.remainingLabel == null) {
            HudPill(text = hud.timeLabel, accent = Color.White, label = "Time")
        }

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

/**
 * The on-screen controls, which differ per game: a maze is steered in four directions, a
 * runner only jumps, a flyer only flaps.
 */
@Composable
private fun TouchControls(
    scheme: ControlScheme,
    enabled: Boolean,
    jumpOnRight: Boolean,
    scale: Float,
    onLeft: (Boolean) -> Unit,
    onRight: (Boolean) -> Unit,
    onUp: (Boolean) -> Unit,
    onDown: (Boolean) -> Unit,
    onJump: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val moveDiameter = (72 * scale).dp
    val actionDiameter = (88 * scale).dp
    val iconSize = (38 * scale).dp

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
            val leftRight: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HoldButton("Move left", enabled, onLeft, diameter = moveDiameter) {
                        Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(iconSize))
                    }
                    HoldButton("Move right", enabled, onRight, diameter = moveDiameter) {
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(iconSize))
                    }
                }
            }

            // A cross, so up and down are reachable without hunting for them.
            val dPad: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HoldButton("Move up", enabled, onUp, diameter = moveDiameter) {
                        Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(iconSize))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(moveDiameter * 0.18f)) {
                        HoldButton("Move left", enabled, onLeft, diameter = moveDiameter) {
                            Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(iconSize))
                        }
                        HoldButton("Move right", enabled, onRight, diameter = moveDiameter) {
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(iconSize))
                        }
                    }
                    HoldButton("Move down", enabled, onDown, diameter = moveDiameter) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(iconSize))
                    }
                }
            }

            val actionButton: @Composable (String, ImageVector) -> Unit = { label, icon ->
                HoldButton(
                    description = label,
                    enabled = enabled,
                    onPressedChange = onJump,
                    accent = LavaOrange,
                    diameter = actionDiameter,
                ) {
                    Icon(icon, null, Modifier.size(iconSize * 1.1f))
                }
            }

            when (scheme) {
                ControlScheme.RUN_AND_JUMP -> if (jumpOnRight) {
                    leftRight()
                    actionButton("Jump", Icons.Rounded.KeyboardArrowUp)
                } else {
                    actionButton("Jump", Icons.Rounded.KeyboardArrowUp)
                    leftRight()
                }

                ControlScheme.EIGHT_WAY -> if (jumpOnRight) {
                    dPad()
                    Spacer(Modifier.size(moveDiameter))
                } else {
                    Spacer(Modifier.size(moveDiameter))
                    dPad()
                }

                // One button, so put it under the thumb the player chose and nothing else.
                ControlScheme.JUMP_ONLY, ControlScheme.FLAP_ONLY -> {
                    val label = if (scheme == ControlScheme.FLAP_ONLY) "Flap" else "Jump"
                    val icon = if (scheme == ControlScheme.FLAP_ONLY) {
                        Icons.Rounded.Air
                    } else {
                        Icons.Rounded.KeyboardArrowUp
                    }
                    if (jumpOnRight) {
                        Spacer(Modifier.size(actionDiameter))
                        actionButton(label, icon)
                    } else {
                        actionButton(label, icon)
                        Spacer(Modifier.size(actionDiameter))
                    }
                }
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
private fun PauseOverlay(
    setup: GameSetup,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onChangeMode: () -> Unit,
    onQuit: () -> Unit,
) {
    OverlayCard(title = "Paused", subtitle = setup.label, accent = SkyBlue) {
        PaperButton(
            text = "Resume",
            icon = Icons.Rounded.PlayArrow,
            onClick = onResume,
            modifier = Modifier.fillMaxWidth(),
            accent = SpringGreen,
        )
        PaperOutlineButton(
            text = "Restart level",
            icon = Icons.Rounded.Refresh,
            onClick = onRestart,
            modifier = Modifier.fillMaxWidth(),
        )
        PaperOutlineButton(
            text = "Change game",
            onClick = onChangeMode,
            modifier = Modifier.fillMaxWidth(),
        )
        PaperQuietButton(
            text = "Quit to menu",
            onClick = onQuit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResultOverlay(
    hud: HudState,
    setup: GameSetup,
    record: LevelRecord,
    wasPersonalBest: Boolean,
    onRetry: () -> Unit,
    onChangeMode: () -> Unit,
    onTune: () -> Unit,
    onQuit: () -> Unit,
) {
    val won = hud.status == GameStatus.WON
    OverlayCard(
        title = if (won) headlineFor(setup.mode) else deathHeadline(hud.deathCause),
        subtitle = if (won) {
            buildString {
                append("Coins ${hud.coins}/${hud.totalCoins}")
                append("  ·  ${hud.timeLabel}")
                if (hud.attempts > 1) append("  ·  attempt ${hud.attempts}")
            }
        } else {
            deathExplanation(hud.deathCause)
        },
        accent = if (won) SpringGreen else LavaOrange,
    ) {
        if (won && wasPersonalBest) {
            StickerBadge(text = "New best time!", accent = CoinGold)
        } else if (won) {
            record.bestTimeSeconds?.let {
                Text(
                    text = String.format(Locale.US, "Your best: %.1fs", it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        PaperButton(
            text = if (won) "Play again" else "Try again",
            icon = if (won) Icons.Rounded.PlayArrow else Icons.Rounded.Refresh,
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            accent = if (won) SpringGreen else LavaOrange,
        )
        PaperOutlineButton(
            text = "Change game",
            onClick = onChangeMode,
            modifier = Modifier.fillMaxWidth(),
        )
        PaperQuietButton(
            text = "Tune this level",
            onClick = onTune,
            modifier = Modifier.fillMaxWidth(),
        )
        PaperQuietButton(
            text = "Quit to menu",
            onClick = onQuit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OverlayCard(
    title: String,
    subtitle: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.padding(24.dp).width(320.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = accent,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            content()
        }
    }
}

private fun headlineFor(mode: GameMode): String = when (mode) {
    GameMode.PLATFORMER -> "Level complete!"
    GameMode.MAZE -> "Out of the maze!"
    GameMode.FLYER -> "Flew it clean!"
    GameMode.RUNNER -> "Ran it to the end!"
}

private fun deathHeadline(cause: DeathCause): String = when (cause) {
    DeathCause.TIME_UP -> "Out of time"
    DeathCause.FLOODED -> "Swallowed by the lava"
    DeathCause.FELL -> "Off the page"
    DeathCause.CRASHED -> "Crashed"
    DeathCause.ENEMY -> "Caught!"
    else -> "Burnt to a crisp"
}

private fun deathExplanation(cause: DeathCause): String = when (cause) {
    DeathCause.TIME_UP -> "The clock ran out before you reached the flag."
    DeathCause.FLOODED -> "The rising lava caught up with you. Keep climbing."
    DeathCause.FELL -> "You fell off the bottom of the page."
    DeathCause.CRASHED -> "You hit the ink. Out here it is scenery to steer around, " +
        "not something to land on."
    DeathCause.ENEMY -> "One of the creatures got you. Land on top of one to squash it."
    else -> "You touched the lava."
}
