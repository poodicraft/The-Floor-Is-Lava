package com.lava.floorislava.processing

import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android facing entry point of the detection pipeline.
 *
 * It only does the `Bitmap` plumbing — scale the photo down to a sane analysis size and
 * hand a plain `IntArray` of ARGB pixels to [LevelBuilder], which holds all of the actual
 * colour / line logic and is unit tested on the JVM.
 *
 * Colour legend the detector looks for:
 * | Ink on paper      | Becomes                    |
 * |-------------------|----------------------------|
 * | Dark / black      | Solid platform & collider  |
 * | Green             | Player spawn point         |
 * | Red               | Lava (instant game over)   |
 * | Yellow / gold     | Collectible coin           |
 * | Blue              | Goal flag                  |
 */
object ImageProcessor {

    /**
     * Longest edge used for analysis. A photo bigger than this is scaled down first: more
     * pixels only add noise and cost, since the grid is at most ~176 cells wide anyway.
     */
    const val ANALYSIS_MAX_DIMENSION = 900

    @WorkerThread
    fun process(bitmap: Bitmap, config: ProcessingConfig = ProcessingConfig()): LevelData {
        val analysed = prepare(bitmap)
        val width = analysed.width
        val height = analysed.height
        val pixels = IntArray(width * height)
        analysed.getPixels(pixels, 0, width, 0, 0, width, height)
        if (analysed !== bitmap) analysed.recycle()
        return LevelBuilder.build(pixels, width, height, config)
    }

    /**
     * Returns a software ARGB_8888 copy scaled to [ANALYSIS_MAX_DIMENSION].
     *
     * `getPixels` throws on hardware bitmaps (which `ImageDecoder` hands out by default),
     * so the config is normalised here rather than at every call site.
     */
    private fun prepare(bitmap: Bitmap): Bitmap {
        val longestEdge = max(bitmap.width, bitmap.height)
        val scale = if (longestEdge > ANALYSIS_MAX_DIMENSION) {
            ANALYSIS_MAX_DIMENSION.toFloat() / longestEdge
        } else {
            1f
        }
        val needsCopy = bitmap.config != Bitmap.Config.ARGB_8888
        if (scale == 1f && !needsCopy) return bitmap

        val targetWidth = max(1, (bitmap.width * scale).roundToInt())
        val targetHeight = max(1, (bitmap.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled.config == Bitmap.Config.ARGB_8888) return scaled

        val converted = scaled.copy(Bitmap.Config.ARGB_8888, false)
        if (scaled !== bitmap) scaled.recycle()
        return converted
    }
}
