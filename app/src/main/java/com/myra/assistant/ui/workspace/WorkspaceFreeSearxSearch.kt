package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Optional zero-key public-research discovery adapter.
 *
 * A public SearXNG instance may disable JSON, impose rate limits, go offline,
 * or log search terms. NEVER send credentials, conversation history, attachments
 * or private context. A 403/429/bad JSON is handled by the existing DuckDuckGo
 * route, without bypassing the instance operator's controls.
 *
 * No results enter the answer until the existing guarded URL verifier and
 * question-aware source reader have checked them.
 */
internal object WorkspaceFreeSearxSearch {
    // Public instance listed by https://searx.space/; not an availability promise.
    private const val ORIGIN = "https://searx.tiekoetter.com"
    private const val MAX_RESPONSE_BYTES = 768_000L
    private const val MAX_CANDIDATES = 16

    private val words = Regex("""[\p{L}\p{N}]{2,}""")
    private val noise = setOf(
        "site", "official", "guide", "docs", "documentation", "from", "about",
        "source", "sources", "website", "websites", "page", "pages", "the",
    )

    private fun tokens(raw: String): Set<String> =
        words.findAll(raw.lowercase(Locale.ROOT))
            .map { it.value }.filterNot { it in noise }.toSet()

    private fun encode(input: String): String =
        URLEncoder.encode(input, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun request(intent: WorkspaceWebLinkIntent.Request): Request {
        val query = intent.query.trim()
        require(query.length in 2..180 && query.none(Char::isISOControl)) {
            "Public research query is invalid"
        }
        // The user-selected host (when present) is a search scope, not an
        // arbitrary network destination for this adapter.
        val scoped = intent.preferredHost?.let { "$query site:$it" } ?: query
        val url = ORIGIN + "/search?q=" + encode(scoped) + "&format=json&safesearch=1"
        val target = WorkspaceAgentReachPolicy.parse(url)
        require(target.platform == WorkspaceAgentReachPolicy.Platform.WEB &&
            target.host == URI(ORIGIN).host && URI(target.canonicalUrl).path == "/search") {
            "SearXNG request must stay at the approved HTTPS search endpoint"
        }
        return Request.Builder()
            .url(target.canonicalUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "LYRA-PublicResearch/1")
            .get().build()
    }

    fun parseJson(
        body: String,
        intent: WorkspaceWebLinkIntent.Request,
    ): List<WorkspacePublicWebSearch.Candidate> {
        require(body.length <= MAX_RESPONSE_BYTES.toInt()) { "SearXNG output is too large" }
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return emptyList()
        val expected = tokens(intent.query)
        if (expected.isEmpty()) return emptyList()
        val candidates = mutableListOf<WorkspacePublicWebSearch.Candidate>()
        for (index in 0 until minOf(results.length(), 48)) {
            val item = results.optJSONObject(index) ?: continue
            val urlRaw = item.optString("url").trim()
            val target = runCatching { WorkspaceAgentReachPolicy.parse(urlRaw) }
                .getOrNull() ?: continue
            // No http://, private IPs, credential URLs, or unsafe link destinations.
            if (!WorkspacePublicWebSearch.matchesRequestedDestination(
                    target.canonicalUrl, intent.query, intent.preferredHost)) continue
            val title = item.optString("title").replace(
                Regex("""<[^>]{0,300}>"""), " ").replace(
                Regex("""\s+"""), " ").trim().take(180)
            if (title.length < 4) continue
            val snippet = item.optString("content").replace(
                Regex("""<[^>]{0,300}>"""), " ").replace(
                Regex("""\s+"""), " ").trim().take(300)
            val titleWords = tokens(title)
            val pathWords = tokens(target.host + " " +
                URI(target.canonicalUrl).path.orEmpty().replace('-', ' '))
            val topicHits = expected.count { it in titleWords || it in pathWords }
            // Relevance is an admission gate; 'site:' match alone isn't
            // evidence that the page answers the actual question.
            if (topicHits == 0) continue
            val score = topicHits * 8 + expected.count { it in titleWords } * 4 - index
            candidates += WorkspacePublicWebSearch.Candidate(
                title, target.canonicalUrl, snippet, score)
        }
        return candidates.distinctBy { it.url }
            .sortedByDescending { it.score }.take(MAX_CANDIDATES)
    }

    fun read(
        response: Response, intent: WorkspaceWebLinkIntent.Request,
    ): List<WorkspacePublicWebSearch.Candidate> {
        require(response.code in 200..299) {
            "Public SearXNG instance did not serve JSON (HTTP " + response.code + ")"
        }
        require(response.request.url.host == URI(ORIGIN).host &&
            response.request.url.isHttps) {
            "Public search response left the approved server"
        }
        val type = response.header("Content-Type").orEmpty()
            .substringBefore(';').trim().lowercase(Locale.ROOT)
        require(type == "application/json" || type.endsWith("+json")) {
            "Public SearXNG instance did not return JSON"
        }
        val text = response.peekBody(MAX_RESPONSE_BYTES).string()
        return parseJson(text, intent)
    }
}
