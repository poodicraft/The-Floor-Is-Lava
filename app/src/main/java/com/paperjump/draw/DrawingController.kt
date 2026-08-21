package com.paperjump.draw

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.max

/**
 * Editing state for the sketchpad: the current pen, the stroke under the finger, and the
 * document itself.
 *
 * This exists as an object with a **stable identity** rather than as a handful of
 * `remember`ed values because the gesture handler is a long-lived lambda. A lambda that
 * closed over the values directly would keep reading whatever they were when it was
 * created, and the drawing would start ignoring the toolbar after the first stroke.
 */
@Stable
class DrawingController(initial: DrawingState = DrawingState()) {

    var document: DrawingState by mutableStateOf(initial)
        private set

    var tool: DrawTool by mutableStateOf(DrawTool.INK)

    /** Multiplier on the current pen's nib size. */
    var sizeScale: Float by mutableFloatStateOf(1f)

    /** Points of the stroke currently under the finger, in paper space. */
    val livePoints = mutableStateListOf<StrokePoint>()

    /** Strokes the in-progress eraser sweep will remove; hidden while it is in progress. */
    val pendingErase = mutableStateListOf<Int>()

    private var liveTool: DrawTool = DrawTool.INK
    private var liveWidth: Float = DrawTool.INK.defaultWidth

    val currentWidth: Float get() = tool.defaultWidth * sizeScale

    fun undo() { document = document.undo() }

    fun redo() { document = document.redo() }

    fun clear() { document = document.cleared() }

    // ---- gesture ------------------------------------------------------------------

    fun begin(x: Float, y: Float) {
        if (tool.isEraser) {
            pendingErase.clear()
            eraseAt(x, y)
            return
        }
        liveTool = tool
        liveWidth = currentWidth
        livePoints.clear()
        livePoints += StrokePoint(x, y)
    }

    fun extend(x: Float, y: Float) {
        if (liveTool.isEraser || tool.isEraser) {
            eraseAt(x, y)
            return
        }
        val last = livePoints.lastOrNull()
        // Thin the samples out: a finger reports far more points than a stroke needs, and
        // every one of them costs memory, hit-testing and a path segment.
        if (last != null && abs(last.x - x) < MIN_SAMPLE_DISTANCE && abs(last.y - y) < MIN_SAMPLE_DISTANCE) {
            return
        }
        livePoints += StrokePoint(x, y)
    }

    fun end() {
        if (tool.isEraser || liveTool.isEraser) {
            document = document.removingIndices(pendingErase.toSet())
            pendingErase.clear()
            return
        }
        if (livePoints.isNotEmpty()) {
            document = document.add(Stroke(liveTool, liveWidth, livePoints.toList()))
        }
        livePoints.clear()
    }

    /** The eraser's reach, as a fraction of the paper's width. */
    val eraseRadius: Float get() = DrawTool.ERASER.defaultWidth * sizeScale / 2f

    private fun eraseAt(x: Float, y: Float) {
        liveTool = DrawTool.ERASER
        document.strokeIndicesAt(x, y, eraseRadius).forEach { index ->
            if (index !in pendingErase) pendingErase += index
        }
    }

    /** The stroke being drawn right now, for the live preview. */
    fun previewStroke(): Stroke? =
        if (liveTool.isEraser || livePoints.isEmpty()) {
            null
        } else {
            Stroke(liveTool, liveWidth, livePoints.toList())
        }

    /** Strokes to paint, with anything the eraser is about to take already hidden. */
    fun visibleStrokes(): List<Stroke> {
        if (pendingErase.isEmpty()) return document.strokes
        val doomed = pendingErase.toSet()
        return document.strokes.filterIndexed { index, _ -> index !in doomed }
    }

    private companion object {
        const val MIN_SAMPLE_DISTANCE = 0.0025f
    }
}

/** Converts a touch in pixels into paper space, clamped to the sheet. */
internal fun toPaper(px: Float, py: Float, widthPx: Float, heightPx: Float): StrokePoint =
    StrokePoint(
        x = (px / max(1f, widthPx)).coerceIn(0f, 1f),
        y = (py / max(1f, heightPx)).coerceIn(0f, 1f),
    )
