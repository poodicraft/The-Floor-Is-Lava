package com.paperjump.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.paperjump.processing.LevelData
import com.paperjump.processing.LevelRect
import com.paperjump.processing.LevelStroke
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.CreatureViolet
import com.paperjump.ui.theme.InkBlack
import com.paperjump.ui.theme.InkSoft
import com.paperjump.ui.theme.LavaEmber
import com.paperjump.ui.theme.LavaOrange
import com.paperjump.ui.theme.LavaRed
import com.paperjump.ui.theme.PaperCream
import com.paperjump.ui.theme.PaperShade
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Maps world units to screen pixels: `screen = (world - left/top) * scale`.
 */
data class WorldCamera(val left: Float, val top: Float, val scale: Float) {
    fun toScreen(worldX: Float, worldY: Float): Offset =
        Offset((worldX - left) * scale, (worldY - top) * scale)
}

/**
 * All canvas drawing for the level and the player.
 *
 * Every function draws in **world units** and is meant to be called inside [withWorld],
 * which installs the camera transform. Both the game and the "tune your level" preview
 * render through these, so what you tweak is exactly what you play.
 */
object GameRenderer {

    /** Above this many lava rectangles the animated surface is skipped to stay smooth. */
    private const val MAX_ANIMATED_LAVA_RECTS = 80

    /**
     * How many world units one "reference cell" is worth in this level.
     *
     * Line weights, corner radii and wobble amplitudes are authored against a 24-column
     * page and multiplied by this, so a 176-column sketch does not render hairlines.
     */
    fun styleScale(level: LevelData): Float = Tuning.scaleFor(level)

    fun DrawScope.withWorld(camera: WorldCamera, block: DrawScope.() -> Unit) {
        withTransform({
            translate(left = -camera.left * camera.scale, top = -camera.top * camera.scale)
            scale(scaleX = camera.scale, scaleY = camera.scale, pivot = Offset.Zero)
        }) {
            block()
        }
    }

    /** Paper background plus the faint squared-notebook grid, drawn in screen space. */
    fun DrawScope.drawPaper(camera: WorldCamera, cols: Int, rows: Int, styleScale: Float = 1f) {
        drawRect(
            brush = Brush.verticalGradient(listOf(PaperCream, PaperShade)),
            topLeft = Offset.Zero,
            size = size,
        )

        val step = 4f * styleScale
        val gridColor = InkBlack.copy(alpha = 0.06f)
        val firstCol = max(0f, (camera.left / step).toInt() * step)
        val lastCol = min(cols.toFloat(), camera.left + size.width / camera.scale)
        var x = firstCol
        while (x <= lastCol) {
            val screenX = (x - camera.left) * camera.scale
            drawLine(gridColor, Offset(screenX, 0f), Offset(screenX, size.height), strokeWidth = 1f)
            x += step
        }

        val firstRow = max(0f, (camera.top / step).toInt() * step)
        val lastRow = min(rows.toFloat(), camera.top + size.height / camera.scale)
        var y = firstRow
        while (y <= lastRow) {
            val screenY = (y - camera.top) * camera.scale
            drawLine(gridColor, Offset(0f, screenY), Offset(size.width, screenY), strokeWidth = 1f)
            y += step
        }
    }

    /** Solid ink: the platforms the player stands on. */
    fun DrawScope.drawPlatforms(
        platforms: List<LevelRect>,
        strokes: List<LevelStroke> = emptyList(),
        styleScale: Float = 1f,
        /** In [GameMode.FLYER] the ink kills, so it must not read as something to stand on. */
        deadly: Boolean = false,
    ) {
        val corner = CornerRadius(0.12f * styleScale, 0.12f * styleScale)
        val body = if (deadly) Color(0xFF6B2018) else InkSoft
        val cap = if (deadly) LavaRed else Color(0xFF4A4238)

        // Anything the detector read as a line is drawn as one, so a ledge drawn at a slant
        // is a slanted bar rather than a staircase of blocks.
        strokes.forEach { stroke ->
            drawCapsule(stroke, body)
            drawCapsule(
                stroke = stroke,
                color = cap,
                widthScale = 0.34f,
                offsetY = -stroke.thickness * 0.3f,
            )
        }

        platforms.forEach { rect ->
            drawRoundRect(
                color = body,
                topLeft = Offset(rect.x, rect.y),
                size = Size(rect.width, rect.height),
                cornerRadius = corner,
            )
            // A lighter cap on top so ledges read clearly against the paper — and a hot one
            // when the ink is deadly, so it never invites a landing.
            drawRect(
                color = cap,
                topLeft = Offset(rect.x, rect.y),
                size = Size(rect.width, min(0.14f * styleScale, rect.height * 0.4f)),
            )
        }
    }

