package com.paperjump

import com.paperjump.draw.DrawTool
import com.paperjump.draw.DrawingState
import com.paperjump.draw.Stroke
import com.paperjump.draw.StrokePoint
import com.paperjump.draw.touches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sketchpad's document model: history, erasing and stroke hit-testing. */
class DrawingStateTest {

    private fun stroke(
        tool: DrawTool = DrawTool.INK,
        width: Float = 0.01f,
        vararg points: Pair<Float, Float>,
    ) = Stroke(tool, width, points.map { StrokePoint(it.first, it.second) })

    private val line = stroke(points = arrayOf(0.1f to 0.5f, 0.9f to 0.5f))
    private val dot = stroke(tool = DrawTool.COIN, points = arrayOf(0.5f to 0.2f))

    @Test
    fun `strokes are added in order`() {
        val state = DrawingState().add(line).add(dot)
        assertEquals(listOf(line, dot), state.strokes)
        assertFalse(state.isEmpty)
        assertEquals(setOf(DrawTool.INK, DrawTool.COIN), state.toolsUsed)
    }

    @Test
    fun `undo and redo walk the history`() {
        val drawn = DrawingState().add(line).add(dot)
        assertTrue(drawn.canUndo)
        assertFalse(drawn.canRedo)

        val undone = drawn.undo()
        assertEquals(listOf(line), undone.strokes)
        assertTrue(undone.canRedo)

        val redone = undone.redo()
        assertEquals(drawn.strokes, redone.strokes)
        assertFalse(redone.canRedo)
    }

    @Test
    fun `drawing after an undo drops the redo stack`() {
        val other = stroke(points = arrayOf(0.2f to 0.2f, 0.3f to 0.3f))
        val state = DrawingState().add(line).add(dot).undo().add(other)

        assertEquals(listOf(line, other), state.strokes)
        assertFalse("a new stroke must discard the redo history", state.canRedo)
    }

    @Test
    fun `undo and redo on an empty sketchpad do nothing`() {
        val empty = DrawingState()
        assertEquals(empty, empty.undo())
        assertEquals(empty, empty.redo())
    }

    @Test
    fun `clear is undoable in a single step`() {
        val drawn = DrawingState().add(line).add(dot)
        val cleared = drawn.cleared()

        assertTrue(cleared.isEmpty)
        assertTrue(cleared.canUndo)
        assertEquals("undo should bring the whole drawing back", drawn.strokes, cleared.undo().strokes)
    }

    @Test
    fun `the eraser removes strokes it passes over and leaves the others`() {
        val state = DrawingState().add(line).add(dot)

        // Right on the horizontal line, far from the coin.
        val erased = state.erasingAt(0.5f, 0.5f, radius = 0.02f)
        assertEquals(listOf(dot), erased.strokes)

        // Nowhere near anything.
        val untouched = state.erasingAt(0.05f, 0.95f, radius = 0.02f)
        assertEquals(state.strokes, untouched.strokes)
    }

    @Test
    fun `erasing hits the middle of a long segment, not just its ends`() {
        // The classic bug: points are sampled sparsely, so testing only the vertices makes
        // the middle of a fast, straight stroke un-erasable.
        val long = stroke(points = arrayOf(0f to 0f, 1f to 1f))
        assertTrue("midpoint should be erasable", long.touches(0.5f, 0.5f, 0.01f))
        assertFalse("well off the line should not", long.touches(0.2f, 0.8f, 0.01f))
    }

    @Test
    fun `a fat stroke is as easy to erase as it looks`() {
        val thin = stroke(width = 0.002f, points = arrayOf(0.1f to 0.5f, 0.9f to 0.5f))
        val fat = stroke(width = 0.08f, points = arrayOf(0.1f to 0.5f, 0.9f to 0.5f))

        // A touch 0.03 above the centre line: inside the fat stroke, outside the thin one.
        assertFalse(thin.touches(0.5f, 0.53f, 0.005f))
        assertTrue(fat.touches(0.5f, 0.53f, 0.005f))
    }

    @Test
    fun `erasing empty paper keeps the redo stack intact`() {
        val state = DrawingState().add(line).add(dot).undo()
        assertTrue(state.canRedo)

        val stillRedoable = state.erasingAt(0.02f, 0.02f, radius = 0.01f)
        assertTrue("a no-op erase must not clear the redo history", stillRedoable.canRedo)
    }

    @Test
    fun `undo brings back everything one eraser stroke removed`() {
        // Two strokes crossing the same spot: the eraser takes both, so undo owes both back.
        val across = stroke(points = arrayOf(0.5f to 0.1f, 0.5f to 0.9f))
        val drawn = DrawingState().add(line).add(across).add(dot)

        val erased = drawn.erasingAt(0.5f, 0.5f, radius = 0.02f)
        assertEquals("both crossing strokes should be gone", listOf(dot), erased.strokes)

        assertEquals("undo must restore the whole gesture", drawn.strokes, erased.undo().strokes)
    }

    @Test
    fun `history survives a mixed sequence of edits`() {
        val state = DrawingState()
            .add(line)
            .add(dot)
            .erasingAt(0.5f, 0.5f, radius = 0.02f) // removes the line
            .cleared()

        assertTrue(state.isEmpty)
        assertEquals("undo the clear", listOf(dot), state.undo().strokes)
        assertEquals("undo the erase", listOf(line, dot), state.undo().undo().strokes)
        assertEquals("undo the dot", listOf(line), state.undo().undo().undo().strokes)
        assertTrue("and back to blank paper", state.undo().undo().undo().undo().isEmpty)
    }
}
