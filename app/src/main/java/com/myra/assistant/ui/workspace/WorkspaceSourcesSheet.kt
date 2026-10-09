package com.myra.assistant.ui.workspace

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.material.bottomsheet.BottomSheetDialog

/** ChatGPT-like source tray for runtime-grounded public sources only. */
internal object WorkspaceSourcesSheet {
    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun text(
        activity: Activity,
        value: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
    ) = TextView(activity).apply {
        text = value
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    fun show(
        activity: Activity,
        sources: List<WorkspaceVerifiedSourceStore.Source>,
    ) {
        if (sources.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val dialog = BottomSheetDialog(activity)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 22))
            setBackgroundColor(Color.rgb(38, 40, 40))
        }
        outer.addView(View(activity).apply {
            setBackgroundColor(Color.rgb(104, 108, 108))
        }, LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(activity, 18)
        })
        outer.addView(
            text(activity, "Sources", 15f, Color.rgb(218, 222, 222), bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 12) },
        )

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sources.forEachIndexed { index, source ->
            val target = runCatching { WorkspaceAgentReachPolicy.parse(source.url) }.getOrNull()
                ?: return@forEachIndexed
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 12), 0, dp(activity, 14))
                isClickable = true
                isFocusable = true
                contentDescription = "Open source " + (index + 1) + ": " + source.title
                setOnClickListener {
                    dialog.dismiss()
                    open(activity, target.canonicalUrl)
                }
            }
            val meta = buildString {
                append(target.host.removePrefix("www."))
                source.verifiedLabel?.let { append("  ·  "); append(it) }
            }
            card.addView(text(activity, meta, 12f, Color.rgb(170, 178, 181)))
            card.addView(
                text(activity, source.title, 16f, Color.WHITE, bold = true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 3) },
            )
            if (source.snippet.isNotBlank()) {
                card.addView(
                    text(activity, source.snippet, 14f, Color.rgb(190, 196, 198)),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 4) },
                )
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2))
            if (index != sources.lastIndex) {
                list.addView(View(activity).apply {
                    setBackgroundColor(Color.rgb(72, 75, 75))
                }, LinearLayout.LayoutParams(-1, dp(activity, 1)))
            }
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        outer.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                height = (activity.resources.displayMetrics.heightPixels * 0.68f).toInt()
            },
        )
        dialog.setContentView(outer)
        dialog.show()
    }

    private fun open(activity: Activity, url: String) {
        val target = runCatching { WorkspaceAgentReachPolicy.parse(url) }.getOrNull() ?: return
        val uri = Uri.parse(target.canonicalUrl)
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri)
        }.recoverCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }
    }
}