    /** One line, drawn as a rounded bar of the given thickness. */
    private fun DrawScope.drawCapsule(
        stroke: LevelStroke,
        color: Color,
        widthScale: Float = 1f,
        offsetY: Float = 0f,
    ) {
        drawLine(
            color = color,
            start = Offset(stroke.x1, stroke.y1 + offsetY),
            end = Offset(stroke.x2, stroke.y2 + offsetY),
            strokeWidth = stroke.thickness * widthScale,
            cap = StrokeCap.Round,
        )
    }

    /** Lava: a hot gradient with a wobbling surface. */
    fun DrawScope.drawHazards(
        hazards: List<LevelRect>,
        timeSeconds: Float,
        styleScale: Float = 1f,
        strokes: List<LevelStroke> = emptyList(),
    ) {
        strokes.forEach { stroke ->
            drawCapsule(stroke, LavaRed)
            // A brighter core that pulses, so a lava line reads as hot rather than as a
            // red platform.
            drawCapsule(
                stroke = stroke,
                color = LavaEmber.copy(alpha = 0.75f + 0.2f * sin(timeSeconds * 3.2f)),
                widthScale = 0.45f,
            )
        }

        val animate = hazards.size <= MAX_ANIMATED_LAVA_RECTS
        hazards.forEach { rect ->
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(LavaOrange, LavaRed),
                    startY = rect.y,
                    endY = rect.bottom + 0.5f * styleScale,
                ),
                topLeft = Offset(rect.x, rect.y),
                size = Size(rect.width, rect.height),
            )
            if (!animate) return@forEach

