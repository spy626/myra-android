package com.myra.assistant.ui.workspace

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/**
 * Paints a compact inline-code token as a rounded neutral chip in the native chat TextView.
 * This is presentation only; it does not alter the stored message or link destinations.
 * Long identifiers are not routed here, so they retain normal word wrapping.
 */
internal class WorkspaceRoundedCodePillSpan : ReplacementSpan() {
    private fun horizontalPad(paint: Paint): Float = paint.textSize * 0.30f

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val mono = Paint(paint).apply { typeface = Typeface.MONOSPACE }
        val label = text.subSequence(start, end).toString()
        return ceil(mono.measureText(label) + 2f * horizontalPad(paint)).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val chipPaint = Paint(paint).apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
        val label = text.subSequence(start, end).toString()
        val pad = horizontalPad(paint)
        val width = chipPaint.measureText(label) + 2f * pad
        val metrics = chipPaint.fontMetrics
        val verticalPad = paint.textSize * 0.08f
        val radius = paint.textSize * 0.28f
        chipPaint.color = WorkspaceChatReadability.codePillBackgroundColor
        chipPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            x,
            y + metrics.ascent - verticalPad,
            x + width,
            y + metrics.descent + verticalPad,
            radius,
            radius,
            chipPaint,
        )
        chipPaint.color = WorkspaceChatReadability.codePillTextColor
        canvas.drawText(label, x + pad, y.toFloat(), chipPaint)
    }
}
