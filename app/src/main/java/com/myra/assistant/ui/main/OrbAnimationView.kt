package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class OrbAnimationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING }
    var state = State.IDLE; set(value) { field = value; invalidate() }
    var amplitude = 0f; set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val ticker = object : Runnable { override fun run() { phase += if (state == State.SPEAKING) .08f else .035f; invalidate(); postDelayed(this, 16) } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val base = width.coerceAtMost(height) * .25f
        val color = when (state) { State.CONNECTING -> Color.CYAN; State.SPEAKING -> Color.rgb(224,64,251); else -> Color.rgb(255,23,68) }
        val pulse = 1f + .08f * sin(phase) + amplitude * .25f
        paint.shader = RadialGradient(cx, cy, base * 1.7f, intArrayOf(color, Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, base * 1.7f * pulse, paint)
        paint.shader = RadialGradient(cx - base*.3f, cy - base*.3f, base*1.4f, Color.WHITE, color, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, base * pulse, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        repeat(3) { i -> paint.color = Color.argb(120-i*25, Color.red(color), Color.green(color), Color.blue(color)); val r=base*(1.25f+i*.2f); c.drawArc(cx-r,cy-r,cx+r,cy+r,phase*40+i*70,210f,false,paint) }
        paint.style = Paint.Style.FILL
        if (state == State.SPEAKING || state == State.LISTENING) repeat(12) { i -> val a=phase+i*Math.PI.toFloat()/6; val r=base*1.55f; c.drawCircle(cx+cos(a)*r,cy+sin(a)*r,3f+amplitude*7f,paint) }
    }
}
