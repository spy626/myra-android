package com.myra.assistant.ui.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

/**
 * Reliable native dotted underline, not a 1dp GradientDrawable border.
 * Draws each wrapped line's label only. The appended ↗ is never underlined.
 */
internal class WorkspaceDottedLinkText(context: Context) : AppCompatTextView(context) {
    private var labelLength = 0
    private val density = resources.displayMetrics.density
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAB4AE.toInt()
        strokeWidth = (1.2f * density).coerceAtLeast(1f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(
            floatArrayOf(1.2f * density, 2.3f * density), 0f
        )
    }

    fun bind(label: String) {
        labelLength = label.length
        text = "$label ↗"
        gravity = Gravity.TOP or Gravity.START
        includeFontPadding = true
        // Reserve pixels for dots, while a whole row remains comfortably tappable.
        setPadding(0, (4f * density).toInt(), 0, (7f * density).toInt())
        minHeight = (44f * density).toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = layout ?: return
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = minOf(labelLength, layout.getLineVisibleEnd(line))
            if (end <= start) continue
            val left = totalPaddingLeft + layout.getPrimaryHorizontal(start)
            val right = totalPaddingLeft + layout.getPrimaryHorizontal(end)
            val y = totalPaddingTop + layout.getLineBottom(line) + 1.2f * density
            if (right > left) canvas.drawLine(left, y, right, y, dots)
        }
    }
}
