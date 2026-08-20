package com.paperjump.processing

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tunables exposed to the user on the "tune" screen.
 *
 * @param gridCols horizontal resolution of the level grid. More columns = finer detail
 *   but thinner platforms and a noisier level.
 * @param inkSensitivity shifts the automatic (Otsu) ink threshold.
 *   `-1` only counts very black strokes, `+1` also picks up faint pencil.
 * @param colorSensitivity how saturated a marker has to be before it counts as a colour.
 *   `-1` strict (good under coloured light), `+1` loose (good for pale highlighters).
 * @param minBlobCells colour blobs smaller than this many cells are discarded as noise.
 */
data class ProcessingConfig(
    val gridCols: Int = 96,
    val inkSensitivity: Float = 0f,
    val colorSensitivity: Float = 0f,
    val minBlobCells: Int = 3,
) {
    companion object {
        const val MIN_GRID_COLS = 40
        const val MAX_GRID_COLS = 176
    }
}

/**
 * Turns raw ARGB pixels into a playable [LevelData].
 *
 * Pipeline:
 *  1. **Downsample** the photo into a coarse grid. Each cell keeps the *darkest* pixel it
 *     saw (so a 1px pencil line survives) and the *most saturated* pixel it saw (so a small
 *     coloured dot survives).
 *  2. **Otsu threshold** over the per-cell darkness histogram to split ink from paper
 *     without caring about the room's lighting.
 *  3. **Classify** every cell: saturated cells snap to the nearest reference hue
 *     (red / yellow / green / blue), the remaining dark cells become platforms.
 *  4. **Denoise** with a speckle filter for ink and a minimum-size filter for colour blobs.
 *  5. **Vectorise**: merge solid/hazard cells into rectangles, turn yellow blobs into coins,
 *     the biggest green blob into the spawn and the biggest blue blob into the goal.
 *
 * Pure Kotlin on purpose — see [ImageProcessor] for the `Bitmap` facing wrapper.
 */
object LevelBuilder {

    /** Hue (degrees) each colour class is anchored to; a colour snaps to the nearest one. */
    private const val HUE_RED = 0f
    private const val HUE_YELLOW = 50f
    private const val HUE_GREEN = 130f
    private const val HUE_BLUE = 215f

    /** Cells lit dimmer than this can never be a colour (a shadow is not a marker). */
    private const val MIN_COLOR_VALUE = 46

