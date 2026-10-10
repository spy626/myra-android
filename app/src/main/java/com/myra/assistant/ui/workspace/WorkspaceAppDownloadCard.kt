package com.myra.assistant.ui.workspace

import android.content.Context
import com.myra.assistant.R
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Native UI projection of already-persisted verified app download Sources.
 *
 * Never parses model-authored links as trusted evidence, never mutates the original
 * reply text (Copy/Retry stay unchanged), and never guesses missing destinations.
 */
internal object WorkspaceAppDownloadCard {
    private const val ACCENT = 0xFF55B970.toInt()
    private const val PRIMARY = 0xFFF3F4F2.toInt()
    private const val SECONDARY = 0xFFA5ACA8.toInt()
    private const val BORDER = 0xFF292D2B.toInt()

    enum class Platform { ANDROID, IPHONE, WEBSITE }

    data class Destination(
        val platform: Platform,
        val url: String,
        val detail: String,
    )

    data class Content(
        val appName: String,
        val developer: String?,
        val destinations: List<Destination>,
        val missing: String?,
    )

    // Only the app-search-produced reply format may use this verified-source projection.
    // Other AI-generated answers and unrelated verified links remain on the old renderer.
    private val header = Regex("""^\*\*(.{2,90}) — app download links\*\*""")

    internal fun parse(
        raw: String, sources: List<WorkspaceVerifiedSourceStore.Source>,
    ): Content? {
        val requested = header.find(raw.trimStart())?.groupValues?.get(1)?.trim()
            ?: return null
        val result = buildList<Destination> {
            sources.forEach { source ->
                val category = when {
                    source.title.endsWith(" — Google Play Store") ->
                        Platform.ANDROID
                    source.title.endsWith(" — Apple App Store") ->
                        Platform.IPHONE
                    source.title.endsWith(" — Publisher website") ->
                        Platform.WEBSITE
                    else -> null
                } ?: return@forEach
                val url = runCatching {
                    WorkspaceAgentReachPolicy.parse(source.url).canonicalUrl
                }.getOrNull() ?: return@forEach
                val valid = when (category) {
                    Platform.ANDROID -> WorkspaceAppDownloadSearch.playUrl(url)
                    Platform.IPHONE -> WorkspaceAppDownloadSearch.appleUrl(url)
                    Platform.WEBSITE -> WorkspaceAppDownloadSearch.safePublisherWebsite(url) != null &&
                        source.verifiedLabel.orEmpty().contains("Publisher-linked")
                }
                if (!valid || any { it.platform == category }) return@forEach
                add(Destination(category, url, source.snippet.take(190)))
            }
        }.sortedBy { it.platform.ordinal }
        if (result.isEmpty()) return null
        val developer = sources.firstOrNull { it.title.endsWith(" — Apple App Store") }
            ?.snippet?.substringAfter("Seller:", "")
            ?.trim()?.take(90)?.takeIf(String::isNotBlank)
        val missing = raw.substringAfter("Not confirmed:", "")
            .lineSequence().firstOrNull()?.trim()?.take(140)?.takeIf(String::isNotBlank)
        return Content(requested, developer, result, missing)
    }

    private fun dp(context: Context, n: Int): Int =
        (n * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun line(context: Context, color: Int = BORDER): View =
        View(context).apply { setBackgroundColor(color) }

    private fun label(
        context: Context, value: String, size: Float,
        color: Int = PRIMARY, bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = true
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(context, 2).toFloat(), 1.04f)
    }

    private fun drawable(context: Context, resource: Int, description: String): ImageView =
        ImageView(context).apply {
            setImageResource(resource)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
        }

    private fun destinationLink(
        context: Context, caption: String, url: String,
        open: (String) -> Unit,
    ): View {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 44)
            isClickable = true
            isFocusable = true
            contentDescription = caption + ", opens verified external link"
            setOnClickListener { open(url) }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val link = label(context, caption, 16f).apply {
            // Arrow is a single literal glyph; no second automatic arrow attached.
            paint.isUnderlineText = false
        }
        row.addView(link, LinearLayout.LayoutParams(-2, -2))
        row.addView(label(context, " ↗", 15f, PRIMARY),
            LinearLayout.LayoutParams(-2, -2))
        group.addView(row, LinearLayout.LayoutParams(-2, -2))
        val underline = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(context, 1), SECONDARY, dp(context, 2).toFloat(),
                    dp(context, 3).toFloat())
            }
        }
        val captionWidth = link.paint.measureText(caption).toInt()
            .coerceAtLeast(dp(context, 30))
        group.addView(underline, LinearLayout.LayoutParams(captionWidth, dp(context, 1)).apply {
            topMargin = dp(context, 2)
        })
        return group
    }

    fun create(
        context: Context, content: Content, open: (String) -> Unit,
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(drawable(context, R.drawable.ic_lyra_link_chain, "Verified link"),
            LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply {
                rightMargin = dp(context, 9)
            })
        title.addView(label(context, content.appName + " — app download links", 17.5f,
            bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(title, LinearLayout.LayoutParams(-1, -2))
        val supporting = content.developer?.let {
            "Seller listed on Apple App Store: $it"
        } ?: "Verified app store and publisher-linked destinations"
        column.addView(label(context, supporting, 13.5f, SECONDARY),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(context, 8)
                bottomMargin = dp(context, 14)
            })
        column.addView(line(context), LinearLayout.LayoutParams(-1, dp(context, 1)))

        content.destinations.forEach { destination ->
            val block = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 15), 0, dp(context, 10))
            }
            val icon = when (destination.platform) {
                Platform.ANDROID -> R.drawable.ic_lyra_phone
                Platform.IPHONE -> R.drawable.ic_lyra_apple_style
                Platform.WEBSITE -> R.drawable.ic_lyra_globe
            }
            val platformTitle = when (destination.platform) {
                Platform.ANDROID -> "Android"
                Platform.IPHONE -> "iPhone"
                Platform.WEBSITE -> "Publisher website"
            }
            val meta = when (destination.platform) {
                Platform.ANDROID -> "Google Play Store"
                Platform.IPHONE -> "Apple App Store"
                Platform.WEBSITE -> "Publisher-linked page"
            }
            val caption = when (destination.platform) {
                Platform.ANDROID -> "Download for Android"
                Platform.IPHONE -> "Download for iPhone"
                Platform.WEBSITE -> "Visit website"
            }
            block.addView(drawable(context, icon, platformTitle + " icon"),
                LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)).apply {
                    rightMargin = dp(context, 12)
                })
            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            info.addView(label(context, platformTitle, 16f, PRIMARY, bold = true))
            info.addView(label(context, meta, 13.5f, SECONDARY),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 3) })
            info.addView(destinationLink(context, caption, destination.url, open),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 3) })
            block.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(block, LinearLayout.LayoutParams(-1, -2))
        }
        if (content.missing != null) {
            column.addView(label(context, "Not confirmed: " + content.missing, 12.5f,
                SECONDARY), LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(context, 2)
                    bottomMargin = dp(context, 8)
                })
        }
        column.addView(line(context), LinearLayout.LayoutParams(-1, dp(context, 1)).apply {
            topMargin = dp(context, 8)
        })
        return column
    }
}
