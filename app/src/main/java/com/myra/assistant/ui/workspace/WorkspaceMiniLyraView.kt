package com.myra.assistant.ui.workspace

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tiny task-aware LYRA mascot for the compact Work indicator.
 * Drawn locally so no network/image generation is involved. Intended visual size: 20-24dp.
 */
internal class WorkspaceMiniLyraView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var phase: WorkspaceWorkPhase = WorkspaceWorkPhase.THINKING
    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPhase(value: WorkspaceWorkPhase) {
        if (phase == value) return
        phase = value
        val active = value !in setOf(WorkspaceWorkPhase.DONE, WorkspaceWorkPhase.ERROR)
        if (active && !animator.isStarted) animator.start()
        if (!active && animator.isStarted) {
            animator.cancel()
            progress = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (phase !in setOf(WorkspaceWorkPhase.DONE, WorkspaceWorkPhase.ERROR) && !animator.isStarted) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val bob = if (phase in setOf(WorkspaceWorkPhase.DONE, WorkspaceWorkPhase.ERROR)) 0f
        else sin(progress * 2f * PI).toFloat() * h * 0.035f
        canvas.save()
        canvas.translate(0f, bob)

        val cx = w * 0.5f
        val headCy = h * 0.39f
        val blue = Color.rgb(94, 167, 255)
        val blueSoft = Color.rgb(139, 198, 255)
        val hair = Color.rgb(22, 25, 34)
        val face = Color.rgb(255, 224, 211)
        val dark = Color.rgb(20, 27, 38)
        val white = Color.rgb(241, 246, 255)

        // Hair silhouette.
        paint.style = Paint.Style.FILL
        paint.color = hair
        canvas.drawCircle(cx, headCy, w * 0.30f, paint)
        canvas.drawOval(w * 0.23f, h * 0.28f, w * 0.77f, h * 0.76f, paint)

        // Face.
        paint.color = face
        canvas.drawOval(w * 0.31f, h * 0.23f, w * 0.69f, h * 0.59f, paint)

        // Fringe.
        paint.color = hair
        val fringe = Path().apply {
            moveTo(w * 0.30f, h * 0.28f)
            cubicTo(w * 0.39f, h * 0.16f, w * 0.61f, h * 0.15f, w * 0.70f, h * 0.29f)
            lineTo(w * 0.60f, h * 0.34f)
            lineTo(w * 0.54f, h * 0.28f)
            lineTo(w * 0.47f, h * 0.36f)
            lineTo(w * 0.40f, h * 0.28f)
            close()
        }
        canvas.drawPath(fringe, paint)

        // White headphones with blue work accent.
        stroke.strokeWidth = w * 0.065f
        stroke.color = white
        canvas.drawArc(w * 0.27f, h * 0.13f, w * 0.73f, h * 0.49f, 202f, 136f, false, stroke)
        paint.color = white
        canvas.drawRoundRect(w * 0.20f, h * 0.31f, w * 0.31f, h * 0.52f, w * 0.05f, w * 0.05f, paint)
        canvas.drawRoundRect(w * 0.69f, h * 0.31f, w * 0.80f, h * 0.52f, w * 0.05f, w * 0.05f, paint)
        stroke.strokeWidth = w * 0.025f
        stroke.color = blue
        canvas.drawRoundRect(w * 0.205f, h * 0.315f, w * 0.305f, h * 0.515f, w * 0.04f, w * 0.04f, stroke)
        canvas.drawRoundRect(w * 0.695f, h * 0.315f, w * 0.795f, h * 0.515f, w * 0.04f, w * 0.04f, stroke)

        // Blink loop.
        val blink = if (progress in 0.82f..0.90f) h * 0.005f else h * 0.030f
        paint.color = dark
        canvas.drawOval(w * 0.385f, h * 0.39f - blink / 2f, w * 0.43f, h * 0.39f + blink / 2f, paint)
        canvas.drawOval(w * 0.57f, h * 0.39f - blink / 2f, w * 0.615f, h * 0.39f + blink / 2f, paint)

        // Tiny hoodie/body.
        paint.color = dark
        canvas.drawRoundRect(w * 0.33f, h * 0.57f, w * 0.67f, h * 0.88f, w * 0.10f, w * 0.10f, paint)

        when (phase) {
            WorkspaceWorkPhase.READING,
            WorkspaceWorkPhase.SEARCHING,
            WorkspaceWorkPhase.VISITING -> drawBook(canvas, blue, blueSoft, white)
            WorkspaceWorkPhase.CODING -> drawLaptop(canvas, blue, white, dark)
            WorkspaceWorkPhase.VERIFYING -> drawCheck(canvas, blue, white)
            WorkspaceWorkPhase.RECOVERING -> drawRecover(canvas, blue, white)
            WorkspaceWorkPhase.DONE -> drawCheck(canvas, Color.rgb(97, 219, 148), white)
            WorkspaceWorkPhase.ERROR -> drawError(canvas, Color.rgb(255, 112, 112), white)
            WorkspaceWorkPhase.THINKING -> drawThinking(canvas, blue)
        }

        canvas.restore()
    }

    private fun drawBook(canvas: Canvas, blue: Int, blueSoft: Int, white: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = blue
        val left = Path().apply {
            moveTo(w * 0.29f, h * 0.63f); lineTo(w * 0.49f, h * 0.68f)
            lineTo(w * 0.49f, h * 0.88f); lineTo(w * 0.29f, h * 0.82f); close()
        }
        val right = Path().apply {
            moveTo(w * 0.51f, h * 0.68f); lineTo(w * 0.71f, h * 0.63f)
            lineTo(w * 0.71f, h * 0.82f); lineTo(w * 0.51f, h * 0.88f); close()
        }
        canvas.drawPath(left, paint)
        paint.color = blueSoft
        canvas.drawPath(right, paint)
        stroke.color = white
        stroke.strokeWidth = w * 0.018f
        canvas.drawLine(w * 0.50f, h * 0.68f, w * 0.50f, h * 0.87f, stroke)
    }

    private fun drawLaptop(canvas: Canvas, blue: Int, white: Int, dark: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = blue
        canvas.drawRoundRect(w * 0.29f, h * 0.62f, w * 0.71f, h * 0.82f, w * 0.025f, w * 0.025f, paint)
        paint.color = dark
        canvas.drawRoundRect(w * 0.33f, h * 0.65f, w * 0.67f, h * 0.78f, w * 0.018f, w * 0.018f, paint)
        paint.color = white
        canvas.drawCircle(w * 0.50f, h * 0.715f, w * 0.025f, paint)
        paint.color = blue
        canvas.drawRoundRect(w * 0.25f, h * 0.82f, w * 0.75f, h * 0.87f, w * 0.02f, w * 0.02f, paint)
    }

    private fun drawCheck(canvas: Canvas, accent: Int, white: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = accent
        canvas.drawCircle(w * 0.73f, h * 0.70f, w * 0.16f, paint)
        stroke.color = white
        stroke.strokeWidth = w * 0.045f
        canvas.drawLine(w * 0.66f, h * 0.70f, w * 0.71f, h * 0.76f, stroke)
        canvas.drawLine(w * 0.71f, h * 0.76f, w * 0.81f, h * 0.64f, stroke)
    }

    private fun drawRecover(canvas: Canvas, accent: Int, white: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        stroke.color = accent
        stroke.strokeWidth = w * 0.04f
        canvas.drawArc(w * 0.59f, h * 0.57f, w * 0.88f, h * 0.86f, 45f, 270f, false, stroke)
        paint.color = white
        val arrow = Path().apply {
            moveTo(w * 0.61f, h * 0.57f); lineTo(w * 0.74f, h * 0.56f); lineTo(w * 0.66f, h * 0.67f); close()
        }
        canvas.drawPath(arrow, paint)
    }

    private fun drawError(canvas: Canvas, accent: Int, white: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = accent
        canvas.drawCircle(w * 0.73f, h * 0.70f, w * 0.16f, paint)
        paint.color = white
        canvas.drawRoundRect(w * 0.71f, h * 0.61f, w * 0.75f, h * 0.72f, w * 0.02f, w * 0.02f, paint)
        canvas.drawCircle(w * 0.73f, h * 0.78f, w * 0.022f, paint)
    }

    private fun drawThinking(canvas: Canvas, accent: Int) {
        val w = width.toFloat(); val h = height.toFloat()
        stroke.color = accent
        stroke.strokeWidth = w * 0.035f
        canvas.drawArc(w * 0.66f, h * 0.57f, w * 0.84f, h * 0.75f, 200f, 225f, false, stroke)
        paint.color = accent
        canvas.drawCircle(w * 0.75f, h * 0.80f, w * 0.018f, paint)
    }
}
