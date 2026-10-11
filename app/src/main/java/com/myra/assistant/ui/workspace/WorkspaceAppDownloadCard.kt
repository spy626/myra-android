package com.myra.assistant.ui.workspace

import android.content.Context
import com.myra.assistant.R
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Native UI projection of already-persisted verified app download Sources.
 *
 * Never parses model-authored links as trusted evidence, never mutates the original
 * reply text (Copy/Retry stay unchanged), and never guesses missing destinations.
 */
internal object WorkspaceAppDownloadCard {
    private const val PRIMARY = 0xFFF3F4F2.toInt()
    private const val SECONDARY = 0xFFA5ACA8.toInt()
    private const val BORDER = 0xFF292D2B.toInt()

    enum class Platform { ANDROID, IPHONE, WEBSITE, ANDROID_PUBLISHER }

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
                    source.title.endsWith(" — Publisher Android page") ->
                        Platform.ANDROID_PUBLISHER
                    else -> null
                } ?: return@forEach
                val url = runCatching {
                    WorkspaceAgentReachPolicy.parse(source.url).canonicalUrl
                }.getOrNull() ?: return@forEach
                val valid = when (category) {
                    Platform.ANDROID -> WorkspaceAppDownloadSearch.playUrl(url)
                    Platform.IPHONE -> WorkspaceAppDownloadSearch.appleUrl(url)
                    Platform.WEBSITE -> WorkspaceAppDownloadSearch.safePublisherWebsite(url) != null &&
                        (source.verifiedLabel.orEmpty().contains("Publisher-linked") ||
                            source.verifiedLabel.orEmpty().contains("Store-linked"))
                    Platform.ANDROID_PUBLISHER -> {
                        val web = sources.firstOrNull { it.title.endsWith(
                            " — Publisher website"
                        ) }?.url
                        web != null && WorkspaceAppPublisherDiscovery.acceptsRedirect(web, url) &&
                            java.net.URI(url).path.contains("android", ignoreCase = true) &&
                            source.verifiedLabel.orEmpty().contains("Store-linked Android")
                    }
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

    internal fun heading(platform: Platform): String = when (platform) {
        Platform.ANDROID -> "Android (Google Play Store)"
        Platform.IPHONE -> "iPhone (App Store)"
        // No unsupported "Official" ownership claim: Apple metadata only
        // established a publisher-linked page, not a dedicated download site.
        Platform.WEBSITE -> "Publisher website"
        Platform.ANDROID_PUBLISHER -> "Android (publisher download)"
    }

    internal fun linkCaption(appName: String, destination: Destination): String =
        when (destination.platform) {
            Platform.ANDROID -> "Download $appName for Android"
            Platform.IPHONE -> "Download $appName for iPhone"
            Platform.ANDROID_PUBLISHER -> "Android download from publisher site"
            Platform.WEBSITE -> {
                val parsed = runCatching { java.net.URI(destination.url) }.getOrNull()
                val domain = parsed?.host.orEmpty().removePrefix("www.")
                val path = parsed?.rawPath.orEmpty().takeIf { it != "/" }.orEmpty()
                val display = domain + path
                if (display.isNotBlank() && display.length <= 55 &&
                    !display.contains("%")) display else "Visit publisher website"
            }
        }

    private fun dp(context: Context, n: Int): Int =
        (n * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun text(
        context: Context, value: String, sp: Float,
        color: Int = PRIMARY, bold: Boolean = false,
    ) = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        includeFontPadding = true
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(context, 1).toFloat(), 1.05f)
    }

    private fun icon(context: Context, drawableId: Int, description: String) =
        ImageView(context).apply {
            setImageResource(drawableId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
        }

    /**
     * Inline, no oversized enclosing card. Rows have a 44dp clickable
     * link target but only 12dp gaps between distinct platforms.
     */
    fun create(
        context: Context, content: Content, open: (String) -> Unit,
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 3), dp(context, 2), dp(context, 3), 0)
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(icon(context, R.drawable.ic_lyra_link_chain, "Link"),
            LinearLayout.LayoutParams(dp(context, 19), dp(context, 19)).apply {
                rightMargin = dp(context, 8)
            })
        headerRow.addView(
            text(context, content.appName + " — app download links", 17f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        root.addView(headerRow, LinearLayout.LayoutParams(-1, -2))
        if (content.developer != null) {
            root.addView(
                text(context, content.developer, 12.5f, SECONDARY),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(context, 3)
                    leftMargin = dp(context, 27)
                },
            )
        }
        root.addView(View(context).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(-1, dp(context, 1)).apply {
                topMargin = dp(context, 11)
                bottomMargin = dp(context, 2)
            })

        content.destinations.forEach { destination ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 9), 0, 0)
            }
            val imageId = when (destination.platform) {
                Platform.ANDROID -> R.drawable.ic_lyra_phone
                Platform.IPHONE -> R.drawable.ic_lyra_apple_style
                Platform.WEBSITE -> R.drawable.ic_lyra_globe
                Platform.ANDROID_PUBLISHER -> R.drawable.ic_lyra_phone
            }
            line.addView(icon(context, imageId, heading(destination.platform) + " icon"),
                LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply {
                    rightMargin = dp(context, 8)
                })
            val details = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            details.addView(text(context, heading(destination.platform), 15.5f, bold = true),
                LinearLayout.LayoutParams(-1, -2))
            val caption = linkCaption(content.appName, destination)
            val link = WorkspaceDottedLinkText(context).apply {
                bind(caption)
                textSize = 15.5f
                setTextColor(PRIMARY)
                contentDescription = "$caption, opens verified external link"
                isClickable = true
                isFocusable = true
                setOnClickListener { open(destination.url) }
            }
            details.addView(link, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(context, 2)
            })
            line.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        content.missing?.let {
            root.addView(text(context, "Not confirmed: " + it, 12.5f, SECONDARY),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(context, 6)
                })
        }
        return root
    }
}
