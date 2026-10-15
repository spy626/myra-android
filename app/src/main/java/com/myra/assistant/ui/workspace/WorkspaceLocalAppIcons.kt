package com.myra.assistant.ui.workspace

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** Small locally painted, recognizable app-icon approximations. Offline; no brand asset fetch. */
internal object WorkspaceLocalAppIcons {
    enum class Kind { CHROME, SPCK, GOOGLE_KEEP, FIREBASE, GITHUB, FIGMA, ANDROID_STUDIO, UNKNOWN }

    fun kind(name: String): Kind = when (name.trim().lowercase()) {
        "chrome", "google chrome", "chrome browser" -> Kind.CHROME
        "spck", "spck editor", "spck code editor" -> Kind.SPCK
        "google keep", "keep", "keep notes", "google keep notes" -> Kind.GOOGLE_KEEP
        "firebase", "google firebase" -> Kind.FIREBASE
        "github", "github mobile" -> Kind.GITHUB
        "figma" -> Kind.FIGMA
        "android studio" -> Kind.ANDROID_STUDIO
        else -> Kind.UNKNOWN
    }

    /** Retain the old glyph contract for text alternatives and prior test compatibility. */
    fun glyph(name: String): String = when (kind(name)) {
        Kind.CHROME -> "🌐"
        Kind.SPCK -> "</>"
        Kind.GOOGLE_KEEP -> "✎"
        Kind.FIREBASE -> "🔥"
        Kind.GITHUB -> "⌘"
        Kind.FIGMA -> "✦"
        Kind.ANDROID_STUDIO -> "🤖"
        Kind.UNKNOWN -> name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }

    fun icon(name: String): Drawable? = kind(name)
        .takeUnless { it == Kind.UNKNOWN }?.let(::LocalDrawable)

    private class LocalDrawable(private val kind: Kind) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun color(c: Int) { paint.color = c; paint.style = Paint.Style.FILL }
        private fun square(c: Canvas, fill: Int) {
            color(fill)
            c.drawRoundRect(RectF(3f, 3f, 45f, 45f), 10f, 10f, paint)
        }
        private fun text(c: Canvas, value: String, fill: Int, size: Float, x: Float, y: Float) {
            color(fill); paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textSize = size; c.drawText(value, x, y, paint)
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / 48f, bounds.height().toFloat() / 48f)
            when (kind) {
                Kind.CHROME -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 14f
                    paint.color = Color.rgb(234, 67, 53)
                    canvas.drawArc(RectF(8f, 8f, 40f, 40f), 210f, 120f, false, paint)
                    paint.color = Color.rgb(251, 188, 5)
                    canvas.drawArc(RectF(8f, 8f, 40f, 40f), 330f, 120f, false, paint)
                    paint.color = Color.rgb(52, 168, 83)
                    canvas.drawArc(RectF(8f, 8f, 40f, 40f), 90f, 120f, false, paint)
                    color(Color.WHITE); canvas.drawCircle(24f, 24f, 11f, paint)
                    color(Color.rgb(66, 133, 244)); canvas.drawCircle(24f, 24f, 8.5f, paint)
                }
                Kind.SPCK -> {
                    square(canvas, Color.rgb(36, 39, 47))
                    text(canvas, "</>", Color.rgb(145, 211, 253), 20f, 3.5f, 30.5f)
                }
                Kind.GOOGLE_KEEP -> {
                    square(canvas, Color.rgb(255, 202, 40))
                    color(Color.WHITE); canvas.drawCircle(24f, 20.5f, 9f, paint)
                    canvas.drawRoundRect(RectF(19f, 26f, 29f, 30f), 1.5f, 1.5f, paint)
                    color(Color.rgb(255, 246, 209))
                    canvas.drawRoundRect(RectF(21f, 31f, 27f, 33f), 1f, 1f, paint)
                }
                Kind.FIREBASE -> {
                    square(canvas, Color.rgb(42, 41, 44))
                    val flame = Path().apply {
                        moveTo(12f, 33f); lineTo(22f, 6f); lineTo(29f, 18f)
                        lineTo(34f, 11f); lineTo(38f, 33f); lineTo(24f, 41f); close()
                    }
                    color(Color.rgb(255, 167, 38)); canvas.drawPath(flame, paint)
                    val center = Path().apply {
                        moveTo(19f, 35f); lineTo(29f, 18f); lineTo(34f, 34f)
                        lineTo(24f, 41f); close()
                    }
                    color(Color.rgb(255, 213, 79)); canvas.drawPath(center, paint)
                }
                Kind.GITHUB -> {
                    square(canvas, Color.rgb(39, 40, 44))
                    val head = Path().apply {
                        moveTo(12f, 16f); lineTo(14f, 8f); lineTo(21f, 13f)
                        lineTo(27f, 13f); lineTo(34f, 8f); lineTo(36f, 17f)
                        quadTo(40f, 29f, 31f, 34f); lineTo(17f, 34f)
                        quadTo(8f, 29f, 12f, 16f); close()
                    }
                    color(Color.WHITE); canvas.drawPath(head, paint)
                    color(Color.rgb(39,40,44))
                    canvas.drawCircle(19f, 25f, 1.5f, paint)
                    canvas.drawCircle(29f, 25f, 1.5f, paint)
                }
                Kind.FIGMA -> {
                    square(canvas, Color.rgb(35, 36, 41))
                    val dots = listOf(
                        Triple(18f, 15f, Color.rgb(242, 78, 30)),
                        Triple(28f, 15f, Color.rgb(255, 114, 98)),
                        Triple(18f, 24f, Color.rgb(162, 89, 255)),
                        Triple(28f, 24f, Color.rgb(26, 188, 254)),
                        Triple(18f, 33f, Color.rgb(10, 207, 131)),
                    )
                    dots.forEach { (x, y, hue) -> color(hue); canvas.drawCircle(x, y, 5f, paint) }
                }
                Kind.ANDROID_STUDIO -> {
                    square(canvas, Color.rgb(38, 43, 40))
                    color(Color.rgb(150, 217, 113))
                    canvas.drawRoundRect(RectF(11f, 16f, 37f, 32f), 6f, 6f, paint)
                    color(Color.rgb(24, 35, 26))
                    canvas.drawCircle(19f, 23f, 1.5f, paint)
                    canvas.drawCircle(29f, 23f, 1.5f, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
                    paint.color = Color.rgb(150, 217, 113)
                    canvas.drawLine(15f, 12f, 12f, 8f, paint)
                    canvas.drawLine(33f, 12f, 36f, 8f, paint)
                }
                Kind.UNKNOWN -> Unit
            }
            paint.style = Paint.Style.FILL
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Framework opacity API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
