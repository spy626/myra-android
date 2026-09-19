package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.myra.assistant.R
import java.io.ByteArrayInputStream

/** Native, chat-only code presentation. No generated text is executed unless Preview is tapped. */
internal object WorkspaceCodeCardView {
    private val ink = Color.rgb(223, 245, 227)
    private val accent = Color.rgb(185, 222, 191)

    private fun dp(context: Context, n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()

    private fun highlighted(code: String, language: String): CharSequence {
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
            setPadding(unit(12), unit(6), unit(12), unit(12))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        header.addView(TextView(context).apply {
            text = language
            textSize = 12f
            setTextColor(accent)
            contentDescription = "$language code"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (WorkspaceCodeBlocks.canPreview(block)) {
            header.addView(TextView(context).apply {
                text = "Preview"
                textSize = 12f
                setTextColor(accent)
                gravity = Gravity.CENTER
                setPadding(unit(10), unit(9), unit(10), unit(9))
                isClickable = true
                isFocusable = true
                contentDescription = "Preview HTML locally"
                setOnClickListener { preview(context, block.source) }
            }, LinearLayout.LayoutParams(-2, unit(42)))
        }
        val copy = TextView(context).apply {
            text = "Copy"
            textSize = 12f
            setTextColor(accent)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_workspace_copy, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(accent)
            compoundDrawablePadding = unit(5)
            gravity = Gravity.CENTER
            setPadding(unit(8), unit(7), unit(5), unit(7))
            isClickable = true
            isFocusable = true
            contentDescription = "Copy $language code only"
            setOnClickListener { onCopy() }
        }
        header.addView(copy, LinearLayout.LayoutParams(-2, unit(42)))
        card.addView(header, LinearLayout.LayoutParams(-1, unit(43)))

        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = true
        }
        horizontal.addView(TextView(context).apply {
            text = highlighted(block.source, block.language)
            setTextColor(ink)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            setPadding(unit(3), unit(6), unit(12), unit(8))
            contentDescription = "$language source code"
        }, FrameLayout.LayoutParams(-2, -2))
        card.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun preview(context: Context, html: String) {
        // An HTML snippet is untrusted. Run only after a tap, without file/content/network
        // access, storage, new windows, navigation or a JavaScript bridge.
        runCatching {
            val web = WebView(context).apply {
                settings.javaScriptEnabled = true // required for the user-requested click demo
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val scheme = request?.url?.scheme.orEmpty()
                        if (scheme == "about" || scheme == "data") return null
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }
            val holder = FrameLayout(context).apply {
                setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3))
                addView(web, FrameLayout.LayoutParams(-1, dp(context, 400)))
            }
            val dialog = AlertDialog.Builder(context).setTitle("HTML Preview · Offline")
                .setView(holder).setPositiveButton("Close", null).create()
            dialog.setOnDismissListener {
                (web.parent as? ViewGroup)?.removeView(web)
                web.stopLoading()
                web.destroy()
            }
            dialog.show()
            web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }.onFailure {
            Toast.makeText(context, "HTML preview unavailable on this phone", Toast.LENGTH_LONG).show()
        }
    }
}
