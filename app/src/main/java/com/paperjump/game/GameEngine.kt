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
        fun forLevel(
            level: LevelData,
            mode: GameMode = GameMode.PLATFORMER,
            base: Tuning = Tuning(),
        ): Tuning {
            val scale = scaleFor(level)
            return forMode(mode, base).copy(
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
            ).let { scaled ->
                // Re-apply the per-game adjustments on top of the scaled values, since the
                // copy above rebuilt every field from `base`.
                val forMode = forMode(mode, base)
                scaled.copy(
                    gravity = scaled.gravity * (forMode.gravity / base.gravity),
                    fallGravityMultiplier = forMode.fallGravityMultiplier,
                    maxFallSpeed = scaled.maxFallSpeed * (forMode.maxFallSpeed / base.maxFallSpeed),
                )
            }
        }

        /**
         * Per-game physics.
         *
         * A flyer needs gravity it can beat: with the platformer's pull, one flap barely
         * cancels a fifth of a second of falling, so the player sinks however hard they
         * tap. Halving it — and dropping the "falls faster than it rises" curve, which is a
         * platformer trick — is what makes flapping hold an altitude.
         */
        private fun forMode(mode: GameMode, base: Tuning): Tuning = when (mode) {
            GameMode.FLYER -> base.copy(
                gravity = base.gravity * 0.45f,
                fallGravityMultiplier = 1f,
                maxFallSpeed = base.maxFallSpeed * 0.55f,
            )
            else -> base
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
    val setup: GameSetup = GameSetup(),
    val tuning: Tuning = Tuning.forLevel(level, setup.mode),
) {
    val mode: GameMode get() = setup.mode

    val rules: ModeRules = setup.twist.rulesFor(level, tuning)

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
    var deathCause: DeathCause = DeathCause.NONE
        private set

    /**
     * Seconds left under [Twist.TIME_ATTACK]. `null` when untimed, so the HUD can tell
     * "no clock" apart from "no time left".
     */
    var timeRemaining: Float? = rules.timeLimitSeconds
        private set

    /**
     * World Y of the top of the flood under [Twist.RISING_LAVA], `null` otherwise.
     * Smaller means higher up the page, since Y grows downwards.
     */
    var lavaSurfaceY: Float? = initialLavaSurface()
        private set

    /** Under [Twist.COIN_HUNT] the goal does nothing until the page has been cleared. */
    val isGoalLocked: Boolean
        get() = rules.requireAllCoins && coinsCollected < totalCoins

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

    /** Only [GameMode.MAZE] steers vertically; every other game leaves these false. */
    var moveUp: Boolean = false
    var moveDown: Boolean = false

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
        deathCause = DeathCause.NONE
        timeRemaining = rules.timeLimitSeconds
        lavaSurfaceY = initialLavaSurface()
    }

    /** The flood waits just off the bottom edge of the page until its grace period is up. */
    private fun initialLavaSurface(): Float? =
        if (rules.hasRisingLava) level.height + rules.risingLavaStartOffset else null

    private fun spawnPlayer() {
        playerX = level.spawn.x - tuning.playerWidth / 2f
        playerY = if (mode.hasGravity && !mode.inkIsDeadly) {
            // Feet on the bottom edge of the spawn cell — which [LevelBuilder] guarantees
            // is the ledge the green dot sits on — rather than centred on it, so a player
            // several cells tall does not start half-buried in the floor.
            level.spawn.y + 0.5f - tuning.playerHeight
        } else {
            // Nothing to stand on in these games, so start centred on the mark instead.
            level.spawn.y - tuning.playerHeight / 2f
        }
        if (!mode.hasGravity || mode.inkIsDeadly) {
            // A flyer needs air under it: starting flush against ink that kills on contact
            // means dying on the first frame, before the player has touched anything.
            liftOutOfInk(clearance = if (mode.inkIsDeadly) tuning.playerHeight * 2.5f else 0f)
        }
    }

    /**
     * Moves the player clear of any ink they start inside.
     *
     * The start mark is usually drawn *on* a platform, which is right for a platformer and
     * fatal for a flyer (touching ink ends the run) or hopeless for a maze (spawning inside
     * a wall). Both want the same thing: the nearest free space, searched upwards first
     * because that is where a drawing usually has room.
     */
    private fun liftOutOfInk(clearance: Float = 0f) {
        val step = tuning.playerHeight * 0.25f
        var lifted = 0
        while (overlapsSolid() && lifted < MAX_SPAWN_LIFT_STEPS) {
            playerY -= step
            lifted++
        }
        if (!overlapsSolid()) {
            // Free, but possibly flush against the surface. Back off a little further.
            var gained = 0f
            while (gained < clearance) {
                playerY -= step
                if (overlapsSolid()) {
                    playerY += step
                    break
                }
                gained += step
            }
            return
        }

        // Straight up was blocked all the way; try sideways from the original spot.
        playerY += step * lifted
        var offset = step
        while (offset < level.width) {
            playerX += offset
            if (!overlapsSolid()) return
            playerX -= offset * 2f
            if (!overlapsSolid()) return
            playerX += offset
            offset += step
        }
    }

    private fun overlapsSolid(): Boolean {
        val minCol = floor(playerX).toInt()
        val maxCol = floor(playerX + tuning.playerWidth - EPSILON).toInt()
        val minRow = floor(playerY).toInt()
        val maxRow = floor(playerY + tuning.playerHeight - EPSILON).toInt()
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                if (level.isSolid(col, row)) return true
            }
        }
        return false
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

        // The one place the games really differ: what moves the player.
        when (mode) {
            GameMode.PLATFORMER -> {
                applyHorizontalInput(dt, targetDirection = inputDirection())
                applyJump(dt)
                applyGravity(dt)
            }

            GameMode.RUNNER -> {
                // No brakes: the runner is always accelerating to the right.
                applyHorizontalInput(dt, targetDirection = 1f)
                applyJump(dt)
                applyGravity(dt)
            }

            GameMode.FLYER -> {
                applyHorizontalInput(dt, targetDirection = FLYER_DRIFT)
                applyFlap()
                applyGravity(dt)
            }

            GameMode.MAZE -> applyTopDownInput(dt)
        }

        val blockedX = moveHorizontally(velocityX * dt)
        val blockedY = moveVertically(velocityY * dt)

        updateGroundState()
        checkCoins()
        advanceModeClocks(dt)
        checkCrash(blockedX, blockedY)
        checkHazards()
        checkGoal()
    }

    private fun inputDirection(): Float =
        (if (moveRight) 1f else 0f) - (if (moveLeft) 1f else 0f)

    /** The countdown and the flood: the only two things a mode adds to the simulation. */
    private fun advanceModeClocks(dt: Float) {
        timeRemaining?.let { remaining ->
            val left = remaining - dt
            timeRemaining = max(0f, left)
            if (left <= 0f) die(DeathCause.TIME_UP)
        }

        val speed = rules.risingLavaSpeed ?: return
        if (elapsedSeconds < rules.risingLavaGraceSeconds) return
        lavaSurfaceY = lavaSurfaceY?.minus(speed * dt)
    }

    private fun applyHorizontalInput(dt: Float, targetDirection: Float) {
        val input = targetDirection
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

    /** A tap gives one fixed upward kick, which is what makes a flyer feel like a flyer. */
    private fun applyFlap() {
        if (jumpBuffer > 0f) {
            velocityY = -tuning.jumpSpeed * FLAP_STRENGTH
            jumpBuffer = 0f
        }
    }

    /**
     * Seen from above there is no gravity and no jump: both axes are simply steered, and
     * whatever the player stops pressing decays to a halt.
     */
    private fun applyTopDownInput(dt: Float) {
        val inputX = inputDirection()
        val inputY = (if (moveDown) 1f else 0f) - (if (moveUp) 1f else 0f)

        // Diagonals would otherwise be 41% faster than the straight lines.
        val scale = if (inputX != 0f && inputY != 0f) DIAGONAL_SCALE else 1f
        val speed = tuning.moveSpeed * TOP_DOWN_SPEED
        val acceleration = tuning.groundAcceleration

        velocityX = if (inputX != 0f) {
            facingRight = inputX > 0f
            approach(velocityX, inputX * speed * scale, acceleration * dt)
        } else {
            approach(velocityX, 0f, tuning.groundFriction * dt)
        }
        velocityY = if (inputY != 0f) {
            approach(velocityY, inputY * speed * scale, acceleration * dt)
        } else {
            approach(velocityY, 0f, tuning.groundFriction * dt)
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
    private fun moveHorizontally(delta: Float): Boolean {
        if (delta == 0f) return false
        playerX += delta

        val topRow = rowOf(playerY + EPSILON)
        val bottomRow = rowOf(playerY + tuning.playerHeight - EPSILON)

        if (delta > 0f) {
            val col = colOf(playerX + tuning.playerWidth - EPSILON)
            for (row in topRow..bottomRow) {
                if (level.isSolid(col, row)) {
                    playerX = col - tuning.playerWidth - EPSILON
                    velocityX = 0f
                    return true
                }
            }
        } else {
            val col = colOf(playerX + EPSILON)
            for (row in topRow..bottomRow) {
                if (level.isSolid(col, row)) {
                    playerX = col + 1f + EPSILON
                    velocityX = 0f
                    return true
                }
            }
        }
        return false
    }

    private fun moveVertically(delta: Float): Boolean {
        if (delta == 0f) return false
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
                    return true
                }
            }
        } else {
            val row = rowOf(playerY + EPSILON)
            for (col in leftCol..rightCol) {
                if (level.isSolid(col, row)) {
                    playerY = row + 1f + EPSILON
                    velocityY = 0f
                    return true
                }
            }
        }
        return false
    }

    /**
     * Crashing: the two games where hitting the scenery ends the run rather than stopping
     * the player. A flyer that clips any ink is finished; a runner that cannot go forward
     * has nothing left to do, since it has no brakes and no reverse.
     */
    private fun checkCrash(blockedHorizontally: Boolean, blockedVertically: Boolean) {
        if (status != GameStatus.PLAYING) return
        when (mode) {
            // Landing counts as crashing. The collision resolver stops the player *at* the
            // surface rather than inside it, so an overlap test alone would let a flyer
            // rest on a platform it is supposed to die against.
            // A flyer that climbs off the top of the page could otherwise coast over the
            // whole level and win every drawing the same way, so the sky has a limit.
            GameMode.FLYER -> if (
                blockedHorizontally ||
                blockedVertically ||
                touchingInk() ||
                playerY + tuning.playerHeight < -tuning.playerHeight * CEILING_MARGIN
            ) {
                die(DeathCause.CRASHED)
            }

            GameMode.RUNNER -> if (blockedHorizontally) die(DeathCause.CRASHED)

            else -> Unit
        }
    }

    /** True when the player's body overlaps any solid cell, used by [GameMode.FLYER]. */
    private fun touchingInk(): Boolean {
        val forgiveness = tuning.playerWidth * HAZARD_FORGIVENESS_FRACTION
        val left = playerX + forgiveness
        val top = playerY + forgiveness
        val right = playerX + tuning.playerWidth - forgiveness
        val bottom = playerY + tuning.playerHeight - forgiveness
        val minCol = floor(left).toInt()
        val maxCol = floor(right).toInt()
        val minRow = floor(top).toInt()
        val maxRow = floor(bottom).toInt()
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                // Out-of-bounds columns count as walls, but flying above the page must not
                // be fatal — the level's own edges are not something the player drew.
                if (row in 0 until level.rows && col in 0 until level.cols &&
                    level.isSolid(col, row)
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** A thin probe under the feet, so walking off a ledge is detected immediately. */
    private fun updateGroundState() {
        if (!mode.hasGravity) {
            isOnGround = false
            return
        }
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
        if (status != GameStatus.PLAYING) return

        // A slightly shrunk body: brushing a lava outline by a hair should not kill.
        val forgiveness = tuning.playerWidth * HAZARD_FORGIVENESS_FRACTION
        val hit = level.intersectsHazard(
            playerX + forgiveness,
            playerY + forgiveness,
            tuning.playerWidth - forgiveness * 2f,
            tuning.playerHeight - forgiveness * 2f,
        )
        when {
            hit -> die(DeathCause.LAVA)
            // The flood takes the feet, not the whole body, so standing in the shallows is
            // the moment of death rather than being fully submerged.
            lavaSurfaceY?.let { playerY + tuning.playerHeight > it } == true -> die(DeathCause.FLOODED)
            // Seen from above there is no "down" to fall off.
            mode.hasGravity && playerY > level.height + tuning.fallOutMargin -> die(DeathCause.FELL)
        }
    }

    private fun checkGoal() {
        if (status != GameStatus.PLAYING) return
        if (isGoalLocked) return
        val goal = level.goal ?: return
        if (goal.overlaps(playerX, playerY, tuning.playerWidth, tuning.playerHeight)) {
            status = GameStatus.WON
            velocityX = 0f
        }
    }

    private fun die(cause: DeathCause) {
        if (status != GameStatus.PLAYING) return
        status = GameStatus.DEAD
        deathCause = cause
        velocityX = 0f
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

        /** How hard one flap kicks, relative to a platformer jump. */
        const val FLAP_STRENGTH = 0.62f

        /** The flyer is always drifting forward, at a fraction of full running speed. */
        const val FLYER_DRIFT = 0.55f

        /** Top-down movement is calmer than a platformer's sprint. */
        const val TOP_DOWN_SPEED = 0.78f

        /** 1/sqrt(2): keeps a diagonal the same speed as a straight line. */
        const val DIAGONAL_SCALE = 0.7071f

        /** Bound on the search for free space at the spawn, so it always terminates. */
        const val MAX_SPAWN_LIFT_STEPS = 40

        /** How far above the page a flyer may climb, in player heights. */
        const val CEILING_MARGIN = 3f

        /** Spiral-of-death guard: at most this many physics steps per rendered frame. */
        const val MAX_STEPS_PER_FRAME = 40
    }
}
