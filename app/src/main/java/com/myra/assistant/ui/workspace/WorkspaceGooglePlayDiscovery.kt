package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Store-native fallback when a third-party search provider returns no verified Play links.
 * Index cards are candidates ONLY; callers still verify the destination and its real page title.
 * No hardcoded apps, packages, fabricated store URLs, or APK download.
 */
internal object WorkspaceGooglePlayDiscovery {
    private const val ORIGIN = "https://play.google.com"
    private const val MAX_BYTES = 3_000_000
    private val anchors = Regex("""(?is)<a\b([^>]{0,2500})>(.{0,9000}?)</a\s*>""")
    private val href = Regex("""(?is)\bhref\s*=\s*["']([^"']{1,2000})["']""")
    private val tag = Regex("""(?is)<[^>]{0,1200}>""")
    private val innerLabels = Regex("""(?is)<(?:span|div)[^>]{0,800}>([^<]{1,150})</(?:span|div)>""")
    private val embeddedStoreRefs = Regex(
        """(?i)(?:https://play\.google\.com)?/store/apps/details\?id=""" +
            """([A-Za-z][\w]*(?:\.[A-Za-z][\w]*){1,9})"""
    )

    fun searchRequest(app: String): Request {
        require(app.length in 2..90 && app.none(Char::isISOControl))
        val encoded = URLEncoder.encode(app, StandardCharsets.UTF_8.name())
        val url = "$ORIGIN/store/search?q=$encoded&c=apps&hl=en"
        require(WorkspaceAgentReachPolicy.parse(url).host == "play.google.com")
        return Request.Builder().url(url)
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (compatible; LYRA-PublicAppLookup/1)")
            .get().build()
    }

    private fun decode(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#38;", "&")
        .replace("&#x3d;", "=", ignoreCase = true)
        .replace("&#61;", "=")
        .replace("\\u003d", "=", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)

    private fun absolute(raw: String): String? {
        val value = decode(raw)
        if (value.startsWith("//") || value.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        val full = runCatching { URI(ORIGIN).resolve(value).toASCIIString() }.getOrNull()
            ?: return null
        val target = runCatching { WorkspaceAgentReachPolicy.parse(full) }.getOrNull()
            ?: return null
        return target.canonicalUrl.takeIf { WorkspaceAppDownloadSearch.playUrl(it) }
    }

    /**
     * Google Play search HTML may contain app cards or embedded result data.
     * Either yields candidates only; destination title must be verified later.
     */
    internal fun parseHtml(html: String, name: String): List<WorkspacePublicWebSearch.Candidate> {
        if (html.isBlank() || html.length > MAX_BYTES) return emptyList()
        val cards = anchors.findAll(html).take(150).mapNotNull { match ->
            val link = href.find(match.groupValues[1])?.groupValues?.get(1)
                ?.let(::absolute) ?: return@mapNotNull null
            val text = decode(match.groupValues[2])
                .replace(tag, " ").replace(Regex("""\s+"""), " ").trim()
            val attributes = decode(match.groupValues[1])
            val titles = buildList {
                add(text.substringBefore(" • ").substringBefore("·").trim())
                innerLabels.findAll(match.groupValues[2]).take(12).forEach {
                    add(decode(it.groupValues[1]).trim())
                }
                Regex("""(?is)\b(?:aria-label|title)\s*=\s*["']([^"']{1,150})["']""")
                    .findAll(attributes).forEach { add(it.groupValues[1].trim()) }
            }
            val exact = titles.firstOrNull {
                WorkspaceAppDownloadSearch.storeNameMatches(name, it)
            } ?: return@mapNotNull null
            WorkspacePublicWebSearch.Candidate(exact.take(180), link, "", 100)
        }.toList()

        // Observe real package IDs in Google's page, don't derive IDs from app names.
        val embedded = embeddedStoreRefs.findAll(decode(html)).take(200).mapNotNull { match ->
            val url = ORIGIN + "/store/apps/details?id=" + match.groupValues[1]
            val safe = absolute(url) ?: return@mapNotNull null
            WorkspacePublicWebSearch.Candidate("Google Play candidate", safe, "", 1)
        }.toList()
        return (cards + embedded).distinctBy {
            URI(it.url).rawQuery.orEmpty().split('&')
                .firstOrNull { part -> part.startsWith("id=") }
        }.take(12)
    }

    fun readSearch(response: Response, name: String): List<WorkspacePublicWebSearch.Candidate> {
        require(response.isSuccessful)
        require(response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            .startsWith("text/html"))
        val data = response.peekBody(MAX_BYTES + 1L).bytes()
        require(data.isNotEmpty() && data.size <= MAX_BYTES)
        return parseHtml(String(data, Charsets.UTF_8), name)
    }

    /** Only a first-party Play page's observed HTML title can certify the app name. */
    internal fun pageTitleMatches(name: String, verified: WorkspacePublicWebSearch.Verified): Boolean {
        return WorkspaceAppDownloadSearch.playUrl(verified.finalUrl) &&
            WorkspaceAppDownloadSearch.storeNameMatches(name, verified.title)
    }
}
