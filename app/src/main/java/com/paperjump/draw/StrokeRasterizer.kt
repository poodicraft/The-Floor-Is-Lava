package com.paperjump.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.WorkerThread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Paints a [DrawingState] onto a bitmap so it can go through the photo pipeline.
 *
 * Drawn levels deliberately take the long way round — strokes to bitmap, bitmap through
 * [com.paperjump.processing.ImageProcessor] — instead of being converted straight into a
 * `LevelData`. One detector means a drawn level and a photographed one cannot drift apart,
 * and the tuning screen keeps working for both.
 *
 * The ink colours below are chosen to land squarely on the hues the detector snaps to
 * (red 0°, yellow 50°, green 130°, blue 215°) with plenty of saturation to spare, so a
 * drawing is never misread the way a faint highlighter on a photo might be.
 */
object StrokeRasterizer {

    /** Width of the rendered sheet. Comfortably above the detector's analysis size. */
    const val TARGET_WIDTH = 1200

    /** Paper. Pure white keeps the automatic ink threshold well clear of the strokes. */
    private const val PAPER = 0xFFFFFFFF.toInt()

    fun colorOf(tool: DrawTool): Int = when (tool) {
        DrawTool.INK -> 0xFF1A1A1A.toInt() // hue-less and very dark -> platform
        DrawTool.LAVA -> 0xFFE01B12.toInt() // hue 3°   -> hazard
        DrawTool.COIN -> 0xFFF2C200.toInt() // hue 48°  -> coin
        DrawTool.SPAWN -> 0xFF21B04B.toInt() // hue 138° -> spawn
        DrawTool.GOAL -> 0xFF1E6FE0.toInt() // hue 215° -> goal
        DrawTool.ERASER -> PAPER
    }

    /**
     * Renders the sheet at [aspect] (width / height) into a new ARGB bitmap.
     *
     * The caller owns the result and should recycle it once the detector is done.
     */
    @WorkerThread
    fun rasterize(strokes: List<Stroke>, aspect: Float): Bitmap {
        val safeAspect = aspect.coerceIn(0.2f, 5f)
        val width = TARGET_WIDTH
        val height = max(1, (width / safeAspect).roundToInt())

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()

        strokes.forEach { stroke ->
            paint.color = colorOf(stroke.tool)
            // Widths are a fraction of the paper's width, so they scale with the sheet.
            val strokeWidth = max(1f, stroke.width * width)

            if (stroke.isDot) {
                // A tap has no direction to stroke along; draw the nib itself.
                val point = stroke.points.first()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(point.x * width, point.y * height, strokeWidth / 2f, paint)
                paint.style = Paint.Style.STROKE
                return@forEach
            }

            paint.strokeWidth = strokeWidth
            path.rewind()
            val first = stroke.points.first()
            path.moveTo(first.x * width, first.y * height)
            for (i in 1 until stroke.points.size) {
                val point = stroke.points[i]
                path.lineTo(point.x * width, point.y * height)
            }
            canvas.drawPath(path, paint)
        }

        return bitmap
    }

    /** The on-screen preview colour for a tool — identical to what gets rasterised. */
    fun previewColor(tool: DrawTool): Int =
        if (tool.isEraser) Color.LTGRAY else colorOf(tool)
}
