package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.Locale

/**
 * Independent public app-site discovery when a verified store listing lacks a publisher URL.
 *
 * No "official website" claim from search ranking or HTTP 200. A candidate must
 * have an app-matching first-party hostname, an app-matching page title, and a
 * cross-link to the exact verified Play package or App Store numeric ID.
 * Such a source is labeled store-linked, not publisher-certified.
 */
internal object WorkspaceAppPublisherDiscovery {
    data class Page(
        val siteUrl: String,
        val androidPages: List<String>,
    )

    private val titleTag = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
    private val tag = Regex("""<[^>]{0,1000}>""")
    private val anchor = Regex(
        """(?is)<a\b[^>]{0,1800}\bhref\s*=\s*["']([^"']{1,2000})["'][^>]*>(.*?)</a\s*>"""
    )
    private val forbiddenFile = Regex("""(?iu)\.(?:apk|apks|xapk|exe|zip|dmg|ipa)(?:$|\?)""")
    private const val MAX_HTML_BYTES = 256_000L

    fun searchRequest(name: String): Request =
        WorkspacePublicWebSearch.searchRequest(
            WorkspaceWebLinkIntent.Request("$name official app website download")
        )

    fun searchCandidates(html: String, name: String): List<WorkspacePublicWebSearch.Candidate> =
        WorkspacePublicWebSearch.parseHtml(html,
            "$name official app website download", null)
            .filter { brandHost(it.url, name) }
            .sortedByDescending { if (URI(it.url).path.trimEnd('/').endsWith("/apps")) 1 else 0 }
            .distinctBy { it.url }
            .take(4)

    fun readSearch(response: Response, name: String): List<WorkspacePublicWebSearch.Candidate> {
        require(response.isSuccessful)
        require(response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            .startsWith("text/html"))
        val bytes = response.peekBody(768_001).bytes()
        require(bytes.isNotEmpty() && bytes.size <= 768_000)
        return searchCandidates(String(bytes, Charsets.UTF_8), name)
    }

    fun brandHost(url: String, name: String): Boolean {
        val host = runCatching { WorkspaceAgentReachPolicy.parse(url).host }.getOrNull()
            ?: return false
        val components = host.removePrefix("www.").split('.')
        if (components.size < 2) return false
        // Compare the registrable-looking label, never the first subdomain.
        // E.g. telegram.org.evil.com must resolve to "evil", not "telegram".
        val label = components[components.size - 2].lowercase(Locale.ROOT)
        val expected = WorkspaceAppDownloadIntent.compact(name)
        if (expected.length < 4 || expected.length > 42) return false
        return label == expected || (
            name.contains(' ') && label == WorkspaceAppDownloadIntent.compact(
                name.substringBefore(' ')
            )
        )
    }

    fun acceptsRedirect(start: String, end: String): Boolean {
        val a = runCatching { WorkspaceAgentReachPolicy.parse(start).host }.getOrNull()
            ?: return false
        val b = runCatching { WorkspaceAgentReachPolicy.parse(end).host }.getOrNull()
            ?: return false
        val base = a.removePrefix("www.")
        val destination = b.removePrefix("www.")
        return base == destination || destination.endsWith("." + base)
    }

    private fun title(html: String): String = titleTag.find(html)?.groupValues?.get(1)
        ?.replace(tag, " ")?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()

    private fun siteTitleMatches(name: String, title: String): Boolean {
        if (WorkspaceAppDownloadSearch.storeNameMatches(name, title)) return true
        val first = title.lowercase(Locale.ROOT).substringBefore(" — ")
            .substringBefore(" - ").substringBefore(':')
        val nameLower = name.lowercase(Locale.ROOT).trim()
        val suffix = first.removePrefix(nameLower).trim()
        return first.startsWith(nameLower + " ") &&
            suffix in setOf("apps", "applications", "downloads", "for android", "messenger")
    }

    private fun storeIdentityPresent(html: String, storeUrls: List<String>): Boolean {
        if (storeUrls.isEmpty()) return false
        val normalized = html.lowercase(Locale.ROOT)
        return storeUrls.any { url ->
            when {
                WorkspaceAppDownloadSearch.playUrl(url) -> {
                    val packageId = URI(url).rawQuery.orEmpty().split('&')
                        .firstOrNull { it.startsWith("id=") }?.substringAfter('=').orEmpty()
                    packageId.length >= 6 && normalized.contains(packageId.lowercase(Locale.ROOT))
                }
                WorkspaceAppDownloadSearch.appleUrl(url) -> {
                    val appId = Regex("""id\d{5,15}""").find(URI(url).path.orEmpty())
                        ?.value.orEmpty()
                    appId.isNotBlank() && normalized.contains(appId.lowercase(Locale.ROOT))
                }
                else -> false
            }
        }
    }

    private fun androidPages(site: String, html: String): List<String> = anchor.findAll(html)
        .mapNotNull { match ->
            val raw = match.groupValues[1].replace("&amp;", "&")
            val label = match.groupValues[2].replace(tag, " ")
            val pathCue = raw.contains("/android", ignoreCase = true)
            if (!label.contains("android", ignoreCase = true) &&
                !label.contains("apk", ignoreCase = true) && !pathCue) return@mapNotNull null
            val address = runCatching { URI(site).resolve(raw).toASCIIString() }.getOrNull()
                ?: return@mapNotNull null
            val target = runCatching { WorkspaceAgentReachPolicy.parse(address) }.getOrNull()
                ?: return@mapNotNull null
            if (!acceptsRedirect(site, target.canonicalUrl) ||
                forbiddenFile.containsMatchIn(URI(target.canonicalUrl).path)) return@mapNotNull null
            val path = URI(target.canonicalUrl).path.lowercase(Locale.ROOT)
            if (path.isBlank() || path == "/" || path == URI(site).path) return@mapNotNull null
            target.canonicalUrl
        }.distinct().take(3).toList()

    fun inspect(
        url: String, html: String, name: String, storeUrls: List<String>,
    ): Page? {
        if (html.length !in 150..MAX_HTML_BYTES || !brandHost(url, name)) return null
        val observedTitle = title(html)
        if (!siteTitleMatches(name, observedTitle)) return null
        if (!storeIdentityPresent(html, storeUrls)) return null
        return Page(url, androidPages(url, html))
    }

    fun verifiedAndroidPage(
        publisherUrl: String, url: String, html: String, name: String,
    ): Boolean {
        if (!acceptsRedirect(publisherUrl, url) ||
            forbiddenFile.containsMatchIn(URI(url).path) ||
            !URI(url).path.contains("android", ignoreCase = true)) return false
        val observed = title(html)
        return html.length >= 100 &&
            WorkspaceAppDownloadIntent.compact(observed).contains(
                WorkspaceAppDownloadIntent.compact(name)
            ) && observed.contains("android", ignoreCase = true)
    }
}
