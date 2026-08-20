package com.paperjump.game

import com.paperjump.processing.LevelData
import com.paperjump.processing.LevelRect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class GameStatus { PLAYING, WON, DEAD }

/**
 * Every physics constant in one place. Units are grid cells and seconds, so
 * `gravity = 52` means "52 cells per second squared".
 */
data class Tuning(
    val gravity: Float = 52f,
    /** Falling is faster than rising — the classic platformer "snappy arc". */
    val fallGravityMultiplier: Float = 1.5f,
    val maxFallSpeed: Float = 34f,
    val moveSpeed: Float = 9.5f,
    val groundAcceleration: Float = 95f,
    val airAcceleration: Float = 58f,
    val groundFriction: Float = 75f,
    val airFriction: Float = 14f,
    val jumpSpeed: Float = 17f,
    /** Releasing the button early clips the jump down to this much upward speed. */
    val minJumpSpeed: Float = 7.5f,
    /** Grace period after walking off a ledge during which a jump still works. */
    val coyoteTime: Float = 0.10f,
    /** Pressing jump slightly before landing still jumps on touchdown. */
    val jumpBufferTime: Float = 0.12f,
    val playerWidth: Float = 0.7f,
    val playerHeight: Float = 0.92f,
    /** Physics runs at a fixed rate regardless of display refresh rate. */
    val fixedStep: Float = 1f / 120f,
    /** How far below the sketch the player has to fall before it counts as a death. */
    val fallOutMargin: Float = 3f,
) {
    companion object {
        /**
         * Grid width the constants above are authored against: the player is one 24th of
         * the page wide, can jump ~18% of the page's height and ~23% of its width.
         */
        const val REFERENCE_COLS = 24f

        /** How much bigger this level's grid is than the reference. */
        fun scaleFor(level: LevelData): Float =
            (level.width / REFERENCE_COLS).coerceIn(0.5f, 12f)

        /**
         * Scales every *length* by the grid resolution while leaving every *duration*
         * alone, so a level plays identically whether it was sampled at 40 or 176 columns.
         *
         * Without this the detail slider would double as a difficulty slider: at 96
         * columns a fixed 0.7-cell player would be 1/140th of the page and a gap anyone
         * would draw as "one jump wide" would be impossible.
         */
        fun forLevel(level: LevelData, base: Tuning = Tuning()): Tuning {
            val scale = scaleFor(level)
            return base.copy(
                gravity = base.gravity * scale,
                maxFallSpeed = base.maxFallSpeed * scale,
                moveSpeed = base.moveSpeed * scale,
                groundAcceleration = base.groundAcceleration * scale,
                airAcceleration = base.airAcceleration * scale,
                groundFriction = base.groundFriction * scale,
                airFriction = base.airFriction * scale,
                jumpSpeed = base.jumpSpeed * scale,
                minJumpSpeed = base.minJumpSpeed * scale,
                playerWidth = base.playerWidth * scale,
                playerHeight = base.playerHeight * scale,
                fallOutMargin = base.fallOutMargin * scale,
                // Faster world, finer steps: keeps the distance travelled per step — and
                // therefore the collision resolver's tunnelling margin — unchanged.
                fixedStep = base.fixedStep / scale,
            )
        }
    }
}

/**
 * The whole game: gravity, AABB collision against the detected ink grid, coins, lava
 * and the goal.
 *
 * Deliberately framework-free (no Compose, no Android) so it can be unit tested and so the
 * renderer stays a pure function of this state. [GameView] drives it with `withFrameNanos`.
 */
