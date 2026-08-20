package com.paperjump.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Draws a synthetic "photo of a sketch" so the app is playable (and demoable) without
 * a camera, a printer or a marker pen. It goes through the exact same detection pipeline
 * as a real photo — nothing here is a shortcut into the engine.
 */
object SampleSketch {

    fun create(width: Int = 1400, height: Int = 900): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER) // slightly warm paper

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            style = Paint.Style.STROKE
            strokeWidth = height * 0.022f
            strokeCap = Paint.Cap.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            canvas.drawLine(x1 * width, y1 * height, x2 * width, y2 * height, ink)

        fun dot(cx: Float, cy: Float, r: Float, color: Int) {
            fill.color = color
            canvas.drawCircle(cx * width, cy * height, r * height, fill)
        }

        // Layout note: with the default grid a jump clears roughly 23% of the page's
        // width and 18% of its height, so every gap below stays inside that budget.

        // Ground, with a lava gap to jump.
        line(0.00f, 0.88f, 0.32f, 0.88f)
        line(0.44f, 0.88f, 1.00f, 0.88f)

        // A staircase of ledges up to the goal.
        line(0.16f, 0.74f, 0.30f, 0.74f)
        line(0.36f, 0.60f, 0.52f, 0.60f)
        line(0.58f, 0.46f, 0.74f, 0.46f)
        line(0.80f, 0.34f, 0.96f, 0.34f)

        // Lava: the gap in the ground, plus a pool to hop over on the way right.
        fill.color = LAVA
        canvas.drawRect(0.32f * width, 0.885f * height, 0.44f * width, 1.00f * height, fill)
        canvas.drawRect(0.62f * width, 0.885f * height, 0.70f * width, 0.93f * height, fill)

        // Coins.
        dot(0.22f, 0.69f, 0.020f, GOLD)
        dot(0.44f, 0.55f, 0.020f, GOLD)
        dot(0.50f, 0.84f, 0.020f, GOLD)
        dot(0.66f, 0.41f, 0.020f, GOLD)
        dot(0.88f, 0.29f, 0.020f, GOLD)

        // Spawn (green) and goal (blue).
        dot(0.05f, 0.84f, 0.026f, SPAWN)
        fill.color = GOAL
        canvas.drawRect(0.90f * width, 0.22f * height, 0.95f * width, 0.335f * height, fill)

        return bitmap
    }

    private const val PAPER = 0xFFFBF6E9.toInt()
    private const val INK = 0xFF1A1A1A.toInt()
    private const val LAVA = 0xFFE02020.toInt()
    private const val GOLD = 0xFFF5C518.toInt()
    private const val SPAWN = 0xFF22A040.toInt()
    private const val GOAL = 0xFF1E6FE0.toInt()
}
