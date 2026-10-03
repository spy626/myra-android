package com.myra.assistant.ui.workspace

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Native chat code card. The full source is visible; preview runs only after an explicit tap. */
internal object WorkspaceCodeCardView {
    private val ink = Color.rgb(223, 245, 227)
    private val accent = Color.rgb(185, 222, 191)
    private fun dp(context: Context, n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()

    internal fun highlighted(code: String, language: String): CharSequence {
        val result = SpannableString(code)
        fun color(pattern: Regex, shade: Int) {
            pattern.findAll(code).forEach { hit ->
                result.setSpan(ForegroundColorSpan(shade), hit.range.first, hit.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (language == "html" || language == "htm" || language == "xml") {
            color(Regex("</?[A-Za-z][A-Za-z0-9:-]*"), Color.rgb(152, 204, 245))
        } else {
            color(Regex("\\b(?:const|let|var|function|return|if|else|class|fun|val|import|from|def|async|await|public|private|void|new|true|false|null)\\b"),
                Color.rgb(152, 204, 245))
        }
        color(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), Color.rgb(214, 190, 143))
        color(Regex("(?s)/\\*.*?\\*/|<!--.*?-->|(?m)//[^\\n]*"), Color.rgb(147, 169, 154))
        return result
    }

    fun create(context: Context, block: WorkspaceCodeBlocks.Part.Code, onCopy: () -> Unit): View {
        val unit = { n: Int -> dp(context, n) }
        val openCode = { WorkspaceCodeViewer.open(context, block, false, onCopy) }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 25, 21))
                cornerRadius = unit(15).toFloat()
                setStroke(unit(1), Color.rgb(72, 101, 79))
            }
            setPadding(unit(14), unit(8), unit(14), unit(15))
            isClickable = true
            isFocusable = true
            contentDescription = "Open full-screen source code"
            setOnClickListener { openCode() }
        }
        val language = when (block.language) {
            "html", "htm" -> "HTML"
            "js", "javascript" -> "JavaScript"
            "ts", "typescript" -> "TypeScript"
            "py", "python" -> "Python"
            "css" -> "CSS"
            "" -> "Code"
            else -> block.language.replaceFirstChar { it.uppercase() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openCode() }
        }
        header.addView(TextView(context).apply {
            text = language
            textSize = WorkspaceCodeCardStyle.HEADER_TEXT_SP
            setTextColor(accent)
            contentDescription = "$language code; tap to expand"
            setOnClickListener { openCode() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (WorkspaceCodeBlocks.canPreview(block)) {
            header.addView(TextView(context).apply {
                text = "Preview"
                textSize = WorkspaceCodeCardStyle.HEADER_TEXT_SP
                setTextColor(accent)
                gravity = Gravity.CENTER
                setPadding(unit(9), unit(9), unit(9), unit(9))
                isClickable = true
                isFocusable = true
                contentDescription = "Open full-screen HTML preview offline"
                setOnClickListener { WorkspaceCodeViewer.open(context, block, true, onCopy) }
            }, LinearLayout.LayoutParams(-2, unit(WorkspaceCodeCardStyle.ACTION_MIN_HEIGHT_DP)))
        }
        header.addView(WorkspaceCodeViewer.copyIcon(context, "Copy $language code only", onCopy),
            LinearLayout.LayoutParams(unit(48), unit(WorkspaceCodeCardStyle.ACTION_MIN_HEIGHT_DP)))
        card.addView(header, LinearLayout.LayoutParams(-1, unit(WorkspaceCodeCardStyle.HEADER_HEIGHT_DP)))

        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = true
            contentDescription = "Tap to open full-screen code; swipe sideways for long lines"
            setOnClickListener { openCode() }
        }
        horizontal.addView(TextView(context).apply {
            text = highlighted(block.source, block.language)
            setTextColor(ink)
            typeface = Typeface.MONOSPACE
            textSize = WorkspaceCodeCardStyle.SOURCE_TEXT_SP
            setLineSpacing(unit(WorkspaceCodeCardStyle.SOURCE_LINE_EXTRA_DP).toFloat(), 1.05f)
            setHorizontallyScrolling(true)
            setPadding(unit(3), unit(9), unit(14), unit(12))
            contentDescription = "$language source code; tap to open full screen"
            setOnClickListener { openCode() }
        }, FrameLayout.LayoutParams(-2, -2))
        card.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
        return card
    }
}
