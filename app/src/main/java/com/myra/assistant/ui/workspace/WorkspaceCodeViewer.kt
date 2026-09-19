package com.myra.assistant.ui.workspace

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.myra.assistant.R
import java.io.ByteArrayInputStream

/** Chat-only display; no project edit and no WebView until the user taps Preview. */
internal object WorkspaceCodeViewer {
    private val ink = Color.rgb(223, 245, 227)
    private val accent = Color.rgb(185, 222, 191)
    private val surface = Color.rgb(11, 17, 14)
    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun copyIcon(context: Context, description: String, onCopy: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(R.drawable.ic_workspace_copy)
            imageTintList = ColorStateList.valueOf(accent)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(context, 11), dp(context, 11), dp(context, 11), dp(context, 11))
            contentDescription = description
            isFocusable = true
            setOnClickListener { onCopy() }
        }

    /** A generated page is untrusted: offline, no storage, files, navigation, or JS bridge. */
    fun offlineHtml(context: Context, html: String): WebView = WebView(context).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true // Only called following an explicit Preview tap.
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
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
        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    fun destroy(web: WebView?) {
        if (web == null) return
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.destroy()
    }

    fun open(context: Context, block: WorkspaceCodeBlocks.Part.Code,
             startPreview: Boolean, onCopy: () -> Unit) {
        val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 9), dp(context, 5), dp(context, 9), dp(context, 5))
        }
        val close = TextView(context).apply {
            text = "×"
            textSize = 27f
            setTextColor(ink)
            gravity = Gravity.CENTER
            contentDescription = "Close code viewer"
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        bar.addView(close, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 38, 31))
                cornerRadius = dp(context, 24).toFloat()
            }
        }
        fun tab(name: String): TextView = TextView(context).apply {
            text = name
            textSize = 14f
            setTextColor(ink)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "$name tab"
        }
        val codeTab = tab("Code")
        tabs.addView(codeTab, LinearLayout.LayoutParams(0, dp(context, 39), 1f))
        val mayPreview = WorkspaceCodeBlocks.canPreview(block)
        val previewTab = if (mayPreview) tab("Preview") else null
        if (previewTab != null)
            tabs.addView(previewTab, LinearLayout.LayoutParams(0, dp(context, 39), 1f))
        bar.addView(tabs, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(copyIcon(context, "Copy code only", onCopy),
            LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(context, 60)))

        val viewport = FrameLayout(context)
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        var web: WebView? = null
        fun select(preview: Boolean) {
            destroy(web)
            web = null
            viewport.removeAllViews()
            codeTab.background = GradientDrawable().apply {
                setColor(if (!preview) Color.rgb(43, 69, 52) else Color.TRANSPARENT)
                cornerRadius = dp(context, 20).toFloat()
            }
            previewTab?.background = GradientDrawable().apply {
                setColor(if (preview) Color.rgb(43, 69, 52) else Color.TRANSPARENT)
                cornerRadius = dp(context, 20).toFloat()
            }
            if (preview && mayPreview) {
                runCatching {
                    web = offlineHtml(context, block.source)
                    viewport.addView(web, FrameLayout.LayoutParams(-1, -1))
                }.onFailure {
                    destroy(web)
                    web = null
                    Toast.makeText(context, "Offline preview unavailable", Toast.LENGTH_SHORT).show()
                }
            } else {
                val horizontal = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = true
                    isFillViewport = true
                }
                horizontal.addView(TextView(context).apply {
                    text = WorkspaceCodeCardView.highlighted(block.source, block.language)
                    setTextColor(ink)
                    typeface = Typeface.MONOSPACE
                    textSize = WorkspaceCodeCardStyle.SOURCE_TEXT_SP
                    setLineSpacing(dp(context, WorkspaceCodeCardStyle.SOURCE_LINE_EXTRA_DP).toFloat(), 1.05f)
                    setTextIsSelectable(true)
                    setHorizontallyScrolling(true)
                    setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 22))
                    contentDescription = "Full screen source code"
                }, FrameLayout.LayoutParams(-2, -2))
                val scroll = ScrollView(context).apply { isFillViewport = true }
                scroll.addView(horizontal, ScrollView.LayoutParams(-1, -2))
                viewport.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            }
        }
        codeTab.setOnClickListener { select(false) }
        previewTab?.setOnClickListener { select(true) }
        dialog.setOnDismissListener { destroy(web); web = null }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(surface))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        select(startPreview && mayPreview)
    }
}
