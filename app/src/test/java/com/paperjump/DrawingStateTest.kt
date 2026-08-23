package com.paperjump

import com.paperjump.draw.DrawTool
import com.paperjump.draw.DrawingState
import com.paperjump.draw.Stroke
import com.paperjump.draw.StrokePoint
import com.paperjump.draw.erasedAt
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
    fun `the eraser cuts a hole in a line instead of deleting it`() {
        val state = DrawingState().add(line).add(dot)

        // Right on the middle of the horizontal line, far from the coin.
        val erased = state.erasingAt(0.5f, 0.5f, radius = 0.05f)

        assertEquals("the line should be in two pieces, the coin untouched", 3, erased.strokes.size)
        assertTrue("the coin is not under the eraser", dot in erased.strokes)

        val pieces = erased.strokes.filter { it != dot }
        assertEquals(2, pieces.size)
        // The left piece runs from the start of the line up to the rim of the eraser, and
        // the right piece from the far rim onwards. Neither may cross the hole.
        assertEquals(0.1f, pieces[0].points.first().x, 0.001f)
        assertTrue("left piece must stop before the eraser", pieces[0].points.last().x < 0.5f)
        assertTrue("right piece must start after the eraser", pieces[1].points.first().x > 0.5f)
        assertEquals(0.9f, pieces[1].points.last().x, 0.001f)
    }

    @Test
    fun `erasing the end of a line shortens it and leaves one piece`() {
        val erased = DrawingState().add(line).erasingAt(0.9f, 0.5f, radius = 0.05f)

        assertEquals(1, erased.strokes.size)
        val survivor = erased.strokes.single()
        assertEquals(0.1f, survivor.points.first().x, 0.001f)
        assertTrue("the far end should have been rubbed away", survivor.points.last().x < 0.87f)
    }

    @Test
    fun `a dot under the eraser goes entirely`() {
        val erased = DrawingState().add(line).add(dot).erasingAt(0.5f, 0.2f, radius = 0.02f)
        assertEquals(listOf(line), erased.strokes)
    }

    @Test
    fun `the eraser leaves a stroke it only passes near`() {
        val state = DrawingState().add(line).add(dot)
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
    fun `one sweep is one undo, however much it rubbed out`() {
        // What the controller does: carve a working copy as the finger moves, commit once.
        val across = stroke(points = arrayOf(0.5f to 0.1f, 0.5f to 0.9f))
        val drawn = DrawingState().add(line).add(across).add(dot)

        var swept = drawn.strokes
        listOf(0.44f, 0.47f, 0.5f, 0.53f, 0.56f).forEach { x ->
            swept = swept.erasedAt(x, 0.5f, radius = 0.03f)
        }
        val erased = drawn.replacingStrokes(swept)

        assertTrue("both crossing strokes should be cut", erased.strokes.size > drawn.strokes.size)
        assertEquals("undo must restore the whole sweep", drawn.strokes, erased.undo().strokes)
    }

    @Test
    fun `history survives a mixed sequence of edits`() {
        val state = DrawingState()
            .add(line)
            .add(dot)
            .erasingAt(0.5f, 0.5f, radius = 0.05f) // cuts the line in two
            .cleared()

        assertTrue(state.isEmpty)
        assertEquals("undo the clear", 3, state.undo().strokes.size)
        assertEquals("undo the erase", listOf(line, dot), state.undo().undo().strokes)
        assertEquals("undo the dot", listOf(line), state.undo().undo().undo().strokes)
        assertTrue("and back to blank paper", state.undo().undo().undo().undo().isEmpty)
    }
}
