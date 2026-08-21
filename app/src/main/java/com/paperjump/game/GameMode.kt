package com.paperjump.game

import com.paperjump.processing.LevelData
import kotlin.math.abs

/**
 * The four ways to play a drawn level.
 *
 * A mode never changes the physics — the same sketch always handles identically — it only
 * changes the win and lose conditions layered on top, so a level drawn for one mode is
 * still playable in every other.
 *
 * Pure Kotlin, like the rest of [GameEngine]: the rules are unit tested on the JVM.
 */
enum class GameMode(
    val title: String,
    val tagline: String,
    val blurb: String,
) {
    CLASSIC(
        title = "Classic",
        tagline = "Reach the flag",
        blurb = "No clock, no conditions. Get to the blue flag without touching the lava.",
    ),
    COIN_HUNT(
        title = "Coin hunt",
        tagline = "Every coin, then the flag",
        blurb = "The flag stays shut until you have picked up every last coin on the page.",
    ),
    TIME_ATTACK(
        title = "Time attack",
        tagline = "Beat the clock",
        blurb = "A countdown sized to the level. Reach the flag before it runs out.",
    ),
    RISING_LAVA(
        title = "Rising lava",
        tagline = "Climb, don't linger",
        blurb = "Lava floods the page from the bottom and keeps coming. Stay above it.",
    );

    /**
     * Rules for this mode on a specific level.
     *
     * Everything derived here is expressed so that it is **resolution independent**: the
     * detail slider changes how finely the sketch is sampled, and a time limit or a lava
     * speed that ignored that would silently double as a difficulty slider.
     */
    fun rulesFor(level: LevelData, tuning: Tuning = Tuning.forLevel(level)): ModeRules = when (this) {
        CLASSIC -> ModeRules()

        COIN_HUNT -> ModeRules(requireAllCoins = true)

        TIME_ATTACK -> ModeRules(timeLimitSeconds = timeLimitFor(level, tuning))

        RISING_LAVA -> ModeRules(
            // Both are fractions of the page per second, so the flood always takes the
            // same wall-clock time to cross the same drawing.
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
         * The multiplier is what makes the mode playable: the direct line ignores every
         * detour a drawn route actually takes. The clamps are deliberately wide — they are
         * there for degenerate sketches (a flag on top of the spawn, a level the size of a
         * postage stamp), not for ordinary ones, which must land between them or the limit
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

/**
 * What a [GameMode] actually does to a run.
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

    /** [GameMode.TIME_ATTACK] countdown reached zero. */
    TIME_UP,

    /** [GameMode.RISING_LAVA] caught up with the player. */
    FLOODED,
}
