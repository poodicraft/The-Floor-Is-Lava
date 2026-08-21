package com.paperjump.draw

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The in-app sketchpad's document model.
 *
 * Deliberately free of Android and Compose types: a stroke is a list of points in **paper
 * space**, where (0, 0) is the top-left corner of the sheet and (1, 1) the bottom-right,
 * whatever size the screen happens to be. That is what lets the same drawing be rasterised
 * at any resolution, survive a rotation, and be unit tested on the JVM.
 *
 * Drawings are rendered to a bitmap by `StrokeRasterizer` and then go through exactly the
 * same detector as a photograph, so a drawn level and a photographed one play identically.
 */

/** The pens on the toolbar. Each one paints the ink the detector reads as that element. */
enum class DrawTool(
    val label: String,
    val hint: String,
    /** Stroke width as a fraction of the paper's width. */
    val defaultWidth: Float,
) {
    INK("Ground", "Platforms and walls", 0.014f),
    LAVA("Lava", "Deadly to touch", 0.030f),
    COIN("Coin", "Collect these", 0.034f),
    SPAWN("Start", "Where you appear", 0.050f),
    GOAL("Flag", "Reach it to win", 0.050f),
    ERASER("Eraser", "Remove strokes", 0.060f),
    ;

    val isEraser: Boolean get() = this == ERASER
}

/** A point on the sheet, in paper space (0..1 on both axes). */
data class StrokePoint(val x: Float, val y: Float)

/** One continuous press-drag-release of a pen. */
data class Stroke(
    val tool: DrawTool,
    val width: Float,
    val points: List<StrokePoint>,
) {
    init {
        require(points.isNotEmpty()) { "A stroke needs at least one point" }
        require(!tool.isEraser) { "The eraser removes strokes, it does not add one" }
    }

    /** A single tap, which rasterises as a dot rather than a line. */
    val isDot: Boolean get() = points.size == 1
}

/**
 * An immutable snapshot of the sketchpad, including its undo history.
 *
 * History is kept as **whole-document snapshots** rather than as a stack of individual
 * strokes. A stroke stack can only ever undo "the last stroke drawn", which gets the two
 * interesting cases wrong: erasing (one gesture can remove several strokes, and undo has to
 * bring all of them back) and clearing (one gesture removes everything). Snapshots make
 * every gesture exactly one step, whatever it did.
 *
 * Snapshots share their `Stroke` objects, so a step costs one list of references.
 */
data class DrawingState(
    val strokes: List<Stroke> = emptyList(),
    private val past: List<List<Stroke>> = emptyList(),
    private val future: List<List<Stroke>> = emptyList(),
) {
    val isEmpty: Boolean get() = strokes.isEmpty()
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Which elements the drawing currently contains, for the "what's missing" hints. */
    val toolsUsed: Set<DrawTool> get() = strokes.mapTo(mutableSetOf()) { it.tool }

    fun add(stroke: Stroke): DrawingState = commit(strokes + stroke)

    /** Clearing the sheet is a single undoable step, like any other edit. */
    fun cleared(): DrawingState = if (isEmpty) this else commit(emptyList())

    /**
     * Erases every stroke passing within [radius] of ([x], [y]).
     *
     * Whole strokes go at once rather than being cut in half: with strokes this short it is
     * what people expect from a sketchpad, and it cannot leave a sliver of red ink behind
     * that quietly kills the player later.
     */
    fun erasingAt(x: Float, y: Float, radius: Float): DrawingState {
        val survivors = strokes.filterNot { it.touches(x, y, radius) }
        // Dragging the eraser across blank paper must not fill the history with no-ops.
        return if (survivors.size == strokes.size) this else commit(survivors)
    }

    /** Indices of every stroke under the eraser at ([x], [y]). */
    fun strokeIndicesAt(x: Float, y: Float, radius: Float): List<Int> =
        strokes.indices.filter { strokes[it].touches(x, y, radius) }

    /**
     * Removes the given strokes in one undoable step.
     *
     * This is what an eraser *drag* commits at the end of the gesture. Erasing stroke by
     * stroke as the finger moves would work, but it would push one history entry per
     * stroke, so undoing a single sweep could take a dozen taps.
     */
    fun removingIndices(indices: Set<Int>): DrawingState =
        if (indices.isEmpty()) this
        else commit(strokes.filterIndexed { index, _ -> index !in indices })

    fun undo(): DrawingState =
        if (past.isEmpty()) this
        else copy(strokes = past.last(), past = past.dropLast(1), future = future + listOf(strokes))

    fun redo(): DrawingState =
        if (future.isEmpty()) this
        else copy(strokes = future.last(), past = past + listOf(strokes), future = future.dropLast(1))

    /** Records the current document in the history and makes [next] the present. */
    private fun commit(next: List<Stroke>): DrawingState = DrawingState(
        strokes = next,
        past = (past + listOf(strokes)).takeLast(MAX_HISTORY),
        // Editing after an undo abandons the redo branch, as in every other editor.
        future = emptyList(),
    )

    private companion object {
        /** Plenty for a sketch, and bounded so a long session cannot grow without limit. */
        const val MAX_HISTORY = 50
    }
}

/** True when any part of the stroke — including between sampled points — is within [radius]. */
internal fun Stroke.touches(x: Float, y: Float, radius: Float): Boolean {
    // Half the stroke's own thickness counts too, so fat strokes are as easy to hit as they
    // look. Points are sampled sparsely, so this has to test segments, not just vertices.
    val reach = radius + width / 2f
    val reachSquared = reach * reach
    if (points.size == 1) {
        val only = points.first()
        return distanceSquared(x, y, only.x, only.y) <= reachSquared
    }
    for (i in 0 until points.size - 1) {
        if (distanceToSegmentSquared(x, y, points[i], points[i + 1]) <= reachSquared) return true
    }
    return false
}

private fun distanceSquared(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return dx * dx + dy * dy
}

/** Squared distance from a point to a line *segment* (not the infinite line). */
private fun distanceToSegmentSquared(px: Float, py: Float, a: StrokePoint, b: StrokePoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return distanceSquared(px, py, a.x, a.y)
    // Projection of the point onto the segment, clamped to its ends.
    val t = min(1f, max(0f, ((px - a.x) * dx + (py - a.y) * dy) / lengthSquared))
    return distanceSquared(px, py, a.x + t * dx, a.y + t * dy)
}

/** Length of a stroke in paper widths, used to tell a scribble from a stray tap. */
internal fun Stroke.length(): Float {
    var total = 0f
    for (i in 0 until points.size - 1) {
        total += sqrt(distanceSquared(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y))
    }
    return total
}
