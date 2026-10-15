package com.myra.assistant.ui.workspace

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.myra.assistant.R
import java.util.Locale

/**
 * Tiny inline website identity badges shown only for persisted verified sources.
 * No favicon network request, invented publisher identity, background or clickable badges.
 */
internal object WorkspaceSourceBadgeView {
    enum class Kind { GOOGLE_PLAY, APP_STORE, DOMAIN }
    data class Badge(val kind: Kind, val host: String, val initial: String)

    fun describe(host: String): Badge {
        val clean = host.trim().lowercase(Locale.ROOT).removePrefix("www.")
        val kind = when (clean) {
            "play.google.com" -> Kind.GOOGLE_PLAY
            "apps.apple.com", "itunes.apple.com" -> Kind.APP_STORE
            else -> Kind.DOMAIN
        }
        val segments = clean.split('.')
        val root = if (segments.size >= 2) segments[segments.size - 2] else clean
        val initial = root.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "?"
        return Badge(kind, clean, initial)
    }

    fun preview(sources: List<WorkspaceVerifiedSourceStore.Source>): List<Badge> =
        sources.mapNotNull { source ->
            runCatching { describe(source.host) }.getOrNull()
        }.distinctBy { it.host }.take(3)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun create(context: Context, sources: List<WorkspaceVerifiedSourceStore.Source>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        preview(sources).forEachIndexed { index, badge ->
            val v: View = when (badge.kind) {
                Kind.GOOGLE_PLAY, Kind.APP_STORE -> ImageView(context).apply {
                    setImageResource(if (badge.kind == Kind.GOOGLE_PLAY) {
                        R.drawable.ic_lyra_source_play
                    } else {
                        R.drawable.ic_lyra_source_app_store
                    })
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                Kind.DOMAIN -> TextView(context).apply {
                    text = badge.initial
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF247D78.toInt())
                    }
                }
            }.apply {
                contentDescription = "Source website: " + badge.host
            }
            row.addView(v, LinearLayout.LayoutParams(dp(context, 15), dp(context, 15)).apply {
                if (index > 0) leftMargin = -dp(context, 3)
            })
        }
        return row
    }
}
