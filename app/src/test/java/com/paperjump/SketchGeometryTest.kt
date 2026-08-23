package com.paperjump

import com.paperjump.processing.LevelBuilder
import com.paperjump.processing.ProcessingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The two things a person notices immediately when a drawing becomes a level: that a mark
 * drawn on a platform does not eat the platform, and that a line meant to be flat is flat.
 *
 * These work on a synthetic sheet that mimics what the sketchpad rasterises — white paper,
 * dark lines, saturated marker colours — so the whole detector runs on the JVM.
 */
class SketchGeometryTest {

    private val w = 900
    private val h = 750
    private val paper = 0xFFFFFFFF.toInt()
    private val ink = 0xFF1A1A1A.toInt()
    private val green = 0xFF21B04B.toInt()
    private val gold = 0xFFF2C200.toInt()
    private val blue = 0xFF1E6FE0.toInt()
    private val lava = 0xFFE01B12.toInt()

    private fun sheet() = IntArray(w * h) { paper }

    private fun IntArray.bar(y: Int, thickness: Int, x0: Int = 0, x1: Int = w, color: Int = ink) {
        for (yy in y until y + thickness) {
            for (xx in x0 until x1) if (yy in 0 until h && xx in 0 until w) this[yy * w + xx] = color
        }
    }

    /** A line that drifts by [rise] pixels from one end to the other. */
    private fun IntArray.slanted(yStart: Int, rise: Int, thickness: Int, x0: Int, x1: Int) {
        for (xx in x0 until x1) {
            val t = (xx - x0).toFloat() / (x1 - x0)
            val y = (yStart + rise * t).toInt()
            for (yy in y until y + thickness) {
                if (yy in 0 until h && xx in 0 until w) this[yy * w + xx] = ink
            }
        }
    }

    private fun IntArray.disc(cx: Int, cy: Int, r: Int, color: Int) {
        for (yy in (cy - r)..(cy + r)) {
            for (xx in (cx - r)..(cx + r)) {
                if (yy in 0 until h && xx in 0 until w &&
                    hypot((xx - cx).toDouble(), (yy - cy).toDouble()) <= r
                ) {
                    this[yy * w + xx] = color
                }
            }
        }
    }

    // ---- marks must not eat the ground ------------------------------------------------

    @Test
    fun `a start dot drawn on a platform leaves the platform intact`() {
        val pixels = sheet()
        pixels.bar(y = 600, thickness = 12)
        // Drawn on top of the line, which is where anyone would put it.
        pixels.disc(cx = 200, cy = 597, r = 22, color = green)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig(straightenLines = false))

