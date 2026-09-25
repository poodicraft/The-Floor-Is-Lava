package com.lava.floorislava

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * Draws the "safe zone" marker floating over the live camera feed, positioned
 * according to the angular offset between where the phone is pointing and
 * the real-world direction of the safe zone. Also draws directional hint
 * arrows at the screen edges when the target is off-screen — to the side
 * (behind you) or above/below (need to tilt the phone up or down).
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Normalized screen position of the target, -1..1 on each axis (0 = center).
    // Values can extend beyond -1..1; that's how we know it's off-screen and
    // in which direction. Null until we have a valid bearing/orientation.
    private var targetScreenX: Float? = null
    private var targetScreenY: Float? = null
    private var targetIsBehind: Boolean = false
    private var markerScale: Float = 1f
    private var pulsePhase: Float = 0f

    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39E67A")
        style = Paint.Style.FILL
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFFB0")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val markerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5539E67A")
        style = Paint.Style.FILL
    }
    private val edgeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD166")
        style = Paint.Style.FILL
    }
    private val groundRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA39E67A")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    /**
     * Updates the marker's projected position.
     * @param screenX normalized X, -1..1 (0 = center); can extend past ±1, meaning off-screen to that side
     * @param screenY normalized Y, -1..1 (0 = center); can extend past ±1, meaning off-screen above/below
     * @param isBehind true if the target is roughly behind the phone (>~half FOV + margin off horizontally)
     * @param scale relative marker size — bigger when close, smaller when far away
     */
    fun updateTarget(screenX: Float, screenY: Float, isBehind: Boolean, scale: Float) {
        targetScreenX = screenX
        targetScreenY = screenY
        targetIsBehind = isBehind
        markerScale = scale
        invalidate()
    }

    fun tickPulse() {
        pulsePhase = (pulsePhase + 0.05f) % (2 * Math.PI.toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val x = targetScreenX ?: return
        val y = targetScreenY ?: return

        val pulse = (Math.sin(pulsePhase.toDouble()).toFloat() + 1f) / 2f // 0..1

        val onScreenHorizontally = !targetIsBehind && abs(x) <= 1.0f
        val onScreenVertically = abs(y) <= 1.0f

        if (onScreenHorizontally && onScreenVertically) {
            val centerPxX = width / 2f + (x * width / 2f)
            val centerPxY = height / 2f + (y * height / 2f)
            drawFloatingMarker(canvas, centerPxX, centerPxY, pulse)
            return
        }

        // Off-screen on at least one axis — draw edge hint arrow(s).
        // Horizontal takes priority visually (it's the more common case —
        // target to the side or behind), but we also show a vertical arrow
        // if the target is additionally above/below the visible area.
        if (targetIsBehind || abs(x) > 1.0f) {
            drawHorizontalEdgeHint(canvas, x)
        }
        if (!targetIsBehind && abs(y) > 1.0f) {
            drawVerticalEdgeHint(canvas, y)
        }
    }

    private fun drawFloatingMarker(canvas: Canvas, cx: Float, cy: Float, pulse: Float) {
        val baseRadius = 46f * markerScale
        val glowRadius = baseRadius + 18f + (pulse * 10f)

        // Outer glow
        canvas.drawCircle(cx, cy, glowRadius, markerGlowPaint)

        // "Ground ring" — an ellipse beneath the marker to suggest it's
        // anchored to a spot on the ground, giving a sense of 3D placement.
        canvas.save()
        canvas.translate(cx, cy + baseRadius * 0.9f)
        canvas.scale(1f, 0.35f)
        canvas.drawCircle(0f, 0f, baseRadius * 0.8f, groundRingPaint)
        canvas.restore()

        // Core marker circle
        canvas.drawCircle(cx, cy, baseRadius, markerFillPaint)
        canvas.drawCircle(cx, cy, baseRadius, markerStrokePaint)

        // Little flag on top, echoing the map's safe-zone marker
        val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val poleTop = cy - baseRadius - (34f * markerScale)
        canvas.drawLine(cx, cy - baseRadius, cx, poleTop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 4f * markerScale
        })
        val flagPath = Path().apply {
            moveTo(cx, poleTop)
            lineTo(cx + 26f * markerScale, poleTop + 8f * markerScale)
            lineTo(cx, poleTop + 16f * markerScale)
            close()
        }
        canvas.drawPath(flagPath, flagPaint)
    }

    private fun drawHorizontalEdgeHint(canvas: Canvas, normalizedX: Float) {
        // Target is off to the side (or behind) — draw an arrow hugging
        // whichever edge is closer to the target's actual direction.
        val pointingRight = normalizedX > 0
        val margin = 60f
        val cy = height / 2f
        val cx = if (pointingRight) width - margin else margin

        val path = Path()
        val arrowSize = 36f
        if (pointingRight) {
            path.moveTo(cx - arrowSize, cy - arrowSize)
            path.lineTo(cx, cy)
            path.lineTo(cx - arrowSize, cy + arrowSize)
        } else {
            path.moveTo(cx + arrowSize, cy - arrowSize)
            path.lineTo(cx, cy)
            path.lineTo(cx + arrowSize, cy + arrowSize)
        }
        path.close()
        canvas.drawPath(path, edgeArrowPaint)
    }

    private fun drawVerticalEdgeHint(canvas: Canvas, normalizedY: Float) {
        // Target is above or below the visible frame — draw an arrow
        // hugging the top or bottom edge, hinting to tilt the phone that way.
        val pointingDown = normalizedY > 0 // y > 1 means target is below the frame
        val margin = 60f
        val cx = width / 2f
        val cy = if (pointingDown) height - margin else margin

        val path = Path()
        val arrowSize = 36f
        if (pointingDown) {
            path.moveTo(cx - arrowSize, cy - arrowSize)
            path.lineTo(cx, cy)
            path.lineTo(cx + arrowSize, cy - arrowSize)
        } else {
            path.moveTo(cx - arrowSize, cy + arrowSize)
            path.lineTo(cx, cy)
            path.lineTo(cx + arrowSize, cy + arrowSize)
        }
        path.close()
        canvas.drawPath(path, edgeArrowPaint)
    }
}
