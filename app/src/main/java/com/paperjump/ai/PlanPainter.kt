package com.paperjump.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.WorkerThread
import com.paperjump.draw.DrawTool
import com.paperjump.draw.StrokeRasterizer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Paints a [LevelPlan] as a sketch, in the app's own ink colours.
 *
 * The plan does not become a level directly. It is drawn on a page exactly as a person
 * would draw one — dark lines, red lava, yellow coins, a green start, a blue flag, purple
 * creatures — and then read by the same detector that reads a photograph. That is worth the
 * extra step three times over: the AI cannot invent a level the game does not understand,
 * the result can be re-tuned with the ordinary sliders, and a level made this way is saved,
 * reopened and replayed like any other, with no key and no signal needed ever again.
 */
object PlanPainter {

    /** Matches [StrokeRasterizer.TARGET_WIDTH], so both routes hand the detector the same size. */
    private const val TARGET_WIDTH = StrokeRasterizer.TARGET_WIDTH

    private const val PAPER = 0xFFFFFFFF.toInt()

    @WorkerThread
    fun paint(plan: LevelPlan, aspect: Float): Bitmap {
        val safeAspect = aspect.coerceIn(0.2f, 5f)
        val width = TARGET_WIDTH
        val height = max(1, (width / safeAspect).roundToInt())

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun line(item: PlanLine, tool: DrawTool) {
            stroke.color = StrokeRasterizer.colorOf(tool)
            stroke.strokeWidth = max(MIN_STROKE_PX, item.thickness * width)
            canvas.drawLine(
                item.x1 * width,
                item.y1 * height,
                item.x2 * width,
                item.y2 * height,
                stroke,
            )
        }

        fun dot(point: PlanPoint, tool: DrawTool, radiusFraction: Float) {
            fill.color = StrokeRasterizer.colorOf(tool)
            canvas.drawCircle(
                point.x * width,
                point.y * height,
                max(MIN_DOT_PX, radiusFraction * width),
                fill,
            )
        }

        plan.platforms.forEach { line(it, DrawTool.INK) }
        plan.lava.forEach { line(it, DrawTool.LAVA) }
        plan.coins.forEach { dot(it, DrawTool.COIN, COIN_RADIUS) }
        plan.enemies.forEach { dot(it, DrawTool.ENEMY, CREATURE_RADIUS) }

        // The start and the flag are drawn last so nothing is painted over them; the
        // detector rebuilds any ground they cover, the same as on a real drawing.
        plan.start?.let { dot(it, DrawTool.SPAWN, MARK_RADIUS) }
        plan.goal?.let { dot(it, DrawTool.GOAL, MARK_RADIUS) }

        return bitmap
    }

    /** Thin enough to be a line, thick enough to survive being sampled onto the grid. */
    private const val MIN_STROKE_PX = 6f
    private const val MIN_DOT_PX = 8f

    private const val COIN_RADIUS = 0.011f
    private const val CREATURE_RADIUS = 0.015f
    private const val MARK_RADIUS = 0.016f
}