    fun build(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: ProcessingConfig = ProcessingConfig(),
    ): LevelData {
        require(width > 0 && height > 0) { "Empty image" }
        require(pixels.size >= width * height) { "Pixel buffer smaller than $width x $height" }

        val cols = config.gridCols.coerceIn(ProcessingConfig.MIN_GRID_COLS, ProcessingConfig.MAX_GRID_COLS)
        val rows = (cols * height.toFloat() / width.toFloat())
            .roundToInt()
            .coerceIn(16, 240)

        val grid = downsample(pixels, width, height, cols, rows)
        val inkThreshold = otsuThreshold(grid.darkness) + config.inkSensitivity * 45f
        val cells = classify(grid, cols, rows, inkThreshold.coerceIn(18f, 226f), config)

        despeckleInk(cells, cols, rows)
        val blobs = findBlobs(cells, cols, rows)
        dropTinyColorBlobs(cells, blobs, config.minBlobCells)

        val warnings = mutableListOf<String>()

        val solid = BooleanArray(cells.size) { cells[it] == CellType.SOLID }
        val hazard = BooleanArray(cells.size) { cells[it] == CellType.HAZARD }

        if (solid.count { it } == 0) {
            // Nothing to stand on: give the player a floor so the level is still playable.
            warnings += "No dark lines were found — added a floor so the level is playable. " +
                "Try a darker pen, better light, or raise the line sensitivity."
            for (col in 0 until cols) solid[(rows - 1) * cols + col] = true
        } else if (solid.count { it } > solid.size * 0.55f) {
            warnings += "Most of the photo reads as ink. Lower the line sensitivity or " +
                "retake the photo with more even lighting."
        }

        val platforms = mergeRects(solid, cols, rows)
        val hazardRects = mergeRects(hazard, cols, rows)

        val coins = blobs
            .filter { it.type == CellType.COIN && it.size >= config.minBlobCells }
            .sortedWith(compareBy({ it.centerX }, { it.centerY }))
            .mapIndexed { index, blob ->
                Coin(
                    index = index,
                    center = Vec2(blob.centerX, blob.centerY),
                    radius = (min(blob.width, blob.height) / 2f).coerceIn(0.35f, 1.4f),
                )
            }

        val goalBlob = blobs.filter { it.type == CellType.GOAL }.maxByOrNull { it.size }
        val goal = if (goalBlob != null) {
            LevelRect(
                x = goalBlob.minCol.toFloat(),
                y = goalBlob.minRow.toFloat(),
                width = goalBlob.width,
                height = goalBlob.height,
            )
        } else {
            warnings += "No blue goal was found — reach the right-hand edge of the sketch to win."
            LevelRect(cols - 1.5f, 0f, 1.5f, rows.toFloat())
        }

        val spawnBlob = blobs.filter { it.type == CellType.SPAWN }.maxByOrNull { it.size }
        val spawn = if (spawnBlob != null) {
            resolveSpawn(Vec2(spawnBlob.centerX, spawnBlob.centerY), solid, hazard, cols, rows)
        } else {
            warnings += "No green start dot was found — starting from the first safe ledge."
            resolveSpawn(findFallbackSpawn(solid, hazard, cols, rows), solid, hazard, cols, rows)
        }

        return LevelData(
            cols = cols,
            rows = rows,
            solid = solid,
            hazard = hazard,
            platforms = platforms,
            hazards = hazardRects,
            coins = coins,
            spawn = spawn,
            goal = goal,
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- downsampling

    /**
     * Per-cell summary of the source image.
     *
     * @param darkness the darkest luminance seen in the cell (0 = black, 255 = white)
     * @param hue hue in degrees of the most saturated pixel in the cell
     * @param chroma `max(r,g,b) - min(r,g,b)` of that pixel, 0..255
     * @param saturation HSV saturation of that pixel, 0..1
     * @param value HSV value of that pixel, 0..255
     */
    internal class CellSummary(size: Int) {
        val darkness = IntArray(size) { 255 }
        val hue = FloatArray(size)
        val chroma = IntArray(size)
        val saturation = FloatArray(size)
        val value = IntArray(size)
    }

    internal fun downsample(
        pixels: IntArray,
        width: Int,
        height: Int,
        cols: Int,
        rows: Int,
    ): CellSummary {
        val out = CellSummary(cols * rows)
        for (row in 0 until rows) {
            val y0 = row * height / rows
            val y1 = max(y0 + 1, (row + 1) * height / rows)
            for (col in 0 until cols) {
                val x0 = col * width / cols
                val x1 = max(x0 + 1, (col + 1) * width / cols)

                var darkest = 255
                var bestChroma = -1
                var bestHue = 0f
                var bestSaturation = 0f
                var bestValue = 0

                for (y in y0 until min(y1, height)) {
                    val rowOffset = y * width
                    for (x in x0 until min(x1, width)) {
                        val p = pixels[rowOffset + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF

                        val luma = (r * 77 + g * 151 + b * 28) shr 8
                        if (luma < darkest) darkest = luma

                        val maxC = max(r, max(g, b))
                        val minC = min(r, min(g, b))
                        val chroma = maxC - minC
                        // Prefer strong colour, but never let a near-black pixel win: its hue
                        // is meaningless and would poison the classification.
                        if (chroma > bestChroma && maxC >= MIN_COLOR_VALUE) {
                            bestChroma = chroma
                            bestValue = maxC
                            bestSaturation = if (maxC == 0) 0f else chroma.toFloat() / maxC
                            bestHue = hueOf(r, g, b, maxC, minC)
                        }
                    }
                }

                val index = row * cols + col
                out.darkness[index] = darkest
                out.chroma[index] = max(bestChroma, 0)
                out.hue[index] = bestHue
                out.saturation[index] = bestSaturation
                out.value[index] = bestValue
            }
        }
        return out
    }

    /** Hue in degrees (0..360) for an RGB triple whose max/min channels are known. */
    internal fun hueOf(r: Int, g: Int, b: Int, maxC: Int, minC: Int): Float {
        val chroma = (maxC - minC).toFloat()
        if (chroma == 0f) return 0f
        val hue = when (maxC) {
            r -> ((g - b) / chroma) % 6f
            g -> ((b - r) / chroma) + 2f
            else -> ((r - g) / chroma) + 4f
        } * 60f
        return if (hue < 0f) hue + 360f else hue
    }

    // ---------------------------------------------------------------- thresholding

    /**
     * Otsu's method: pick the darkness threshold that maximises between-class variance,
     * i.e. the value that best separates "paper" from "ink" for *this* photo.
     */
    internal fun otsuThreshold(darkness: IntArray): Float {
        if (darkness.isEmpty()) return 128f
        val histogram = IntArray(256)
        for (value in darkness) histogram[value.coerceIn(0, 255)]++

        val total = darkness.size
        var sum = 0.0
        for (i in 0..255) sum += (i * histogram[i]).toDouble()

        var sumBackground = 0.0
        var weightBackground = 0
        var best = 0.0
        var threshold = 128

        for (i in 0..255) {
            weightBackground += histogram[i]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += (i * histogram[i]).toDouble()
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground
            val between = weightBackground.toDouble() * weightForeground.toDouble() *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        return threshold.toFloat()
    }

    // ---------------------------------------------------------------- classification

    internal fun classify(
        grid: CellSummary,
        cols: Int,
        rows: Int,
        inkThreshold: Float,
        config: ProcessingConfig,
    ): Array<CellType> {
        val minChroma = (72f - config.colorSensitivity * 34f).coerceIn(28f, 120f)
        val minSaturation = (0.34f - config.colorSensitivity * 0.14f).coerceIn(0.14f, 0.6f)

        return Array(cols * rows) { index ->
            val isColored = grid.chroma[index] >= minChroma &&
                grid.saturation[index] >= minSaturation &&
                grid.value[index] >= MIN_COLOR_VALUE
            when {
                isColored -> colorClassOf(grid.hue[index])
                grid.darkness[index] <= inkThreshold -> CellType.SOLID
                else -> CellType.EMPTY
            }
        }
    }

    /** Snaps an arbitrary hue to the nearest of the four meaningful marker colours. */
    internal fun colorClassOf(hue: Float): CellType {
        var best = CellType.HAZARD
        var bestDistance = hueDistance(hue, HUE_RED)
        listOf(
            CellType.COIN to HUE_YELLOW,
            CellType.SPAWN to HUE_GREEN,
            CellType.GOAL to HUE_BLUE,
        ).forEach { (type, reference) ->
            val distance = hueDistance(hue, reference)
            if (distance < bestDistance) {
                bestDistance = distance
                best = type
            }
        }
        return best
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return min(diff, 360f - diff)
    }

    // ---------------------------------------------------------------- denoising

    /**
     * Removes lone ink cells (JPEG noise, paper grain, a stray dot) by dropping any solid
     * cell with fewer than two solid neighbours, then fills pinholes surrounded by ink.
     */
    internal fun despeckleInk(cells: Array<CellType>, cols: Int, rows: Int) {
        val original = cells.copyOf()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val index = row * cols + col
                val neighbours = countNeighbours(original, cols, rows, col, row, CellType.SOLID)
                when {
                    original[index] == CellType.SOLID && neighbours < 2 -> cells[index] = CellType.EMPTY
                    original[index] == CellType.EMPTY && neighbours >= 7 -> cells[index] = CellType.SOLID
                }
            }
        }
    }

    private fun countNeighbours(
        cells: Array<CellType>,
        cols: Int,
        rows: Int,
        col: Int,
        row: Int,
        type: CellType,
    ): Int {
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = col + dx
                val y = row + dy
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue
                if (cells[y * cols + x] == type) count++
            }
        }
        return count
    }

