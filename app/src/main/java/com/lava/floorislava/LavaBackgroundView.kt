package com.lava.floorislava

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated main-menu backdrop: two layers of rolling lava along the bottom
 * of the screen with glowing embers drifting up out of it.
 */
class LavaBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Ember(var x: Float, var y: Float, var speed: Float, var radius: Float, var life: Float)

    private val density = resources.displayMetrics.density
    private val frontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frontPath = Path()
    private val backPath = Path()
    private val embers = ArrayList<Ember>()

    private var phase = 0f
    private var lastFrameNanos = 0L

    /** Fraction of the height where the lava surface sits (0 = top, 1 = bottom). */
    var lavaLevel = 0.8f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = h * (lavaLevel - 0.06f)
        frontPaint.shader = LinearGradient(
            0f, top, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#FF8C3D"), Color.parseColor("#E8360C"), Color.parseColor("#6B0E01")),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        backPaint.shader = LinearGradient(
            0f, top - 30f * density, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#AAFFB84D"), Color.parseColor("#88B01E02")),
            null,
            Shader.TileMode.CLAMP
        )
        embers.clear()
        repeat(22) { embers.add(newEmber(randomHeight = true)) }
    }

    private fun newEmber(randomHeight: Boolean): Ember {
        val surface = height * lavaLevel
        return Ember(
            x = Random.nextFloat() * width,
            y = if (randomHeight) Random.nextFloat() * surface else surface + Random.nextFloat() * 20f * density,
            speed = (25f + Random.nextFloat() * 55f) * density,
            radius = (1.5f + Random.nextFloat() * 2.5f) * density,
            life = 0f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameNanos = now
        phase += dt * 1.4f

        val surface = height * lavaLevel
        buildWave(backPath, surface - 10f * density, 18f * density, 1.1f, phase * 0.7f + 1.3f)
        buildWave(frontPath, surface, 14f * density, 1.7f, -phase)
        canvas.drawPath(backPath, backPaint)
        canvas.drawPath(frontPath, frontPaint)

        for (ember in embers) {
            ember.y -= ember.speed * dt
            ember.life += dt
            val travelled = ((surface - ember.y) / surface).coerceIn(0f, 1f)
            val alpha = ((1f - travelled) * 255).toInt().coerceIn(0, 255)
            emberPaint.color = Color.argb(alpha, 255, 184 - (travelled * 80).toInt(), 77)
            canvas.drawCircle(
                ember.x + sin(ember.life * 2f + ember.radius) * 6f * density,
                ember.y,
                ember.radius,
                emberPaint
            )
            if (ember.y < 0 || travelled >= 1f) {
                val fresh = newEmber(randomHeight = false)
                ember.x = fresh.x; ember.y = fresh.y; ember.speed = fresh.speed
                ember.radius = fresh.radius; ember.life = 0f
            }
        }

        postInvalidateOnAnimation()
    }

    private fun buildWave(path: Path, baseY: Float, amplitude: Float, waves: Float, wavePhase: Float) {
        path.reset()
        path.moveTo(0f, height.toFloat())
        val step = 12f * density
        var x = 0f
        while (x <= width + step) {
            val t = x / width * 2f * PI.toFloat()
            val y = baseY +
                sin(t * waves + wavePhase) * amplitude +
                sin(t * waves * 2.3f - wavePhase * 1.6f) * amplitude * 0.35f
            path.lineTo(x, y)
            x += step
        }
        path.lineTo(width.toFloat(), height.toFloat())
        path.close()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lastFrameNanos = 0L
    }
}
