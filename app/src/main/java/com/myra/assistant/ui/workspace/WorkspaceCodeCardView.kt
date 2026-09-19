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
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Native, chat-only code presentation. Generated HTML runs only following a Preview tap. */
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
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 25, 21))
                cornerRadius = unit(15).toFloat()
                setStroke(unit(1), Color.rgb(72, 101, 79))
            }
            setPadding(unit(14), unit(8), unit(14), unit(12))
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
        }
        header.addView(TextView(context).apply {
            text = language
            textSize = WorkspaceCodeCardStyle.HEADER_TEXT_SP
            setTextColor(accent)
            contentDescription = "$language code"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val mayPreview = WorkspaceCodeBlocks.canPreview(block)
        val previewButton = TextView(context).apply {
            text = "Preview"
            textSize = WorkspaceCodeCardStyle.HEADER_TEXT_SP
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(unit(9), unit(9), unit(9), unit(9))
            isClickable = true
            isFocusable = true
            contentDescription = "Preview HTML locally"
        }
        if (mayPreview) header.addView(previewButton,
            LinearLayout.LayoutParams(-2, unit(WorkspaceCodeCardStyle.ACTION_MIN_HEIGHT_DP)))
        header.addView(WorkspaceCodeViewer.copyIcon(context, "Copy $language code only", onCopy),
            LinearLayout.LayoutParams(unit(48), unit(WorkspaceCodeCardStyle.ACTION_MIN_HEIGHT_DP)))
        card.addView(header, LinearLayout.LayoutParams(-1, unit(WorkspaceCodeCardStyle.HEADER_HEIGHT_DP)))

        val viewport = FrameLayout(context)
        card.addView(viewport, LinearLayout.LayoutParams(-1, -2))
        var web: WebView? = null
        var showingPreview = false
        fun showCode() {
            WorkspaceCodeViewer.destroy(web)
            web = null
            viewport.removeAllViews()
            showingPreview = false
            previewButton.text = "Preview"
            previewButton.contentDescription = "Preview HTML locally"
            val horizontal = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = true
                isFillViewport = true
            }
            val source = TextView(context).apply {
                text = highlighted(block.source, block.language)
                setTextColor(ink)
                typeface = Typeface.MONOSPACE
                textSize = WorkspaceCodeCardStyle.SOURCE_TEXT_SP
                setLineSpacing(unit(WorkspaceCodeCardStyle.SOURCE_LINE_EXTRA_DP).toFloat(), 1.05f)
                setTextIsSelectable(true)
                setHorizontallyScrolling(true)
                setPadding(unit(3), unit(9), unit(14), unit(12))
                contentDescription = "$language source code; full screen available below"
            }
            horizontal.addView(source, FrameLayout.LayoutParams(-2, -2))
            val vertical = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                isFillViewport = true
            }
            vertical.addView(horizontal, FrameLayout.LayoutParams(-1, -2))
            val maxHeight = if (WorkspaceCodeViewerPolicy.needsCompactCard(block.source)) unit(310) else -2
            viewport.addView(vertical, FrameLayout.LayoutParams(-1, maxHeight))
        }
        fun showPreview() {
            if (!mayPreview) return
            WorkspaceCodeViewer.destroy(web)
            web = null
            viewport.removeAllViews()
            runCatching {
                val preview = WorkspaceCodeViewer.offlineHtml(context, block.source)
                web = preview
                viewport.addView(preview, FrameLayout.LayoutParams(-1, unit(310)))
                showingPreview = true
                previewButton.text = "Code"
                previewButton.contentDescription = "Show code instead of preview"
            }.onFailure {
                WorkspaceCodeViewer.destroy(web)
                web = null
                Toast.makeText(context, "Offline preview unavailable", Toast.LENGTH_SHORT).show()
                showCode()
            }
        }
        previewButton.setOnClickListener {
            if (showingPreview) showCode() else showPreview()
        }
        showCode()
        card.addView(TextView(context).apply {
            text = "Open full screen ↗"
            textSize = 12f
            setTextColor(accent)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(unit(8), unit(5), unit(6), unit(3))
            minHeight = unit(38)
            isClickable = true
            isFocusable = true
            contentDescription = "Open full-screen code and preview"
            setOnClickListener { WorkspaceCodeViewer.open(context, block, showingPreview, onCopy) }
        }, LinearLayout.LayoutParams(-1, -2))
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                WorkspaceCodeViewer.destroy(web)
                web = null
            }
        })
        return card
    }
}
