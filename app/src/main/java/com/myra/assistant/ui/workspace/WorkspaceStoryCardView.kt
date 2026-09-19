package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.myra.assistant.R

/** Chat-only presentation; source text stays in the existing conversation store. */
internal object WorkspaceStoryCardView {
    fun create(context: Context, card: WorkspaceStoryScript.Card, onCopy: () -> Unit): View {
        fun dp(n: Int) = (n * context.resources.displayMetrics.density + 0.5f).toInt()
        fun text(value: CharSequence, size: Float): TextView = TextView(context).apply {
            this.text = value
            textSize = size
            setTextColor(Color.rgb(223, 245, 227))
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 25, 21))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(72, 101, 79))
            }
            setPadding(dp(15), dp(12), dp(15), dp(16))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copy = ImageButton(context).apply {
            setImageResource(R.drawable.ic_workspace_copy)
            imageTintList = ColorStateList.valueOf(Color.rgb(185, 222, 191))
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Copy story or script only"
            setOnClickListener { onCopy() }
        }
        top.addView(copy, LinearLayout.LayoutParams(dp(40), dp(40)))
        top.addView(text("Writing · Copy", 12f).apply {
            setTextColor(Color.rgb(185, 222, 191))
            setOnClickListener { onCopy() }
            contentDescription = "Copy story or script only"
        })
        panel.addView(top, LinearLayout.LayoutParams(-1, dp(42)))
        panel.addView(text(card.title, 18f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(10))
        })
        panel.addView(text(WorkspaceMarkdownText.render(card.body), 15f).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1.07f)
        })
        return panel
    }
}
