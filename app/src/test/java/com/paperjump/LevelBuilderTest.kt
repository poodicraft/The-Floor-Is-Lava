package com.paperjump

import com.paperjump.processing.CellType
import com.paperjump.processing.LevelBuilder
import com.paperjump.processing.ProcessingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The detector runs on a plain `IntArray`, so these are fast JVM tests with no emulator,
 * no Robolectric and no android.jar stubs involved.
 */
class LevelBuilderTest {

    private val paper = 0xFFFFFFFF.toInt()
    private val ink = 0xFF101010.toInt()
    private val red = 0xFFE02020.toInt()
    private val green = 0xFF22A040.toInt()
    private val yellow = 0xFFF5C518.toInt()
    private val blue = 0xFF1E6FE0.toInt()
    private val purple = 0xFF9B30D9.toInt()

    private class Sketch(val width: Int, val height: Int) {
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        fun rect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) {
                for (x in left until right) {
                    if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
                }
            }
        }
    }

    private fun defaultSketch(): Sketch = Sketch(400, 300).apply {
        rect(0, 260, 400, 276, ink) // floor
        rect(120, 180, 220, 192, ink) // ledge
        rect(20, 236, 44, 260, green) // spawn
        rect(150, 276, 210, 300, red) // lava pit under the gap
        rect(100, 150, 124, 174, yellow) // coin
        rect(360, 190, 392, 250, blue) // goal
    }

    @Test
    fun `detects platforms hazards coins spawn and goal`() {
        val sketch = defaultSketch()
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)

        assertTrue("expected platforms", level.lineCount > 0)
        assertTrue("expected lava", level.hasLava)
        assertEquals("expected exactly one coin", 1, level.coins.size)
        assertNotNull("expected a goal", level.goal)

        // The floor must be solid where it was drawn and open above it.
        val floorRow = (266f / sketch.height * level.rows).toInt()
        assertTrue(level.isSolid(level.cols / 2, floorRow))
        assertTrue(!level.isSolid(level.cols / 2, floorRow - 4))

        // Spawn is on the left, goal on the right, coin in the upper half.
        assertTrue("spawn should be on the left, was ${level.spawn}", level.spawn.x < level.cols * 0.25f)
        assertTrue("goal should be on the right", level.goal!!.centerX > level.cols * 0.75f)
        assertTrue(level.coins.first().center.y < level.rows * 0.65f)
    }

    @Test
    fun `spawn is never inside a wall or lava`() {
        val sketch = defaultSketch()
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)

        val col = level.spawn.x.toInt()
        val row = level.spawn.y.toInt()
        assertTrue("spawn is inside a platform", !level.isSolid(col, row))
        assertTrue("spawn is inside lava", !level.isHazard(col, row))
    }

    @Test
    fun `a blank page still produces a playable level with a warning`() {
        val sketch = Sketch(200, 150)
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)

        assertTrue("expected a generated floor", level.lineCount > 0)
        assertTrue("expected warnings", level.warnings.isNotEmpty())
        assertNotNull("expected a fallback goal", level.goal)
    }

    @Test
    fun `grid resolution follows the config and keeps the aspect ratio`() {
        val sketch = defaultSketch()
        val coarse = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height, ProcessingConfig(gridCols = 48))
        val fine = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height, ProcessingConfig(gridCols = 160))

        assertEquals(48, coarse.cols)
        assertEquals(36, coarse.rows) // 48 * 300/400
        assertEquals(160, fine.cols)
        assertEquals(120, fine.rows)
    }

    @Test
    fun `hues snap to the nearest marker colour`() {
        assertEquals(CellType.HAZARD, LevelBuilder.colorClassOf(2f))
        assertEquals(CellType.HAZARD, LevelBuilder.colorClassOf(352f))
        assertEquals(CellType.COIN, LevelBuilder.colorClassOf(45f))
        assertEquals(CellType.SPAWN, LevelBuilder.colorClassOf(128f))
        assertEquals(CellType.GOAL, LevelBuilder.colorClassOf(210f))
    }

    @Test
    fun `otsu separates the ink peak from the paper peak`() {
        // 70% paper at 240, 30% ink at 20. Cells are ink when `darkness <= threshold`,
        // so the threshold has to include the dark peak and exclude the light one.
        val darkness = IntArray(1000) { if (it < 700) 240 else 20 }
        val threshold = LevelBuilder.otsuThreshold(darkness)
        assertTrue("ink at 20 must be below the threshold $threshold", threshold >= 20f)
        assertTrue("paper at 240 must be above the threshold $threshold", threshold < 240f)
    }

    @Test
    fun `rect merging covers exactly the masked cells`() {
        val cols = 6
        val rows = 4
        val mask = BooleanArray(cols * rows)
        // An L shape.
        for (x in 0 until 4) mask[2 * cols + x] = true
        for (y in 0 until 4) mask[y * cols + 5] = true

        val rects = LevelBuilder.mergeRects(mask, cols, rows)
        val covered = BooleanArray(cols * rows)
        rects.forEach { rect ->
            for (y in rect.y.toInt() until rect.bottom.toInt()) {
                for (x in rect.x.toInt() until rect.right.toInt()) {
                    assertTrue("rect covers an empty cell at $x,$y", mask[y * cols + x])
                    assertTrue("cells covered twice at $x,$y", !covered[y * cols + x])
                    covered[y * cols + x] = true
                }
            }
        }
        assertTrue("some cells were not covered", mask.indices.all { mask[it] == covered[it] })
    }

    @Test
    fun `tiny colour specks are ignored as noise`() {
        val sketch = Sketch(400, 300).apply {
            rect(0, 260, 400, 276, ink)
            rect(200, 100, 202, 102, red) // a 2x2 speck of red
        }
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)
        assertFalse("a speck should not become lava", level.hasLava)
    }

    // ---- reading ink as lines -------------------------------------------------------

    @Test
    fun `a wobbly hand-drawn ledge becomes one straight bar`() {
        // A line nobody could draw straight: it sags and rises by a couple of pixels the
        // whole way across, which read cell by cell is a staircase of little blocks.
        val sketch = Sketch(400, 300).apply {
            for (x in 40 until 360) {
                val wobble = (sin(x / 11f) * 5f).roundToInt()
                rect(x, 200 + wobble, x + 1, 214 + wobble, ink)
            }
        }
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)

        assertEquals("the ledge should be one line", 1, level.platformStrokes.size)
        val bar = level.platformStrokes.single()
        assertEquals("and a level one", bar.y1, bar.y2, 0.01f)
        assertTrue("with nothing left over as blocks", level.platforms.isEmpty())
    }

    @Test
    fun `a deliberate slope keeps its angle`() {
        val sketch = Sketch(400, 300).apply {
            for (x in 40 until 360) {
                val drop = (x - 40) / 2
                rect(x, 60 + drop, x + 1, 74 + drop, ink)
            }
        }
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)

        assertEquals(1, level.platformStrokes.size)
        val ramp = level.platformStrokes.single()
        assertTrue("a ramp must not be flattened", abs(ramp.y2 - ramp.y1) > 10f)
    }

    @Test
    fun `a filled shape is left alone as blocks`() {
        val sketch = Sketch(400, 300).apply {
            rect(120, 100, 200, 180, ink) // a solid square: no spine to speak of
        }
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)

        assertTrue("a blob is not a line", level.platformStrokes.isEmpty())
        assertTrue("so it stays as rectangles", level.platforms.isNotEmpty())
    }

    @Test
    fun `vectorising leaves the ink where it was drawn`() {
        val sketch = Sketch(400, 300).apply {
            for (x in 40 until 360) {
                val wobble = (sin(x / 9f) * 4f).roundToInt()
                rect(x, 200 + wobble, x + 1, 212 + wobble, ink)
            }
        }
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)
        val row = (206f / sketch.height * level.rows).toInt()

        assertTrue("the redrawn ledge must still be solid", level.isSolid(level.cols / 2, row))
        assertTrue("and must not have grown upwards", !level.isSolid(level.cols / 2, row - 4))
    }

    @Test
    fun `simplify keeps only the vertices that carry the shape`() {
        val straight = FloatArray(20) { 10f + it * 0.1f }
        assertEquals(listOf(0, 19), LevelBuilder.simplify(straight, tolerance = 1f))

        // A shallow V: the corner has to survive, and nothing else needs to.
        val bent = FloatArray(21) { if (it <= 10) 20f - it else it.toFloat() }
        assertEquals(listOf(0, 10, 20), LevelBuilder.simplify(bent, tolerance = 1f))
    }

    @Test
    fun `purple ink becomes a creature`() {
        val sketch = defaultSketch().apply {
            rect(240, 232, 268, 260, purple)
        }
        val level = LevelBuilder.build(sketch.pixels, sketch.width, sketch.height)

        assertEquals("expected one creature", 1, level.enemies.size)
        val creature = level.enemies.single()
        assertTrue("it should be where it was drawn", creature.center.x > level.cols * 0.5f)
        assertNotNull("and the blue flag is still the goal", level.goal)
        assertTrue(
            "the creature must not have been read as the flag",
            level.goal!!.x > creature.center.x,
        )
    }

    @Test
    fun `purple and blue are told apart`() {
        assertEquals(CellType.ENEMY, LevelBuilder.colorClassOf(285f))
        assertEquals(CellType.GOAL, LevelBuilder.colorClassOf(220f))
    }
}
