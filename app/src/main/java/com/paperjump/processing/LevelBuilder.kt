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
    /** Snap nearly-straight runs of ink to exactly straight platforms. */
    val straightenLines: Boolean = true,
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
    private const val HUE_PURPLE = 288f

    /** Cells lit dimmer than this can never be a colour (a shadow is not a marker). */
    private const val MIN_COLOR_VALUE = 46

    /** How colourful a pixel has to be before it counts as a marker rather than ink. */
    internal fun minChromaFor(config: ProcessingConfig): Float =
        (72f - config.colorSensitivity * 34f).coerceIn(28f, 120f)

    internal fun minSaturationFor(config: ProcessingConfig): Float =
        (0.34f - config.colorSensitivity * 0.14f).coerceIn(0.14f, 0.6f)

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

        val grid = downsample(pixels, width, height, cols, rows, minChromaFor(config))
        val inkThreshold = (otsuThreshold(grid.darkness) + config.inkSensitivity * 45f)
            .coerceIn(18f, 226f)
        val cells = classify(grid, cols, rows, inkThreshold, config)

        despeckleInk(cells, cols, rows)
        val blobs = findBlobs(cells, cols, rows)
        dropTinyColorBlobs(cells, blobs, config.minBlobCells)

        val warnings = mutableListOf<String>()

        // A cell can be both "ink" and "a marker": that is what drawing the start dot on
        // top of a platform looks like. Ground has to survive underneath the mark, or the
        // mark punches a hole in the platform and the player falls through the floor.
        val inkUnder = BooleanArray(cells.size) { grid.darkness[it] <= inkThreshold }
        val solid = BooleanArray(cells.size) { index ->
            when (cells[index]) {
                CellType.SOLID -> true
                // Lava is deliberately not solid: you die in it rather than stand on it.
                CellType.SPAWN, CellType.COIN, CellType.GOAL, CellType.ENEMY -> inkUnder[index]
                else -> false
            }
        }
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

        // A mark is opaque: drawing the start dot on a platform hides the ink underneath
        // it, so the ground has to be reconnected across the mark before anything else
        // looks at the shape of the level.
        bridgeMarks(solid, cells, cols, rows)

        // Read the ink as lines rather than as cells. Lava goes through the same pass: it is
        // drawn by the same hand as the platforms and wobbles just as much.
        val solidLines = if (config.straightenLines) vectorise(solid, cols, rows) else null
        val hazardLines = if (config.straightenLines) vectorise(hazard, cols, rows) else null

        val platforms = mergeRects(leftover(solid, solidLines), cols, rows)
        val hazardRects = mergeRects(leftover(hazard, hazardLines), cols, rows)

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

        val enemies = blobs
            .filter { it.type == CellType.ENEMY && it.size >= config.minBlobCells }
            .sortedWith(compareBy({ it.centerX }, { it.centerY }))
            .mapIndexed { index, blob ->
                Enemy(
                    index = index,
                    center = Vec2(blob.centerX, blob.centerY),
                    radius = (min(blob.width, blob.height) / 2f).coerceIn(0.4f, 2f),
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
            enemies = enemies,
            platformStrokes = solidLines?.strokes.orEmpty(),
            hazardStrokes = hazardLines?.strokes.orEmpty(),
        )
    }

    /** The cells a vectorising pass did not claim, which still have to be drawn as blocks. */
    private fun leftover(mask: BooleanArray, lines: Vectorised?): BooleanArray =
        if (lines == null) mask else BooleanArray(mask.size) { mask[it] && !lines.covered[it] }

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
        inkChromaLimit: Float = 72f,
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

                        val maxC = max(r, max(g, b))
                        val minC = min(r, min(g, b))
                        val chroma = maxC - minC

                        // Darkness means *ink* darkness, so a saturated marker colour does
                        // not count: a green start dot on white paper is not a platform,
                        // while the black line it was drawn over still is.
                        val luma = (r * 77 + g * 151 + b * 28) shr 8
                        if (luma < darkest && chroma < inkChromaLimit) darkest = luma
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
        val minChroma = minChromaFor(config)
        val minSaturation = minSaturationFor(config)

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
            CellType.ENEMY to HUE_PURPLE,
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

    // ---------------------------------------------------------------- marks over ink

    private fun CellType.isMark(): Boolean =
        this == CellType.SPAWN || this == CellType.COIN ||
            this == CellType.GOAL || this == CellType.ENEMY

    /**
     * Restores ground that a marker colour painted over.
     *
     * Drawing the green start dot on a platform — the obvious place to put it — used to
     * punch a hole clean through that platform, because the dot's cells classify as spawn
     * rather than ink and the player then fell through the floor on the first frame.
     *
     * A mark cell is filled back in only when the ink resumes on **both** sides at the same
     * row (or column), crossing nothing but more of the same mark. That reconstructs the
     * line the mark was drawn over, while a coin floating in mid-air between two platforms
     * has paper beside it, finds no support, and stays empty.
     */
    internal fun bridgeMarks(
        solid: BooleanArray,
        cells: Array<CellType>,
        cols: Int,
        rows: Int,
    ) {
        val restored = mutableListOf<Int>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val index = row * cols + col
                if (solid[index] || !cells[index].isMark()) continue
                val supported = isBridged(solid, cells, cols, rows, col, row, horizontal = true) ||
                    isBridged(solid, cells, cols, rows, col, row, horizontal = false)
                if (supported) restored += index
            }
        }
        // Applied afterwards so one restored cell cannot act as support for the next and
        // let a bridge grow indefinitely across the page.
        restored.forEach { solid[it] = true }
    }

    private fun isBridged(
        solid: BooleanArray,
        cells: Array<CellType>,
        cols: Int,
        rows: Int,
        col: Int,
        row: Int,
        horizontal: Boolean,
    ): Boolean {
        fun probe(step: Int): Boolean {
            var c = col
            var r = row
            repeat(MAX_BRIDGE_CELLS) {
                if (horizontal) c += step else r += step
                if (c !in 0 until cols || r !in 0 until rows) return false
                val index = r * cols + c
                if (solid[index]) return true
                if (!cells[index].isMark()) return false
            }
            return false
        }
        return probe(1) && probe(-1)
    }

    /** How wide a mark can be and still have the ground rebuilt under it. */
    private const val MAX_BRIDGE_CELLS = 14

    // ---------------------------------------------------------------- vectorising ink

    /**
     * The result of reading a mask as **lines** instead of as cells.
     *
     * @param strokes one entry per straight run the detector recognised
     * @param covered the cells those strokes now occupy, so the caller can tell what is
     *   left over and still has to be drawn as plain blocks
     */
    internal class Vectorised(val strokes: List<LevelStroke>, val covered: BooleanArray)

    /**
     * Turns runs of ink into clean lines, in place.
     *
     * This is the difference between a level that looks drawn and a level that looks like a
     * scan of a drawing. A hand-drawn ledge is never straight and never an even thickness:
     * read cell by cell it becomes a staircase of little blocks that the player trips over,
     * with a bulge wherever the pen paused. So each run of ink is reduced to its **spine**,
     * the spine is simplified to a handful of vertices, and the ink is then redrawn from
     * those vertices at an even thickness. What comes back is the line the hand was aiming
     * for: straight where it meant to be straight, bent where it meant to bend.
     *
     * Only things that actually read as lines are touched. A filled shape, a blob or a
     * scribble has no meaningful spine, so it is left exactly as drawn and the caller keeps
     * rendering it as blocks.
     */
    internal fun vectorise(mask: BooleanArray, cols: Int, rows: Int): Vectorised {
        val covered = BooleanArray(mask.size)
        val strokes = mutableListOf<LevelStroke>()

        solidComponents(mask, cols, rows).forEach { component ->
            val traced = traceComponent(component, cols, rows) ?: return@forEach
            component.forEach { mask[it] = false }
            traced.forEach { stroke ->
                strokes += stroke
                stampStroke(mask, covered, cols, rows, stroke)
            }
        }
        return Vectorised(strokes, covered)
    }

    /**
     * Reads one connected run of ink as a polyline, or returns `null` if it is not a line.
     *
     * The guards are the whole job: applied carelessly this would bulldoze a drawing's
     * structure into bars. A run qualifies only when it is long, thin, unbroken along its
     * own axis, and free of the lumps that mean "this is a shape, not a stroke".
     */
    private fun traceComponent(component: List<Int>, cols: Int, rows: Int): List<LevelStroke>? {
        if (component.size < MIN_LINE_CELLS) return null

        val minCol = component.minOf { it % cols }
        val maxCol = component.maxOf { it % cols }
        val minRow = component.minOf { it / cols }
        val maxRow = component.maxOf { it / cols }
        val spanX = maxCol - minCol + 1
        val spanY = maxRow - minRow + 1

        // Measure along whichever way the run is longer; across the other.
        val horizontal = spanX >= spanY
        val length = if (horizontal) spanX else spanY
        if (length < MIN_LINE_LENGTH) return null

        val minAlong = if (horizontal) minCol else minRow
        val sums = IntArray(length)
        val counts = IntArray(length)
        component.forEach { index ->
            val col = index % cols
            val row = index / cols
            val slot = (if (horizontal) col else row) - minAlong
            sums[slot] += if (horizontal) row else col
            counts[slot]++
        }

        val thickness = medianOf(counts.filter { it > 0 })
        if (thickness > MAX_LINE_THICKNESS) return null
        // Long *and* thin. A square patch of ink is a drawing, not a platform.
        if (length < thickness * MIN_LINE_ASPECT) return null
        // A lump — a blob hanging off the run — means the spine is not the whole story.
        if (counts.max() > thickness * 3 + 2) return null

        val spine = FloatArray(length)
        var gap = 0
        for (slot in 0 until length) {
            if (counts[slot] > 0) {
                spine[slot] = sums[slot].toFloat() / counts[slot]
                gap = 0
            } else {
                // A short break is the pen skipping; a long one means this run doubles back
                // on itself and reading it as one line along this axis would be a fiction.
                if (++gap > MAX_LINE_GAP) return null
                spine[slot] = Float.NaN
            }
        }
        fillGaps(spine)

        val vertices = simplify(spine, tolerance = max(MIN_LINE_TOLERANCE, thickness * 0.5f))

        // A run that came back as a single straight piece and barely leans was meant to be
        // level: snap it exactly level, because a ledge that sags by half a cell reads as a
        // mistake. A steeper one is a ramp somebody drew on purpose and is left at its angle.
        if (vertices.size == 2) {
            val slope = abs(spine[length - 1] - spine[0]) / (length - 1).coerceAtLeast(1)
            if (slope <= MAX_LEVEL_SLOPE) {
                val level = spine.average().toFloat()
                spine[0] = level
                spine[length - 1] = level
            }
        }

        val thicknessUnits = thickness.toFloat()
        return (0 until vertices.size - 1).map { i ->
            val a = vertices[i]
            val b = vertices[i + 1]
            LevelStroke(
                x1 = worldAlong(horizontal, minAlong + a, spine[a]).first,
                y1 = worldAlong(horizontal, minAlong + a, spine[a]).second,
                x2 = worldAlong(horizontal, minAlong + b, spine[b]).first,
                y2 = worldAlong(horizontal, minAlong + b, spine[b]).second,
                thickness = thicknessUnits,
            )
        }
    }

    /** Cell indices to a world point, in whichever order this run is being read. */
    private fun worldAlong(horizontal: Boolean, along: Int, across: Float): Pair<Float, Float> =
        if (horizontal) (along + 0.5f) to (across + 0.5f) else (across + 0.5f) to (along + 0.5f)

    /** Straight-line interpolation across the short breaks left by [traceComponent]. */
    private fun fillGaps(spine: FloatArray) {
        var index = 0
        while (index < spine.size) {
            if (!spine[index].isNaN()) { index++; continue }
            val start = index
            while (index < spine.size && spine[index].isNaN()) index++
            val before = if (start > 0) spine[start - 1] else spine.getOrNull(index) ?: 0f
            val after = if (index < spine.size) spine[index] else before
            for (slot in start until index) {
                val t = (slot - start + 1).toFloat() / (index - start + 1)
                spine[slot] = before + (after - before) * t
            }
        }
    }

    /**
     * Ramer–Douglas–Peucker: keeps only the vertices that carry the shape.
     *
     * A hundred sampled points along a wobbling pen stroke become two if it was meant to be
     * one straight line, three if it turns a corner, a handful if it curves — which is
     * exactly the difference between a level that looks drawn and one that looks digitised.
     */
    internal fun simplify(spine: FloatArray, tolerance: Float): List<Int> {
        if (spine.size < 3) return spine.indices.toList()
        val keep = BooleanArray(spine.size)
        keep[0] = true
        keep[spine.size - 1] = true
        simplifyBetween(spine, 0, spine.size - 1, tolerance, keep)
        return spine.indices.filter { keep[it] }
    }

    private fun simplifyBetween(
        spine: FloatArray,
        first: Int,
        last: Int,
        tolerance: Float,
        keep: BooleanArray,
    ) {
        if (last <= first + 1) return
        val slope = (spine[last] - spine[first]) / (last - first)
        var worst = first
        var worstDistance = 0f
        for (index in first + 1 until last) {
            val distance = abs(spine[index] - (spine[first] + slope * (index - first)))
            if (distance > worstDistance) {
                worstDistance = distance
                worst = index
            }
        }
        if (worstDistance <= tolerance) return
        keep[worst] = true
        simplifyBetween(spine, first, worst, tolerance, keep)
        simplifyBetween(spine, worst, last, tolerance, keep)
    }

    private fun medianOf(values: List<Int>): Int =
        if (values.isEmpty()) 1 else values.sorted()[values.size / 2]

    /** Redraws one line into the mask at an even thickness, as a capsule. */
    private fun stampStroke(
        mask: BooleanArray,
        covered: BooleanArray,
        cols: Int,
        rows: Int,
        stroke: LevelStroke,
    ) {
        val radius = max(0.5f, stroke.thickness / 2f)
        val dx = stroke.x2 - stroke.x1
        val dy = stroke.y2 - stroke.y1
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val steps = max(1, ceil(distance / 0.35f).toInt())
        for (step in 0..steps) {
            val t = step.toFloat() / steps
            stamp(mask, covered, cols, rows, stroke.x1 + dx * t, stroke.y1 + dy * t, radius)
        }
    }

    private fun stamp(
        mask: BooleanArray,
        covered: BooleanArray,
        cols: Int,
        rows: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ) {
        val minCol = max(0, floorToInt(centerX - radius))
        val maxCol = min(cols - 1, floorToInt(centerX + radius))
        val minRow = max(0, floorToInt(centerY - radius))
        val maxRow = min(rows - 1, floorToInt(centerY + radius))
        val radiusSquared = radius * radius
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                val dx = col + 0.5f - centerX
                val dy = row + 0.5f - centerY
                if (dx * dx + dy * dy > radiusSquared) continue
                val index = row * cols + col
                mask[index] = true
                covered[index] = true
            }
        }
    }

    private fun floorToInt(value: Float): Int {
        val truncated = value.toInt()
        return if (value < 0f && value != truncated.toFloat()) truncated - 1 else truncated
    }

    /** Fewer cells than this is a dot or a corner, not a stroke. */
    private const val MIN_LINE_CELLS = 8

    /** Runs shorter than this are punctuation, and redrawing them would only move them. */
    private const val MIN_LINE_LENGTH = 5

    /** Anything fatter is a filled shape, and its spine would be meaningless. */
    private const val MAX_LINE_THICKNESS = 9

    /** A stroke has to be at least this many times longer than it is thick. */
    private const val MIN_LINE_ASPECT = 2.6f

    /** The pen may skip this many steps and still be read as one continuous line. */
    private const val MAX_LINE_GAP = 3

    /** Never simplify to less than half a cell: below that there is nothing to gain. */
    private const val MIN_LINE_TOLERANCE = 0.6f

    /** ~11°. Below this a line reads as "meant to be level" and is snapped exactly level. */
    private const val MAX_LEVEL_SLOPE = 0.2f

    /** One connected run of ink, as the cell indices it occupies. */
    private fun solidComponents(solid: BooleanArray, cols: Int, rows: Int): List<List<Int>> {
        val seen = BooleanArray(solid.size)
        val components = mutableListOf<List<Int>>()
        val stack = ArrayDeque<Int>()

        for (start in solid.indices) {
            if (!solid[start] || seen[start]) continue
            val cells = mutableListOf<Int>()
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                cells += index
                val col = index % cols
                val row = index / cols
                for (dRow in -1..1) {
                    for (dCol in -1..1) {
                        if (dRow == 0 && dCol == 0) continue
                        val c = col + dCol
                        val r = row + dRow
                        if (c !in 0 until cols || r !in 0 until rows) continue
                        val next = r * cols + c
                        if (solid[next] && !seen[next]) {
                            seen[next] = true
                            stack.addLast(next)
                        }
                    }
                }
            }
            components += cells
        }
        return components
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
