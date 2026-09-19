package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.myra.assistant.R

/** Chat-only presentation. The original provider text is preserved in conversation history. */
internal object WorkspaceStoryCardView {
    fun create(context: Context, card: WorkspaceStoryScript.Card, onCopy: () -> Unit): View {
        fun dp(n: Int) = (n * context.resources.displayMetrics.density + 0.5f).toInt()
        fun text(value: CharSequence, size: Float): TextView = TextView(context).apply {
            this.text = value
            textSize = size
            setTextColor(Color.rgb(223, 245, 227))
        }
        val whole = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (card.intro.isNotBlank()) {
            whole.addView(text(WorkspaceMarkdownText.render(card.intro), 15f).apply {
                setLineSpacing(dp(3).toFloat(), 1.05f)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        // Title belongs to the assistant's reply above the writing card, not in the copied script.
        whole.addView(text(card.title, 19f).apply {
            setTypeface(null, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 25, 21))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(72, 101, 79))
            }
            setPadding(dp(15), dp(10), dp(15), dp(16))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text("Writing", 12f).apply {
            setTextColor(Color.rgb(185, 222, 191))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val copy = ImageButton(context).apply {
            setImageResource(R.drawable.ic_workspace_copy)
            imageTintList = ColorStateList.valueOf(Color.rgb(185, 222, 191))
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Copy script only"
            setOnClickListener { onCopy() }
        }
        top.addView(copy, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(top, LinearLayout.LayoutParams(-1, dp(42)))
        panel.addView(text(WorkspaceMarkdownText.render(card.body), 15f).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1.1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        whole.addView(panel, LinearLayout.LayoutParams(-1, -2))
        card.tip?.takeIf { it.isNotBlank() }?.let { tip ->
            whole.addView(text("🎬 Video tip", 13f).apply {
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(185, 222, 191))
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14)
                bottomMargin = dp(5)
            })
            whole.addView(text(WorkspaceMarkdownText.render(tip), 14f).apply {
                setLineSpacing(dp(3).toFloat(), 1.05f)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        return whole
    }
}