    /** A connected group of same-coloured cells. */
    internal data class Blob(
        val type: CellType,
        val size: Int,
        val minCol: Int,
        val minRow: Int,
        val maxCol: Int,
        val maxRow: Int,
        val centerX: Float,
        val centerY: Float,
        val cells: IntArray,
    ) {
        val width: Float get() = (maxCol - minCol + 1).toFloat()
        val height: Float get() = (maxRow - minRow + 1).toFloat()

        override fun equals(other: Any?): Boolean =
            other is Blob && type == other.type && cells.contentEquals(other.cells)

        override fun hashCode(): Int = 31 * type.hashCode() + cells.contentHashCode()
    }

    /** 8-connected flood fill over every coloured (non-empty, non-solid) cell. */
    internal fun findBlobs(cells: Array<CellType>, cols: Int, rows: Int): List<Blob> {
        val visited = BooleanArray(cells.size)
        val blobs = mutableListOf<Blob>()
        val stack = IntArray(cells.size)

        for (start in cells.indices) {
            val type = cells[start]
            if (visited[start] || type == CellType.EMPTY || type == CellType.SOLID) continue

            var top = 0
            stack[top++] = start
            visited[start] = true

            val members = mutableListOf<Int>()
            var minCol = cols
            var maxCol = 0
            var minRow = rows
            var maxRow = 0
            var sumX = 0.0
            var sumY = 0.0

            while (top > 0) {
                val index = stack[--top]
                members += index
                val col = index % cols
                val row = index / cols
                minCol = min(minCol, col); maxCol = max(maxCol, col)
                minRow = min(minRow, row); maxRow = max(maxRow, row)
                sumX += col + 0.5
                sumY += row + 0.5

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = col + dx
                        val ny = row + dy
                        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue
                        val neighbour = ny * cols + nx
                        if (!visited[neighbour] && cells[neighbour] == type) {
                            visited[neighbour] = true
                            stack[top++] = neighbour
                        }
                    }
                }
            }