            val amplitude = 0.16f * styleScale
            val crest = 0.35f * styleScale
            val wave = Path().apply {
                moveTo(rect.x, rect.y)
                var x = rect.x
                while (x < rect.right) {
                    val next = min(x + 0.4f * styleScale, rect.right)
                    val phase = (next * 1.7f / styleScale) + timeSeconds * 3.2f
                    lineTo(next, rect.y + sin(phase) * amplitude)
                    x = next
                }
                lineTo(rect.right, rect.y + crest)
                lineTo(rect.x, rect.y + crest)
                close()
            }
            drawPath(wave, color = LavaEmber.copy(alpha = 0.85f))
        }
    }

    /** Coins bob, spin (via a squashed width) and pop when picked up. */
    fun DrawScope.drawCoins(
        level: LevelData,
        collected: BooleanArray?,
        collectedAt: FloatArray?,
        elapsedSeconds: Float,
        timeSeconds: Float,
        styleScale: Float = 1f,
    ) {
        level.coins.forEach { coin ->
            val isCollected = collected?.getOrNull(coin.index) == true
            val pickedAt = collectedAt?.getOrNull(coin.index) ?: -1f
            val sincePickup = if (isCollected && pickedAt >= 0f) elapsedSeconds - pickedAt else -1f

            if (isCollected && (sincePickup < 0f || sincePickup > COIN_POP_SECONDS)) return@forEach

            val bob = sin(timeSeconds * 2.6f + coin.index) * 0.09f * styleScale
            val progress = if (sincePickup >= 0f) (sincePickup / COIN_POP_SECONDS).coerceIn(0f, 1f) else 0f
            val radius = coin.radius * (1f + progress * 0.8f)
            val alpha = 1f - progress
            val center = Offset(coin.center.x, coin.center.y + bob - progress * 0.6f * styleScale)
            // Squashing the horizontal radius fakes a spinning coin without a sprite.
            val spin = abs(cos(timeSeconds * 2.2f + coin.index * 0.7f)).coerceAtLeast(0.25f)

            withTransform({ scale(scaleX = spin, scaleY = 1f, pivot = center) }) {
                drawCircle(color = CoinGold.copy(alpha = alpha), radius = radius, center = center)
                drawCircle(
                    color = Color(0xFF8A6A00).copy(alpha = alpha * 0.9f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 0.07f * styleScale),
                )
            }
        }
    }

    /**
     * The purple creatures.
     *
     * Called with live positions while playing, and with `null` from the level preview,
     * where they sit exactly where they were drawn — the preview has no simulation running,
     * and a creature frozen at its mark is what the player drew anyway.
     */
    fun DrawScope.drawEnemies(
        level: LevelData,
        engine: GameEngine?,
        timeSeconds: Float,
        styleScale: Float = 1f,
    ) {
        if (level.enemies.isEmpty()) return
        val width = engine?.enemyWidth ?: (0.95f * styleScale)
        val height = engine?.enemyHeight ?: (0.8f * styleScale)

        level.enemies.forEach { enemy ->
            val index = enemy.index
            if (engine?.enemyDefeated?.getOrNull(index) == true) return@forEach

            val centerX = engine?.enemyX?.getOrNull(index) ?: enemy.center.x
            val centerY = engine?.enemyY?.getOrNull(index) ?: enemy.center.y
            val facing = engine?.enemyDirection?.getOrNull(index) ?: 1f
            // A waddle: squashed a little, in time, so it reads as alive at a glance.
            val waddle = sin(timeSeconds * 6f + index) * 0.08f
            val bodyHeight = height * (1f + waddle)
            val bodyWidth = width * (1f - waddle * 0.6f)
            val left = centerX - bodyWidth / 2f
            val top = centerY - bodyHeight / 2f
            val corner = CornerRadius(bodyWidth * 0.42f, bodyWidth * 0.42f)

            // Feet, poking out from under the body as it walks.
            listOf(-0.26f, 0.26f).forEachIndexed { foot, side ->
                val step = sin(timeSeconds * 9f + index + foot * 3.14f) * bodyWidth * 0.12f
                drawCircle(
                    color = Color(0xFF5B1580),
                    radius = bodyWidth * 0.17f,
                    center = Offset(centerX + bodyWidth * side + step, top + bodyHeight),
                )
            }

            drawRoundRect(
                color = InkBlack.copy(alpha = 0.18f),
                topLeft = Offset(left + bodyWidth * 0.08f, top + bodyHeight * 0.1f),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = CreatureViolet,
                topLeft = Offset(left, top),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = InkBlack.copy(alpha = 0.6f),
                topLeft = Offset(left, top),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = corner,
                style = Stroke(width = bodyWidth * 0.1f),
            )

            val lookX = bodyWidth * 0.14f * (if (facing >= 0f) 1f else -1f)
            val eyeY = top + bodyHeight * 0.36f
            val eyeRadius = bodyWidth * 0.16f
            listOf(-0.19f, 0.19f).forEach { side ->
                val eyeX = centerX + lookX + bodyWidth * side
                drawCircle(color = Color.White, radius = eyeRadius, center = Offset(eyeX, eyeY))
                drawCircle(
                    color = InkBlack,
                    radius = eyeRadius * 0.55f,
                    center = Offset(eyeX + lookX * 0.3f, eyeY),
                )
            }
            // A flat scowl, which is all it takes to look like it means harm.
            drawLine(
                color = InkBlack.copy(alpha = 0.7f),
                start = Offset(centerX - bodyWidth * 0.18f, top + bodyHeight * 0.68f),
                end = Offset(centerX + bodyWidth * 0.18f, top + bodyHeight * 0.68f),
                strokeWidth = bodyWidth * 0.08f,
                cap = StrokeCap.Round,
            )
        }
    }

    /** The goal flag: a pole with a banner that ripples. */
    fun DrawScope.drawGoal(
        goal: LevelRect,
        timeSeconds: Float,
        styleScale: Float = 1f,
        locked: Boolean = false,
    ) {
        drawRect(
            color = SkyBlue.copy(alpha = if (locked) 0.07f else 0.18f),
            topLeft = Offset(goal.x, goal.y),
            size = Size(goal.width, goal.height),
        )
        val poleX = goal.x + goal.width * 0.15f
        drawLine(
            color = InkSoft,
            start = Offset(poleX, goal.y),
            end = Offset(poleX, goal.bottom),
            strokeWidth = 0.16f * styleScale,
        )
        val flagHeight = min(1.6f * styleScale, goal.height * 0.45f)
        val flagWidth = max(1.2f * styleScale, goal.width * 0.8f)
        val ripple = sin(timeSeconds * 4f) * 0.18f * styleScale
        val banner = Path().apply {
            moveTo(poleX, goal.y + 0.1f)
            lineTo(poleX + flagWidth, goal.y + 0.1f + flagHeight * 0.35f + ripple)
            lineTo(poleX + flagWidth * 0.75f, goal.y + 0.1f + flagHeight * 0.6f)
            lineTo(poleX + flagWidth, goal.y + 0.1f + flagHeight * 0.85f + ripple)
            lineTo(poleX, goal.y + 0.1f + flagHeight)
            close()
        }
        // A locked flag (Coin hunt, coins still on the page) hangs limp and grey, so the
        // player can see at a glance that touching it will do nothing yet.
        drawPath(banner, color = if (locked) InkSoft.copy(alpha = 0.55f) else SkyBlue)
    }

    /**
     * The flood under [com.paperjump.game.Twist.RISING_LAVA].
     *
     * Drawn across the whole page rather than only the visible slice: the surface is a
     * single horizontal line, so there is nothing to gain from clipping it, and the wave
     * has to stay continuous when the camera pans.
     */
    fun DrawScope.drawRisingLava(
        surfaceY: Float,
        level: LevelData,
        timeSeconds: Float,
        styleScale: Float = 1f,
    ) {
        val bottom = level.height + 6f * styleScale
        if (surfaceY >= bottom) return

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(LavaOrange.copy(alpha = 0.92f), LavaRed),
                startY = surfaceY,
                endY = bottom,
            ),
            topLeft = Offset(0f, surfaceY),
            size = Size(level.width, bottom - surfaceY),
        )

        val amplitude = 0.22f * styleScale
        val crest = Path().apply {
            moveTo(0f, surfaceY)
            var x = 0f
            while (x < level.width) {
                val next = min(x + 0.4f * styleScale, level.width)
                val phase = (next * 1.4f / styleScale) + timeSeconds * 2.4f
                lineTo(next, surfaceY + sin(phase) * amplitude)
                x = next
            }
            lineTo(level.width, surfaceY + 0.45f * styleScale)
            lineTo(0f, surfaceY + 0.45f * styleScale)
            close()
        }
        drawPath(crest, color = LavaEmber.copy(alpha = 0.9f))
    }

    /** The spawn marker, so players can find their way back to the start. */
    fun DrawScope.drawSpawnMarker(level: LevelData, timeSeconds: Float, styleScale: Float = 1f) {
        val pulse = 0.5f + 0.5f * sin(timeSeconds * 2f)
        drawCircle(
            color = SpringGreen.copy(alpha = 0.18f + pulse * 0.12f),
            radius = 0.75f * styleScale,
            center = Offset(level.spawn.x, level.spawn.y),
        )
        drawCircle(
            color = SpringGreen.copy(alpha = 0.55f),
            radius = 0.28f * styleScale,
            center = Offset(level.spawn.x, level.spawn.y),
        )
    }

    /**
     * The player: a rounded ink blob that stretches when it jumps and squashes when it
     * lands, with eyes that look the way it is running.
     */
    fun DrawScope.drawPlayer(engine: GameEngine, timeSeconds: Float) {
        val width = engine.playerWidth
        val height = engine.playerHeight

        // Squash on landing, stretch while falling. Everything below is expressed as a
        // fraction of the body, so it stays right at any grid resolution.
        val stretch = (engine.velocityY / engine.tuning.maxFallSpeed).coerceIn(-0.35f, 0.35f)
        val bodyHeight = height * (1f + abs(stretch) * 0.35f * if (engine.isOnGround) -1f else 1f)
        val bodyWidth = width * (height / bodyHeight) // keeps the silhouette's area roughly constant

        val left = engine.playerCenterX - bodyWidth / 2f
        val top = engine.playerY + (height - bodyHeight)
        val corner = CornerRadius(bodyWidth * 0.4f, bodyWidth * 0.4f)

        val bodyColor = when (engine.status) {
            GameStatus.DEAD -> LavaRed
            GameStatus.WON -> SpringGreen
            GameStatus.PLAYING -> Color(0xFF2F6BFF)
        }

        drawRoundRect(
            color = InkBlack.copy(alpha = 0.18f),
            topLeft = Offset(left + bodyWidth * 0.08f, top + bodyHeight * 0.1f),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(left, top),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = InkBlack.copy(alpha = 0.65f),
            topLeft = Offset(left, top),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = corner,
            style = Stroke(width = bodyWidth * 0.11f),
        )

        val lookX = if (engine.facingRight) bodyWidth * 0.16f else -bodyWidth * 0.16f
        val eyeY = top + bodyHeight * 0.34f
        val eyeRadius = bodyWidth * 0.17f
        val pupilRadius = if (engine.status == GameStatus.DEAD) eyeRadius * 0.2f else eyeRadius * 0.75f
        listOf(-0.2f, 0.2f).forEach { side ->
            val eyeX = engine.playerCenterX + lookX + bodyWidth * side
            drawCircle(color = Color.White, radius = eyeRadius, center = Offset(eyeX, eyeY))
            drawCircle(
                color = InkBlack,
                radius = pupilRadius,
                center = Offset(eyeX + lookX * 0.2f, eyeY),
            )
        }

        // Dust puffs while running on the ground.
        if (engine.isOnGround && abs(engine.velocityX) > engine.tuning.moveSpeed * 0.35f) {
            val direction = if (engine.velocityX > 0f) -1f else 1f
            for (i in 0 until 3) {
                val phase = (timeSeconds * 6f + i * 0.33f) % 1f
                drawCircle(
                    color = PaperShade.copy(alpha = (1f - phase) * 0.7f),
                    radius = width * 0.23f * (1f - phase),
                    center = Offset(
                        engine.playerCenterX + direction * width * (0.45f + phase),
                        engine.playerY + height - height * 0.05f,
                    ),
                )
            }
        }
    }

    /** Fades the very top and bottom of the viewport so HUD text stays readable. */
    fun DrawScope.drawScrims() {
        val fade = size.height * 0.16f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(InkBlack.copy(alpha = 0.35f), Color.Transparent),
                startY = 0f,
                endY = fade,
            ),
            size = Size(size.width, fade),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, InkBlack.copy(alpha = 0.45f)),
                startY = size.height - fade * 1.6f,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - fade * 1.6f),
            size = Size(size.width, fade * 1.6f),
        )
    }

    /**
     * Fits the whole level into [viewportWidth] x [viewportHeight] — used by the preview.
     */
    fun fitCamera(level: LevelData, viewportWidth: Float, viewportHeight: Float): WorldCamera {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return WorldCamera(0f, 0f, 1f)
        val scale = min(viewportWidth / level.width, viewportHeight / level.height)
        val left = -(viewportWidth / scale - level.width) / 2f
        val top = -(viewportHeight / scale - level.height) / 2f
        return WorldCamera(left, top, scale)
    }

    /**
     * A camera that follows the player, shows [visibleRows] world rows and never scrolls
     * past the edge of the paper (unless the level is smaller than the screen, in which
     * case it is centred).
     */
    fun followCamera(
        level: LevelData,
        focusX: Float,
        focusY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        visibleRows: Float = 15f,
    ): WorldCamera {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return WorldCamera(0f, 0f, 1f)
        val rowsOnScreen = min(visibleRows, level.height.coerceAtLeast(1f))
        val scale = viewportHeight / rowsOnScreen
        val worldViewWidth = viewportWidth / scale
        val worldViewHeight = viewportHeight / scale

        val left = if (worldViewWidth >= level.width) {
            -(worldViewWidth - level.width) / 2f
        } else {
            (focusX - worldViewWidth / 2f).coerceIn(0f, level.width - worldViewWidth)
        }
        val top = if (worldViewHeight >= level.height) {
            -(worldViewHeight - level.height) / 2f
        } else {
            (focusY - worldViewHeight / 2f).coerceIn(0f, level.height - worldViewHeight)
        }
        return WorldCamera(left, top, scale)
    }

    /** Number of world columns visible at the given camera — handy for debugging. */
    fun visibleCols(camera: WorldCamera, viewportWidth: Float): Int =
        ceil(viewportWidth / camera.scale).toInt()

    private const val COIN_POP_SECONDS = 0.45f
}
