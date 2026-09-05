package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * LYRA's visual presence. The public state/amplitude API is intentionally unchanged so
 * the existing voice service and command flow keep working while the orb becomes a
 * character-backed interface.
 */
class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING }

    var state = State.IDLE
        set(value) { field = value; invalidate() }
    var amplitude = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var phase = 0f
    private val ticker = object : Runnable {
        override fun run() {
            phase += if (state == State.SPEAKING) .075f else .028f
            invalidate()
            postDelayed(this, 16L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * .43f
        val accent = when (state) {
            State.IDLE -> Color.rgb(126, 10, 29)
            State.CONNECTING -> Color.rgb(205, 17, 46)
            State.LISTENING -> Color.rgb(255, 31, 67)
            State.SPEAKING -> Color.rgb(229, 13, 48)
        }
        val energy = when (state) {
            State.IDLE -> .24f
            State.CONNECTING -> .48f
            State.LISTENING -> .60f + amplitude * .18f
            State.SPEAKING -> .72f + amplitude * .28f
        }

        drawAura(canvas, cx, cy, accent, energy)
        drawStateParticles(canvas, cx, cy, accent, energy)
    }

    private fun drawAura(canvas: Canvas, cx: Float, cy: Float, color: Int, energy: Float) {
        val radius = width.coerceAtMost(height) * (.39f + .018f * sin(phase))
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb((86 * energy).toInt(), Color.red(color), 0, 14),
                Color.argb((38 * energy).toInt(), Color.red(color), 0, 10),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        repeat(3) { index ->
            val ring = radius * (.58f + index * .14f)
            paint.color = Color.argb((95 * energy).toInt(), Color.red(color), 10, 31)
            canvas.drawArc(cx - ring, cy - ring, cx + ring, cy + ring, phase * 24f + index * 78f, 118f, false, paint)
            canvas.drawArc(cx - ring, cy - ring, cx + ring, cy + ring, phase * 24f + 191f + index * 63f, 42f, false, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawStateParticles(canvas: Canvas, cx: Float, cy: Float, color: Int, energy: Float) {
        if (state == State.IDLE) return
        paint.style = Paint.Style.FILL
        repeat(10) { index ->
            val angle = phase * .42f + index * (Math.PI.toFloat() / 5f)
            val radius = width * (.36f + (index % 3) * .035f)
            paint.color = Color.argb((135 * energy).toInt(), Color.red(color), 16, 43)
            canvas.drawCircle(
                cx + cos(angle) * radius,
                cy + sin(angle) * radius * .72f,
                1.5f + energy * 2.2f,
                paint
            )
        }
    }
}