class GameEngine(
    val level: LevelData,
    val tuning: Tuning = Tuning.forLevel(level),
) {
    var playerX: Float = 0f
        private set
    var playerY: Float = 0f
        private set
    var velocityX: Float = 0f
        private set
    var velocityY: Float = 0f
        private set
    var isOnGround: Boolean = false
        private set
    var facingRight: Boolean = true
        private set

    var status: GameStatus = GameStatus.PLAYING
        private set
    var elapsedSeconds: Float = 0f
        private set
    var attempts: Int = 1
        private set
    var coinsCollected: Int = 0
        private set

    val totalCoins: Int get() = level.coins.size
    val playerWidth: Float get() = tuning.playerWidth
    val playerHeight: Float get() = tuning.playerHeight
    val playerCenterX: Float get() = playerX + tuning.playerWidth / 2f
    val playerCenterY: Float get() = playerY + tuning.playerHeight / 2f

    /** `true` for coins that have already been picked up. Indexed like [LevelData.coins]. */
    val collected = BooleanArray(level.coins.size)

    /** When each coin was collected, for the little "pop" animation. `-1` = never. */
    val collectedAt = FloatArray(level.coins.size) { -1f }

    // ---- input ----------------------------------------------------------------

    var moveLeft: Boolean = false
    var moveRight: Boolean = false

    private var jumpHeld = false
    private var jumpBuffer = 0f
    private var coyote = 0f
    private var accumulator = 0f

    init {
        spawnPlayer()
    }

    /** Press the jump button. Buffered, so pressing just before landing still works. */
    fun pressJump() {
        jumpBuffer = tuning.jumpBufferTime
        jumpHeld = true
    }

    /** Release the jump button — cuts the jump short for variable jump height. */
    fun releaseJump() {
        jumpHeld = false
    }

    /** Restart the current level from the spawn point, keeping the attempt counter. */
    fun restart() {
        attempts++
        resetState()
    }

    private fun resetState() {
        spawnPlayer()
        velocityX = 0f
        velocityY = 0f
        isOnGround = false
        facingRight = true
        status = GameStatus.PLAYING
        elapsedSeconds = 0f
        coinsCollected = 0
        collected.fill(false)
        collectedAt.fill(-1f)
        jumpBuffer = 0f
        coyote = 0f
        jumpHeld = false
        accumulator = 0f
    }

    private fun spawnPlayer() {
        // Feet on the bottom edge of the spawn cell — which [LevelBuilder] guarantees is
        // the ledge the green dot sits on — rather than centred on it, so a player that
        // is several cells tall does not start half-buried in the floor.
        playerX = level.spawn.x - tuning.playerWidth / 2f
        playerY = level.spawn.y + 0.5f - tuning.playerHeight
    }

    // ---- simulation -----------------------------------------------------------

    /**
     * Advances the simulation by a display frame's worth of time.
     *
     * The frame delta is chopped into fixed steps so that a stutter (or a 120 Hz vs 60 Hz
     * device) can never change how high the player jumps or let them tunnel through a
     * platform.
     */
    fun update(frameDeltaSeconds: Float) {
        if (status != GameStatus.PLAYING) return

        accumulator += frameDeltaSeconds.coerceIn(0f, 0.1f)
        var steps = 0
        while (accumulator >= tuning.fixedStep && steps < MAX_STEPS_PER_FRAME) {
            step(tuning.fixedStep)
            accumulator -= tuning.fixedStep
            steps++
            if (status != GameStatus.PLAYING) {
                accumulator = 0f
                break
            }
        }
        if (steps == MAX_STEPS_PER_FRAME) accumulator = 0f
    }

    private fun step(dt: Float) {
        elapsedSeconds += dt

        applyHorizontalInput(dt)
        applyJump(dt)
        applyGravity(dt)

        moveHorizontally(velocityX * dt)
        moveVertically(velocityY * dt)

        updateGroundState()
        checkCoins()
        checkHazards()
        checkGoal()
    }

    private fun applyHorizontalInput(dt: Float) {
        val input = (if (moveRight) 1f else 0f) - (if (moveLeft) 1f else 0f)
        if (input != 0f) {
            facingRight = input > 0f
            val acceleration = if (isOnGround) tuning.groundAcceleration else tuning.airAcceleration
            val target = input * tuning.moveSpeed
            velocityX = approach(velocityX, target, acceleration * dt)
        } else {
            val friction = if (isOnGround) tuning.groundFriction else tuning.airFriction
            velocityX = approach(velocityX, 0f, friction * dt)
        }
    }

    private fun applyJump(dt: Float) {
        coyote = if (isOnGround) tuning.coyoteTime else max(0f, coyote - dt)
        jumpBuffer = max(0f, jumpBuffer - dt)

        if (jumpBuffer > 0f && coyote > 0f) {
            velocityY = -tuning.jumpSpeed
            jumpBuffer = 0f
            coyote = 0f
            isOnGround = false
        }

        // Variable jump height: let go early and the rise is cut short.
        if (!jumpHeld && velocityY < -tuning.minJumpSpeed) {
            velocityY = -tuning.minJumpSpeed
        }
    }

    private fun applyGravity(dt: Float) {
        val gravity = if (velocityY > 0f) tuning.gravity * tuning.fallGravityMultiplier else tuning.gravity
        velocityY = min(velocityY + gravity * dt, tuning.maxFallSpeed)
    }

    // ---- collision ------------------------------------------------------------

    /**
     * Axis-separated AABB resolution against the solid cell mask. Moving one axis at a time
     * is what makes "slide along a wall while falling" behave, and keeps the maths trivial:
     * after the move, look at the cells the box now overlaps and snap out of the first hit.
     */
    private fun moveHorizontally(delta: Float) {
        if (delta == 0f) return
        playerX += delta

        val topRow = rowOf(playerY + EPSILON)
        val bottomRow = rowOf(playerY + tuning.playerHeight - EPSILON)

        if (delta > 0f) {
            val col = colOf(playerX + tuning.playerWidth - EPSILON)
            for (row in topRow..bottomRow) {
                if (level.isSolid(col, row)) {
                    playerX = col - tuning.playerWidth - EPSILON
                    velocityX = 0f
                    return
                }
            }
        } else {
            val col = colOf(playerX + EPSILON)
            for (row in topRow..bottomRow) {
                if (level.isSolid(col, row)) {
                    playerX = col + 1f + EPSILON
                    velocityX = 0f
                    return
                }
            }
        }
    }

    private fun moveVertically(delta: Float) {
        if (delta == 0f) return
        playerY += delta

        val leftCol = colOf(playerX + EPSILON)
        val rightCol = colOf(playerX + tuning.playerWidth - EPSILON)

        if (delta > 0f) {
            val row = rowOf(playerY + tuning.playerHeight - EPSILON)
            for (col in leftCol..rightCol) {
                if (level.isSolid(col, row)) {
                    playerY = row - tuning.playerHeight - EPSILON
                    velocityY = 0f
                    isOnGround = true
                    return
                }
            }
        } else {
            val row = rowOf(playerY + EPSILON)
            for (col in leftCol..rightCol) {
                if (level.isSolid(col, row)) {
                    playerY = row + 1f + EPSILON
                    velocityY = 0f
                    return
                }
            }
        }
    }

    /** A thin probe under the feet, so walking off a ledge is detected immediately. */
    private fun updateGroundState() {
        val row = rowOf(playerY + tuning.playerHeight + GROUND_PROBE)
        val leftCol = colOf(playerX + EPSILON)
        val rightCol = colOf(playerX + tuning.playerWidth - EPSILON)
        var grounded = false
        for (col in leftCol..rightCol) {
            if (level.isSolid(col, row)) {
                grounded = true
                break
            }
        }
        isOnGround = grounded && velocityY >= 0f
    }

    // ---- rules ----------------------------------------------------------------

    private fun checkCoins() {
        val pickupPadding = tuning.playerWidth * COIN_PICKUP_FRACTION
        level.coins.forEach { coin ->
            if (collected[coin.index]) return@forEach
            val nearestX = coin.center.x.coerceIn(playerX, playerX + tuning.playerWidth)
            val nearestY = coin.center.y.coerceIn(playerY, playerY + tuning.playerHeight)
            val dx = coin.center.x - nearestX
            val dy = coin.center.y - nearestY
            val reach = coin.radius + pickupPadding
            if (dx * dx + dy * dy <= reach * reach) {
                collected[coin.index] = true
                collectedAt[coin.index] = elapsedSeconds
                coinsCollected++
            }
        }
    }

    private fun checkHazards() {
        // A slightly shrunk body: brushing a lava outline by a hair should not kill.
        val forgiveness = tuning.playerWidth * HAZARD_FORGIVENESS_FRACTION
        val hit = level.intersectsHazard(
            playerX + forgiveness,
            playerY + forgiveness,
            tuning.playerWidth - forgiveness * 2f,
            tuning.playerHeight - forgiveness * 2f,
        )
        val fellOut = playerY > level.height + tuning.fallOutMargin
        if (hit || fellOut) {
            status = GameStatus.DEAD
            velocityX = 0f
        }
    }

    private fun checkGoal() {
        val goal = level.goal ?: return
        if (goal.overlaps(playerX, playerY, tuning.playerWidth, tuning.playerHeight)) {
            status = GameStatus.WON
            velocityX = 0f
        }
    }

    // ---- helpers --------------------------------------------------------------

    fun playerRect(): LevelRect =
        LevelRect(playerX, playerY, tuning.playerWidth, tuning.playerHeight)

    private fun colOf(worldX: Float): Int = floor(worldX).toInt()

    private fun rowOf(worldY: Float): Int = floor(worldY).toInt()

    private fun approach(current: Float, target: Float, maxDelta: Float): Float {
        val diff = target - current
        return if (abs(diff) <= maxDelta) target else current + sign(diff) * maxDelta
    }

    private companion object {
        const val EPSILON = 0.001f
        const val GROUND_PROBE = 0.02f
        const val COIN_PICKUP_FRACTION = 0.15f
        const val HAZARD_FORGIVENESS_FRACTION = 0.17f

        /** Spiral-of-death guard: at most this many physics steps per rendered frame. */
        const val MAX_STEPS_PER_FRAME = 40
    }
}