        val groundRow = level.rows * 600 / h + 1
        val holes = (0 until level.cols).count { !level.isSolid(it, groundRow) }
        assertEquals("the mark punched a hole in the ground", 0, holes)
    }

    @Test
    fun `the player starts on the ground, not below it`() {
        val pixels = sheet()
        pixels.bar(y = 600, thickness = 12)
        pixels.disc(cx = 200, cy = 597, r = 22, color = green)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig(straightenLines = false))

        val spawnCol = level.spawn.x.toInt()
        val spawnRow = level.spawn.y.toInt()
        assertTrue(
            "spawn fell through to row $spawnRow of ${level.rows}",
            spawnRow < level.rows - 2,
        )
        assertTrue("nothing solid under the spawn", level.isSolid(spawnCol, spawnRow + 1))
        assertFalse("the spawn is buried in ink", level.isSolid(spawnCol, spawnRow))
    }

    @Test
    fun `coins and the flag also leave the ground alone`() {
        val pixels = sheet()
        pixels.bar(y = 600, thickness = 12)
        pixels.disc(cx = 300, cy = 597, r = 20, color = gold)
        pixels.disc(cx = 700, cy = 597, r = 24, color = blue)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig(straightenLines = false))

        val groundRow = level.rows * 600 / h + 1
        assertEquals(0, (0 until level.cols).count { !level.isSolid(it, groundRow) })
        assertTrue("the coin was lost", level.coins.isNotEmpty())
        assertTrue("the flag was lost", level.goal != null)
    }

    @Test
    fun `a colour mark on blank paper is still not a platform`() {
        // The other half of the rule: ink under a mark keeps the ground, but the mark's
        // own colour must never invent ground that was not drawn.
        val pixels = sheet()
        pixels.bar(y = 700, thickness = 12)
        pixels.disc(cx = 450, cy = 300, r = 26, color = green)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig(straightenLines = false))

        val floatingRow = level.rows * 300 / h
        val floatingCol = level.cols * 450 / w
        assertFalse(
            "a floating start dot became a platform",
            level.isSolid(floatingCol, floatingRow),
        )
    }

    // ---- straightening ----------------------------------------------------------------

    @Test
    fun `a slightly crooked line becomes a straight platform`() {
        val pixels = sheet()
        // Drifts 18px over 800 — about 1.3°, the sort of thing a hand does without meaning to.
        pixels.slanted(yStart = 500, rise = 18, thickness = 10, x0 = 50, x1 = 850)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig())

        val occupiedRows = mutableSetOf<Int>()
        for (col in 0 until level.cols) {
            for (row in 0 until level.rows) if (level.isSolid(col, row)) occupiedRows += row
        }
        val top = occupiedRows.min()
        val bottom = occupiedRows.max()
        assertTrue(
            "the platform still spans rows $top..$bottom, so it is still crooked",
            bottom - top <= 1,
        )
    }

    @Test
    fun `a noticeably tilted line is still flattened`() {
        // The rule people expect: a line drawn by hand comes out level unless they were
        // clearly drawing something that slopes. 60px over 700 is a visible tilt and a
        // mistake, not a design.
        val pixels = sheet()
        pixels.slanted(yStart = 400, rise = 60, thickness = 10, x0 = 100, x1 = 800)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig())

        assertTrue("the tilt survived", rowSpan(level) <= 1)
    }

    @Test
    fun `a steep ramp keeps its slope`() {
        val pixels = sheet()
        // 400px over 700 — 30°, unmistakably a ramp somebody meant to draw.
        pixels.slanted(yStart = 200, rise = 400, thickness = 10, x0 = 100, x1 = 800)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig())

        assertTrue("the ramp was flattened into a bar", rowSpan(level) > 8)
    }

    @Test
    fun `a staircase is left exactly as drawn`() {
        // Four flat treads joined by risers: crooked on purpose.
        val pixels = sheet()
        for (step in 0 until 4) {
            val x0 = 100 + step * 160
            val y = 250 + step * 90
            pixels.bar(y = y, thickness = 10, x0 = x0, x1 = x0 + 160)
            // The riser down to the next tread.
            for (yy in y until y + 100) {
                for (xx in (x0 + 150) until (x0 + 160)) pixels[yy * w + xx] = ink
            }
        }

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig())

        assertTrue("the staircase was flattened into one bar", rowSpan(level) > 20)

        // Each tread should still be at its own height.
        val treadRows = (0 until 4).map { step ->
            val col = level.cols * (180 + step * 160) / w
            (0 until level.rows).first { level.isSolid(col, it) }
        }
        assertEquals("treads should descend", treadRows.sorted(), treadRows)
        assertTrue("the treads all ended up at the same height", treadRows.toSet().size == 4)
    }

    @Test
    fun `lava is straightened too`() {
        val pixels = sheet()
        pixels.bar(y = 700, thickness = 12)
        // A wobbly red pool along the floor.
        for (xx in 100 until 800) {
            val wobble = if ((xx / 40) % 2 == 0) 0 else 9
            for (yy in (640 + wobble) until (664 + wobble)) pixels[yy * w + xx] = lava
        }

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig())

        val hazardRows = mutableSetOf<Int>()
        for (col in 0 until level.cols) {
            for (row in 0 until level.rows) if (level.isHazard(col, row)) hazardRows += row
        }
        assertTrue("no lava was detected at all", hazardRows.isNotEmpty())
        assertTrue(
            "the lava still wobbles across rows ${hazardRows.min()}..${hazardRows.max()}",
            hazardRows.max() - hazardRows.min() <= 2,
        )
    }

    /** How many rows the level's ink spans in total. */
    private fun rowSpan(level: com.paperjump.processing.LevelData): Int {
        val rows = mutableSetOf<Int>()
        for (col in 0 until level.cols) {
            for (row in 0 until level.rows) if (level.isSolid(col, row)) rows += row
        }
        return rows.max() - rows.min()
    }

    @Test
    fun `straightening can be turned off`() {
        val pixels = sheet()
        pixels.slanted(yStart = 500, rise = 18, thickness = 10, x0 = 50, x1 = 850)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig(straightenLines = false))

        val occupiedRows = mutableSetOf<Int>()
        for (col in 0 until level.cols) {
            for (row in 0 until level.rows) if (level.isSolid(col, row)) occupiedRows += row
        }
        assertTrue(
            "with straightening off the drift should survive",
            occupiedRows.max() - occupiedRows.min() >= 2,
        )
    }

    @Test
    fun `straightening does not bulldoze a drawing's structure`() {
        // A floor, a wall rising off it and a shelf: all one connected component. Flattening
        // that whole shape into a bar would destroy the level.
        val pixels = sheet()
        pixels.bar(y = 700, thickness = 12)
        for (yy in 400 until 700) for (xx in 400 until 412) pixels[yy * w + xx] = ink
        pixels.bar(y = 400, thickness = 12, x0 = 400, x1 = 700)

        val level = LevelBuilder.build(pixels, w, h, ProcessingConfig())

        val wallCol = level.cols * 405 / w
        val midWallRow = level.rows * 550 / h
        assertTrue("the wall vanished", level.isSolid(wallCol, midWallRow))

        val floorRow = level.rows * 700 / h + 1
        assertTrue("the floor vanished", level.isSolid(level.cols / 8, floorRow))

        val shelfRow = level.rows * 400 / h + 1
        assertTrue("the shelf vanished", level.isSolid(level.cols * 600 / w, shelfRow))
    }
}