            blobs += Blob(
                type = type,
                size = members.size,
                minCol = minCol,
                minRow = minRow,
                maxCol = maxCol,
                maxRow = maxRow,
                centerX = (sumX / members.size).toFloat(),
                centerY = (sumY / members.size).toFloat(),
                cells = members.toIntArray(),
            )
        }
        return blobs
    }

    private fun dropTinyColorBlobs(cells: Array<CellType>, blobs: List<Blob>, minBlobCells: Int) {
        blobs.filter { it.size < minBlobCells }.forEach { blob ->
            blob.cells.forEach { cells[it] = CellType.EMPTY }
        }
    }

    // ---------------------------------------------------------------- vectorising

    /**
     * Greedy rectangle decomposition: grow each unused cell as far right as possible, then
     * as far down as that full width allows. Turns a few thousand cells into a few hundred
     * rectangles, which keeps rendering cheap.
     */
    internal fun mergeRects(mask: BooleanArray, cols: Int, rows: Int): List<LevelRect> {
        val used = BooleanArray(mask.size)
        val rects = mutableListOf<LevelRect>()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val start = row * cols + col
                if (!mask[start] || used[start]) continue

                var width = 1
                while (col + width < cols && mask[start + width] && !used[start + width]) width++

                var height = 1
                grow@ while (row + height < rows) {
                    val rowStart = (row + height) * cols + col
                    for (k in 0 until width) {
                        if (!mask[rowStart + k] || used[rowStart + k]) break@grow
                    }
                    height++
                }

                for (y in row until row + height) {
                    for (x in col until col + width) used[y * cols + x] = true
                }
                rects += LevelRect(col.toFloat(), row.toFloat(), width.toFloat(), height.toFloat())
            }
        }
        return rects
    }

    // ---------------------------------------------------------------- spawn placement

    /**
     * Nudges a candidate spawn out of any wall it landed in (people draw the green dot
     * right on top of the platform) and drops it onto the ledge underneath.
     */
    private fun resolveSpawn(
        candidate: Vec2,
        solid: BooleanArray,
        hazard: BooleanArray,
        cols: Int,
        rows: Int,
    ): Vec2 {
        var col = candidate.x.toInt().coerceIn(0, cols - 1)
        var row = candidate.y.toInt().coerceIn(0, rows - 1)

        fun blocked(c: Int, r: Int) = solid[r * cols + c] || hazard[r * cols + c]

        // Walk upwards out of solid ink, then sideways if the whole column is blocked.
        var guard = 0
        while (blocked(col, row) && guard++ < rows) {
            if (row > 0) row-- else break
        }
        if (blocked(col, row)) {
            var offset = 1
            while (offset < cols) {
                if (col + offset < cols && !blocked(col + offset, row)) { col += offset; break }
                if (col - offset >= 0 && !blocked(col - offset, row)) { col -= offset; break }
                offset++
            }
        }
        // Sit just above the first ledge below so the player does not start mid-air.
        var landing = row
        while (landing + 1 < rows && !blocked(col, landing + 1)) landing++
        return Vec2(col + 0.5f, landing + 0.5f)
    }

    /**
     * Used when the sketch has no green dot: the left-most ledge that has enough headroom
     * for the player to stand up in.
     */
    private fun findFallbackSpawn(
        solid: BooleanArray,
        hazard: BooleanArray,
        cols: Int,
        rows: Int,
    ): Vec2 {
        val headroom = playerHeightInCells(cols)
        for (col in 0 until cols) {
            for (row in 0 until rows - 1) {
                val here = row * cols + col
                val below = (row + 1) * cols + col
                if (solid[here] || hazard[here] || !solid[below] || hazard[below]) continue

                val standable = (0 until headroom).all { offset ->
                    val above = row - offset
                    above >= 0 && !solid[above * cols + col] && !hazard[above * cols + col]
                }
                if (standable) return Vec2(col + 0.5f, row + 0.5f)
            }
        }
        return Vec2(1.5f, 1.5f)
    }

    /**
     * How many cells tall the player will be on this grid.
     *
     * The engine scales the player with the grid (see `Tuning.forLevel`); this mirrors the
     * rule of thumb "the player is about one 24th of the page wide" without the processing
     * package depending on the game package.
     */
    private fun playerHeightInCells(cols: Int): Int =
        max(1, ceil(cols / PLAYER_REFERENCE_COLS).toInt())

    /** Mirrors `Tuning.REFERENCE_COLS`: the player is about one 24th of the page wide. */
    private const val PLAYER_REFERENCE_COLS = 24f
}
