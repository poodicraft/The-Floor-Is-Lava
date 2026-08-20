package com.paperjump

import com.paperjump.processing.CellType
import com.paperjump.processing.LevelBuilder
import com.paperjump.processing.ProcessingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertTrue("expected platforms", level.platforms.isNotEmpty())
        assertTrue("expected lava", level.hazards.isNotEmpty())
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

        assertTrue("expected a generated floor", level.platforms.isNotEmpty())
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
        assertTrue("a speck should not become lava", level.hazards.isEmpty())
    }
}
