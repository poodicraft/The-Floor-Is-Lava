package com.paperjump.game

import com.paperjump.processing.LevelData
import kotlin.math.abs

/**
 * The games you can play on a drawing.
 *
 * These are genuinely different games rather than variations on one: they differ in what
 * moves the player, what the ink *means*, and what the controls even are. The same sheet of
 * paper is a platformer's ground, a maze's walls, and a flyer's obstacles.
 *
 * Pure Kotlin, like the rest of the engine, so every rule here is unit tested on the JVM.
 */
enum class GameMode(
    val title: String,
    val tagline: String,
    val blurb: String,
    /** What the dark lines do in this game. */
    val inkMeaning: String,
) {
    PLATFORMER(
        title = "Platformer",
        tagline = "Run and jump",
        blurb = "The classic. Walk, jump between the lines you drew, and reach the flag.",
        inkMeaning = "Ground to stand on",
    ),

    MAZE(
        title = "Maze",
        tagline = "Top-down, no gravity",
        blurb = "The page becomes a maze seen from above. Nothing falls — steer in any " +
            "direction, squeeze through the gaps and find the flag.",
        inkMeaning = "Walls you cannot pass",
    ),

    FLYER(
        title = "Flyer",
        tagline = "Tap to flap",
        blurb = "You are always drifting forward and always falling. Tap to flap upward " +
            "and thread the gaps — out here the ink is deadly, not something to land on.",
        inkMeaning = "Deadly to touch",
    ),

    RUNNER(
        title = "Runner",
        tagline = "No brakes",
        blurb = "You sprint to the right and never stop. All you control is the jump, so " +
            "the drawing becomes a course to be read ahead of time.",
        inkMeaning = "Ground, and walls that end the run",
    ),
    ;

    /** Whether the player can steer, which decides what the on-screen controls are. */
    val controls: ControlScheme
        get() = when (this) {
            PLATFORMER -> ControlScheme.RUN_AND_JUMP
            MAZE -> ControlScheme.EIGHT_WAY
            FLYER -> ControlScheme.FLAP_ONLY
            RUNNER -> ControlScheme.JUMP_ONLY
        }

    /** In [FLYER] the ink kills on contact instead of holding the player up. */
    val inkIsDeadly: Boolean get() = this == FLYER

    /** [MAZE] is seen from above, so nothing falls and there is no bottom to fall off. */
    val hasGravity: Boolean get() = this != MAZE
}

/** Which buttons a game needs on screen. */
enum class ControlScheme { RUN_AND_JUMP, JUMP_ONLY, FLAP_ONLY, EIGHT_WAY }

/**
 * An optional extra rule, layered on top of whichever game is being played.
 *
 * Twists are the old "modes" put in their proper place: they change the win and lose
 * conditions without touching how the player moves, so any twist works with any game.
 */
enum class Twist(val title: String, val blurb: String) {
    NONE("No twist", "Just reach the flag."),
    COIN_HUNT("Coin hunt", "The flag stays shut until every coin is collected."),
    TIME_ATTACK("Time attack", "A countdown sized to the level."),
    RISING_LAVA("Rising lava", "Lava floods the page from the bottom."),
    ;

    fun rulesFor(level: LevelData, tuning: Tuning): ModeRules = when (this) {
        NONE -> ModeRules()

        COIN_HUNT -> ModeRules(requireAllCoins = true)

        TIME_ATTACK -> ModeRules(timeLimitSeconds = timeLimitFor(level, tuning))

        // Both are fractions of the page per second, so the flood always takes the same
        // wall-clock time to cross the same drawing.
        RISING_LAVA -> ModeRules(
            risingLavaSpeed = level.height / FLOOD_SECONDS,
            risingLavaGraceSeconds = FLOOD_GRACE_SECONDS,
        )
    }

    private companion object {
        /** How long the lava takes to swallow the whole page, once it starts moving. */
        const val FLOOD_SECONDS = 55f

        /** A breath at the start of the run before the lava begins to rise. */
        const val FLOOD_GRACE_SECONDS = 3f

        /**
         * A countdown worth about four times the straight walk to the flag, plus a few
         * seconds of slack for jumps and hesitation.
         *
         * The multiplier is what makes it playable: the direct line ignores every detour a
         * drawn route actually takes. The clamps are deliberately wide — they are there for
         * degenerate sketches, not ordinary ones, which must land between them or the limit
         * would stop responding to how big the drawing is.
         *
         * Distance is in cells and [Tuning.moveSpeed] scales with the same grid, so the
         * quotient — and therefore the limit — is identical at 40 columns and at 176.
         */
        fun timeLimitFor(level: LevelData, tuning: Tuning): Float {
            val goal = level.goal
            val targetX = goal?.centerX ?: level.width
            val targetY = goal?.centerY ?: level.spawn.y
            // Climbing costs far more than strolling, so vertical distance counts for more.
            val distance = abs(targetX - level.spawn.x) + abs(targetY - level.spawn.y) * 1.8f
            val walkSeconds = distance / tuning.moveSpeed.coerceAtLeast(0.01f)
            return (walkSeconds * 4f + 6f).coerceIn(12f, 180f)
        }
    }
}

/** A game and its twist: everything needed to start a run. */
data class GameSetup(
    val mode: GameMode = GameMode.PLATFORMER,
    val twist: Twist = Twist.NONE,
) {
    val label: String
        get() = if (twist == Twist.NONE) mode.title else "${mode.title} · ${twist.title}"
}

/**
 * What a [Twist] actually does to a run.
 *
 * @param requireAllCoins the goal does nothing until every coin has been collected.
 * @param timeLimitSeconds countdown for the run; reaching zero is a loss. `null` = untimed.
 * @param risingLavaSpeed cells per second the lava surface climbs. `null` = no flood.
 * @param risingLavaGraceSeconds delay before the flood starts moving.
 * @param risingLavaStartOffset how far *below* the bottom of the page the flood starts.
 */
data class ModeRules(
    val requireAllCoins: Boolean = false,
    val timeLimitSeconds: Float? = null,
    val risingLavaSpeed: Float? = null,
    val risingLavaGraceSeconds: Float = 0f,
    val risingLavaStartOffset: Float = 1.5f,
) {
    val isTimed: Boolean get() = timeLimitSeconds != null
    val hasRisingLava: Boolean get() = risingLavaSpeed != null
}

/** Why a run ended, so the result overlay can say something more useful than "you died". */
enum class DeathCause {
    /** Still alive, or the run was won. */
    NONE,

    /** Touched red ink. */
    LAVA,

    /** Fell off the bottom of the page. */
    FELL,

    /** [Twist.TIME_ATTACK] countdown reached zero. */
    TIME_UP,

    /** [Twist.RISING_LAVA] caught up with the player. */
    FLOODED,

    /** [GameMode.FLYER] flew into the ink, or [GameMode.RUNNER] ran into a wall. */
    CRASHED,
}
