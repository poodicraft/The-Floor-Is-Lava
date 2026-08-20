package com.paperjump.processing

/**
 * Level model produced by [LevelBuilder] and consumed by the game engine / renderer.
 *
 * Everything in here is expressed in **world units** where `1 unit == 1 grid cell`.
 * The grid origin (0, 0) is the top-left corner of the photographed sketch, X grows
 * right and Y grows down, matching both the source bitmap and Compose's canvas.
 *
 * This file is deliberately free of any Android dependency so the whole detection
 * pipeline and the physics can be unit-tested on a plain JVM.
 */

/** What a single grid cell of the sketch was recognised as. */
enum class CellType {
    /** Paper. Nothing to do here. */
    EMPTY,

    /** Dark pencil/pen strokes -> solid platform the player collides with. */
    SOLID,

    /** Red ink -> lava. Touching it is fatal. */
    HAZARD,

    /** Yellow/gold ink -> collectible coin. */
    COIN,

    /** Green ink -> player spawn point. */
    SPAWN,

    /** Blue ink -> goal / finish flag. */
    GOAL,
}

/** Simple immutable 2D point in world units. */
data class Vec2(val x: Float, val y: Float)

/** Axis-aligned rectangle in world units. Used for both rendering and AABB collision. */
data class LevelRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f

    /** Standard AABB overlap test (touching edges do not count as an overlap). */
    fun overlaps(other: LevelRect): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y

    fun overlaps(otherX: Float, otherY: Float, otherW: Float, otherH: Float): Boolean =
        x < otherX + otherW && right > otherX && y < otherY + otherH && bottom > otherY

    fun inflate(amount: Float): LevelRect =
        LevelRect(x - amount, y - amount, width + amount * 2f, height + amount * 2f)
}

/** A collectible detected from a yellow blob. */
data class Coin(val index: Int, val center: Vec2, val radius: Float)

/**
 * A fully playable level.
 *
 * @param cols number of grid columns (world width in units)
 * @param rows number of grid rows (world height in units)
 * @param solid one boolean per cell, row-major. The engine collides against this mask.
 * @param hazard one boolean per cell, row-major. Overlapping any of these kills the player.
 * @param platforms [solid] merged into as few rectangles as possible (rendering / debug).
 * @param hazards [hazard] merged into as few rectangles as possible.
 * @param spawn where the player's *center* starts.
 * @param goal the rectangle that finishes the level, or `null` if none was detected.
 * @param warnings human readable notes about what the detector had to guess.
 */
data class LevelData(
    val cols: Int,
    val rows: Int,
    val solid: BooleanArray,
    val hazard: BooleanArray,
    val platforms: List<LevelRect>,
    val hazards: List<LevelRect>,
    val coins: List<Coin>,
    val spawn: Vec2,
    val goal: LevelRect?,
    val warnings: List<String> = emptyList(),
) {
    val width: Float get() = cols.toFloat()
    val height: Float get() = rows.toFloat()

    /**
     * Solid lookup used by the collision resolver.
     *
     * Out-of-bounds columns are treated as walls so the player can never walk off the
     * side of the paper, while out-of-bounds rows are open: the player may jump above
     * the sketch and falls to their death below it.
     */
    fun isSolid(col: Int, row: Int): Boolean {
        if (col < 0 || col >= cols) return true
        if (row < 0 || row >= rows) return false
        return solid[row * cols + col]
    }

    fun isHazard(col: Int, row: Int): Boolean {
        if (col < 0 || col >= cols || row < 0 || row >= rows) return false
        return hazard[row * cols + col]
    }

    /** True when any cell overlapped by the given AABB is lava. */
    fun intersectsHazard(x: Float, y: Float, w: Float, h: Float): Boolean {
        val minCol = floorToInt(x)
        val maxCol = floorToInt(x + w - EPSILON)
        val minRow = floorToInt(y)
        val maxRow = floorToInt(y + h - EPSILON)
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                if (isHazard(col, row)) return true
            }
        }
        return false
    }

    // Generated equals/hashCode would compare the arrays by reference; make it explicit
    // so `LevelData` behaves like the value type it looks like.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LevelData) return false
        return cols == other.cols &&
            rows == other.rows &&
            solid.contentEquals(other.solid) &&
            hazard.contentEquals(other.hazard) &&
            coins == other.coins &&
            spawn == other.spawn &&
            goal == other.goal
    }

    override fun hashCode(): Int {
        var result = cols
        result = 31 * result + rows
        result = 31 * result + solid.contentHashCode()
        result = 31 * result + hazard.contentHashCode()
        result = 31 * result + coins.hashCode()
        result = 31 * result + spawn.hashCode()
        result = 31 * result + (goal?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val EPSILON = 0.0001f

        internal fun floorToInt(value: Float): Int {
            val i = value.toInt()
            return if (value < 0f && value != i.toFloat()) i - 1 else i
        }
    }
}
