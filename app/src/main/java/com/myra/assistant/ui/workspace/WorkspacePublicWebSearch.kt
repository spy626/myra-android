package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Free, unauthenticated, read-only public web discovery.
 *
 * Search results are candidate evidence only. A result is returned only after the exact public
 * HTTPS destination (or a bounded redirect chain) responds through guarded DNS.
 */
internal object WorkspacePublicWebSearch {
    private const val SEARCH_ORIGIN = "https://html.duckduckgo.com/html/"
    private const val MAX_SEARCH_BYTES = 768_000L
    private const val MAX_VERIFY_PREFIX = 64_000L
    private const val MAX_RESULTS = 16

    data class Candidate(
        val title: String,
        val url: String,
        val snippet: String,
        val score: Int,
    )

    data class Verified(
        val title: String,
        val finalUrl: String,
        val snippet: String,
        val contentType: String,
    )

    private val unsafePath = Regex(
        """(?iu)(?:^|[/_.-])(?:login|logout|signin|signup|register|account|checkout|""" +
            """payment|pay|oauth|auth|callback)(?:$|[/_.-])"""
    )
    private val challengeTitle = Regex(
        """(?iu)^(?:just a moment|access denied|attention required|checking your browser|sign in)\b"""
    )
    private val repositoryCue = Regex("""(?iu)\b(?:repo|repos|repository|repositories)\b""")
    private val githubOwnersNotRepositories = setOf(
        "about", "apps", "collections", "customer-stories", "dashboard", "enterprise",
        "explore", "features", "issues", "login", "marketplace", "new", "notifications",
        "orgs", "organizations", "pricing", "pulls", "search", "security", "settings",
        "site", "sponsors", "topics", "trending",
    )

    /** A reachable GitHub profile or GitHub feature page is NOT a repository. */
    internal fun isGitHubRepositoryUrl(url: String): Boolean {
        val target = runCatching { WorkspaceAgentReachPolicy.parse(url) }.getOrNull()
            ?: return false
        if (target.host != "github.com" && target.host != "www.github.com") return false
        val path = runCatching { URI(target.canonicalUrl).path.orEmpty() }
            .getOrDefault("").trim('/')
        val segments = path.split('/')
        if (segments.size != 2 || segments[0].lowercase(Locale.ROOT) in githubOwnersNotRepositories) {
            return false
        }
        val owner = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})""")
        val repository = Regex("""[A-Za-z0-9_.-]{1,100}""")
        return owner.matches(segments[0]) && repository.matches(segments[1]) &&
            segments[1] !in setOf(".", "..")
    }

    internal fun matchesRequestedDestination(
        url: String,
        query: String,
        preferredHost: String?,
    ): Boolean {
        val isRepositoryRequest = preferredHost == "github.com" &&
            repositoryCue.containsMatchIn(query)
        val target = runCatching { WorkspaceAgentReachPolicy.parse(url) }.getOrNull()
            ?: return false
        if (preferredHost != null &&
            target.host != preferredHost && !target.host.endsWith("." + preferredHost)) {
            return false
        }
        return !isRepositoryRequest || (
            isGitHubRepositoryUrl(url) &&
                WorkspaceGitHubRepositorySearch.nameMatchesQuery(url, query)
            )
    }

    private val ignore = setOf(
        "link", "url", "website", "site", "page", "official", "direct", "exact",
        "the", "a", "an", "ka", "ki", "ke", "ko", "bro", "mujhe",
    )

    private fun htmlDecode(raw: String): String = raw
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&minus;", "−", ignoreCase = true)
        .replace("&ndash;", "–", ignoreCase = true)
        .replace("&mdash;", "—", ignoreCase = true)
        .replace("&rsquo;", "'", ignoreCase = true)

    private fun stripHtml(raw: String): String = htmlDecode(raw)
        .replace(Regex("""(?s)<[^>]{0,1000}>"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun tokens(value: String): Set<String> =
        Regex("""[\p{L}\p{N}]{2,}""").findAll(value.lowercase(Locale.ROOT))
            .map { it.value }.filterNot { it in ignore }.toSet()

    private fun queryParameter(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key != name) null else runCatching {
                URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8.name())
            }.getOrNull()
        }

    private fun resultUrl(hrefRaw: String): String? {
        val href = htmlDecode(hrefRaw.trim())
        val resolved = runCatching { URI(SEARCH_ORIGIN).resolve(href).toASCIIString() }.getOrNull()
            ?: return null
        val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
        val rawDestination = if (
            uri.host?.lowercase(Locale.ROOT)?.endsWith("duckduckgo.com") == true &&
            uri.path == "/l/"
        ) queryParameter(uri, "uddg") else resolved
        val target = runCatching {
            WorkspaceAgentReachPolicy.parse(rawDestination ?: return null)
        }.getOrNull() ?: return null
        if (target.host.endsWith("duckduckgo.com")) return null
        val path = runCatching { URI(target.canonicalUrl).path.orEmpty() }.getOrDefault("")
        if (unsafePath.containsMatchIn(path)) return null
        return target.canonicalUrl
    }

    /** One conservative query relaxation for normal documentation searches only. */
    internal fun broaderDocumentationRequest(
        request: WorkspaceWebLinkIntent.Request,
    ): WorkspaceWebLinkIntent.Request? {
        if (request.preferredHost != null) return null
        val shorter = request.query.trim().replace(
            Regex("""(?iu)\s+(?:documentation|docs|guide|tutorial)$"""), ""
        ).trim()
        if (shorter == request.query.trim() ||
            shorter.length < 6 || shorter.split(Regex("""\s+""")).size < 2) return null
        return request.copy(query = shorter)
    }


    /** Canonical publisher documentation hosts; never fabricate a result URL from this table. */
    internal fun officialDocumentationHost(query: String): String? {
        val words = tokens(query)
        // Mixed-platform research must not be silently redirected to Android
        // developer documentation. Official docs are a hint for a single
        // ecosystem, not evidence about the comparison's other side.
        if ("android" in words && words.any {
                it in setOf("ios", "iphone", "ipad", "apple")
            }) return null
        return when {
            "ios" in words || "iphone" in words || "ipad" in words -> "support.apple.com"
            "kotlin" in words -> "kotlinlang.org"
            "android" in words || "workmanager" in words ||
                ("foreground" in words && "service" in words) -> "developer.android.com"
            "python" in words -> "docs.python.org"
            "javascript" in words || "mozilla" in words -> "developer.mozilla.org"
            "rust" in words -> "doc.rust-lang.org"
            else -> null
        }
    }

    private fun isOfficialDocumentation(url: String, query: String): Boolean {
        val expected = officialDocumentationHost(query) ?: return false
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        val host = parsed.host.orEmpty().lowercase(Locale.ROOT)
        return (host == expected || host.endsWith("." + expected)) &&
            parsed.path.orEmpty().trim('/').isNotEmpty()
    }

    /** One bounded site-specific follow-up; keeps the existing single-link path untouched. */
    internal fun followUpRequest(
        request: WorkspaceWebLinkIntent.Request, previous: List<Verified>,
    ): WorkspaceWebLinkIntent.Request? {
        if (request.maxResults == 1 || request.preferredHost != null) return null
        val official = officialDocumentationHost(request.query)
        if (official != null &&
            previous.count { isOfficialDocumentation(it.finalUrl, request.query) } <
                request.maxResults) return request.copy(preferredHost = official)
        if (previous.size < request.maxResults) return broaderDocumentationRequest(request)
        return null
    }

    internal fun selectResults(
        request: WorkspaceWebLinkIntent.Request, results: List<Verified>,
    ): List<Verified> = results.distinctBy { it.finalUrl }
        .sortedWith(compareByDescending<Verified> {
            if (isOfficialDocumentation(it.finalUrl, request.query)) 1 else 0
        }.thenByDescending {
            tokens(request.query).count { term -> term in tokens(it.title) }
        }).take(request.maxResults)

    fun searchRequest(request: WorkspaceWebLinkIntent.Request): Request {
        val query = request.query.trim()
        require(query.length in 2..180 && query.none(Char::isISOControl)) {
            "Web link search query is invalid"
        }
        val scoped = request.preferredHost?.let { query + " site:" + it } ?: query
        val encoded = URLEncoder.encode(scoped, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val target = WorkspaceAgentReachPolicy.parse(SEARCH_ORIGIN + "?q=" + encoded)
        require(target.platform == WorkspaceAgentReachPolicy.Platform.WEB &&
            target.host == "html.duckduckgo.com") {
            "Web search escaped the approved public search endpoint"
        }
        return Request.Builder()
            .url(target.canonicalUrl)
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "LYRA-PublicSearch/1")
            .get()
            .build()
    }

    internal fun parseHtml(
        html: String,
        query: String,
        preferredHost: String?,
    ): List<Candidate> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        val subjectTokens = (queryTokens - setOf(
            "repo", "repos", "repository", "repositories", "docs", "documentation",
            "article", "articles", "product", "products", "video", "videos",
            "channel", "channels", "source", "sources", "page", "pages"
        )).ifEmpty { queryTokens }
        // DuckDuckGo has at least two public HTML variants and attribute order varies.
        // Never trust a random anchor: require the search-result class and a safe HTTPS target.
        val anchor = Regex("""(?is)<a\b([^>]{0,2200})>(.*?)</a\s*>""")
        val href = Regex("""(?is)\bhref\s*=\s*["']([^"']{1,3000})["']""")
        val resultClass = Regex(
            """(?is)\bclass\s*=\s*["'][^"']*\b(?:result__a|result-link)\b[^"']*["']"""
        )
        return anchor.findAll(html).take(150).mapIndexedNotNull { index, match ->
            val attributes = match.groupValues[1]
            if (!resultClass.containsMatchIn(attributes)) return@mapIndexedNotNull null
            val rawUrl = href.find(attributes)?.groupValues?.get(1)
                ?: return@mapIndexedNotNull null
            val url = resultUrl(rawUrl) ?: return@mapIndexedNotNull null
            if (!matchesRequestedDestination(url, query, preferredHost)) return@mapIndexedNotNull null
            val target = WorkspaceAgentReachPolicy.parse(url)
            val title = stripHtml(match.groupValues[2]).take(180)
            if (title.length < 2) return@mapIndexedNotNull null
            val tailEnd = minOf(html.length, match.range.last + 3500)
            val tail = html.substring(match.range.last + 1, tailEnd)
            val snippet = Regex(
                """(?is)class\s*=\s*["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div|span)\s*>"""
            ).find(tail)?.groupValues?.get(1)?.let(::stripHtml).orEmpty().take(300)

            val titleTokens = tokens(title)
            val urlTokens = tokens(target.host + " " + URI(url).path.orEmpty())
            val snippetTokens = tokens(snippet)
            // Result snippets can contain the topic while a publisher uses a
            // synonym in the title (e.g. a feature name instead of its category).
            // Keep pinned-host/direct-link lookups strict; research candidates
            // still undergo HTTPS verification and actual page evidence checks.
            if (subjectTokens.none { it in titleTokens || it in urlTokens } &&
                (preferredHost != null || subjectTokens.none { it in snippetTokens })) {
                return@mapIndexedNotNull null
            }
            val overlapTitle = queryTokens.count { it in titleTokens }
            val overlapUrl = queryTokens.count { it in urlTokens }
            val overlapSnippet = queryTokens.count { it in snippetTokens }
            val hostMatch = preferredHost?.let {
                target.host == it || target.host.endsWith("." + it)
            } == true
            val score = overlapTitle * 8 + overlapUrl * 3 + overlapSnippet * 3 +
                (if (hostMatch) 50 else 0) +
                (if (isOfficialDocumentation(url, query)) 45 else 0) - index
            val minimum = if (hostMatch) 50 else 8
            Candidate(title, url, snippet, score).takeIf { score >= minimum }
        }.distinctBy { it.url }
            .sortedByDescending { it.score }
            .take(MAX_RESULTS)
            .toList()
    }

    fun readSearch(
        response: Response,
        request: WorkspaceWebLinkIntent.Request,
    ): List<Candidate> {
        require(response.isSuccessful) { "Public web search failed (HTTP " + response.code + ")" }
        val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
        require(type.startsWith("text/html")) { "Public web search did not return HTML" }
        val bytes = response.peekBody(MAX_SEARCH_BYTES).bytes()
        require(bytes.isNotEmpty()) { "Public web search returned no readable result page" }
        return parseHtml(String(bytes, Charsets.UTF_8), request.query, request.preferredHost)
    }

    fun verifyRequest(target: WorkspaceAgentReachPolicy.Target): Request =
        Request.Builder()
            .url(target.canonicalUrl)
            .header("Accept", "text/html, text/plain, application/xhtml+xml, application/pdf")
            .header("Range", "bytes=0-" + (MAX_VERIFY_PREFIX - 1))
            .header("User-Agent", "LYRA-LinkVerify/1")
            .get()
            .build()

    fun redirect(
        previous: WorkspaceAgentReachPolicy.Target,
        location: String,
    ): WorkspaceAgentReachPolicy.Target {
        require(location.isNotBlank() && location.length <= 2_048) {
            "Verified destination redirect is invalid"
        }
        val next = WorkspaceAgentReachPolicy.validateRedirect(
            previous,
            URI(previous.canonicalUrl).resolve(location).toASCIIString(),
        )
        val path = URI(next.canonicalUrl).path.orEmpty()
        require(!unsafePath.containsMatchIn(path)) {
            "Verified destination redirected to an authentication/payment path"
        }
        return next
    }

    fun verify(
        response: Response,
        candidate: Candidate,
        final: WorkspaceAgentReachPolicy.Target,
        request: WorkspaceWebLinkIntent.Request? = null,
    ): Verified {
        require(request == null || matchesRequestedDestination(
            final.canonicalUrl, request.query, request.preferredHost
        )) { "Final destination is not the requested GitHub repository page" }
        require(response.code in 200..299) {
            "Candidate destination returned HTTP " + response.code
        }
        val type = response.header("Content-Type").orEmpty()
            .substringBefore(';').trim().lowercase(Locale.ROOT)
        require(type in setOf(
            "text/html", "text/plain", "application/xhtml+xml", "application/pdf",
        )) { "Candidate destination is not a supported public document/page" }

        val observedTitle = if (type in setOf("text/html", "application/xhtml+xml")) {
            val prefix = response.peekBody(MAX_VERIFY_PREFIX).string()
            Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
                .find(prefix)?.groupValues?.get(1)?.let(::stripHtml).orEmpty().take(180)
        } else ""
        require(observedTitle.isBlank() || !challengeTitle.containsMatchIn(observedTitle)) {
            "Candidate destination returned an access/challenge page"
        }
        return Verified(
            title = observedTitle.ifBlank { candidate.title },
            finalUrl = final.canonicalUrl,
            snippet = candidate.snippet,
            contentType = type,
        )
    }

    /** Return only independent runtime-verified destinations. */
    internal fun canAddResult(
        request: WorkspaceWebLinkIntent.Request,
        results: List<Verified>,
        candidate: Verified,
    ): Boolean {
        if (!matchesRequestedDestination(
                candidate.finalUrl, request.query, request.preferredHost
            )) return false
        if (results.any { it.finalUrl == candidate.finalUrl }) return false
        if (request.maxResults == 1 || request.preferredHost != null) return true
        val host = URI(candidate.finalUrl).host.removePrefix("www.").lowercase(Locale.ROOT)
        return results.none {
            URI(it.finalUrl).host.removePrefix("www.").lowercase(Locale.ROOT) == host
        } || isOfficialDocumentation(candidate.finalUrl, request.query)
    }

    fun receiptAll(results: List<Verified>, requested: Int): String {
        require(results.isNotEmpty() && requested in 1..3)
        if (requested == 1) return receipt(results.first())
        return buildString {
            appendLine("**" + results.size + " reachable public links**")
            if (results.size < requested) {
                appendLine("Requested " + requested + "; only " + results.size + " could be verified.")
            }
            results.forEachIndexed { index, result ->
                appendLine()
                appendLine("**" + (index + 1) + ".**")
                appendLine(receipt(result))
            }
        }.trim()
    }

    fun receipt(result: Verified): String {
        val uri = URI(result.finalUrl)
        val repoParts = uri.path.orEmpty().trim('/').split('/')
        val title = if (isGitHubRepositoryUrl(result.finalUrl) && repoParts.size == 2) {
            repoParts.joinToString("/") + " — GitHub repository"
        } else result.title
        return WorkspaceVerifiedLinkReply.format(
            title = title,
            url = result.finalUrl,
            summary = result.snippet,
            fallback = "Live web search se direct public page mila. Link kholkar details dekho.",
        )
    }

    fun source(
        result: Verified,
        observedAtMs: Long = System.currentTimeMillis(),
    ): WorkspaceVerifiedSourceStore.Source =
        WorkspaceVerifiedSourceStore.Source(
            title = result.title,
            url = result.finalUrl,
            snippet = result.snippet.ifBlank {
                "Direct public destination matched from a live web search and responded successfully."
            },
            observedAtMs = observedAtMs,
            verifiedLabel = "Live web result · reachable",
        )
}

internal class WorkspacePublicWebSearchRunner(
    private val listener: Listener,
) {
    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(results: List<WorkspacePublicWebSearch.Verified>)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Call? = null
    // One failed public SearXNG probe must not multiply network timeouts across
    // every query in a multi-round task. In-memory only; no user data persisted.
    private var metaUnavailableUntilMs = 0L
    private val metaCooldownMs = 10 * 60 * 1000L

    @Synchronized fun start(
        request: WorkspaceWebLinkIntent.Request,
        useFreeMetaSearch: Boolean = false,
    ) {
        cancelLocked()
        val run = ++generation
        if (WorkspaceGitHubRepositorySearch.supports(request)) {
            listener.onEvent(
                WorkspaceWorkPhase.SEARCHING,
                "Searching GitHub repositories",
                request.query.take(100),
            )
            // GitHub's public repository index is structured evidence. Profiles cannot be
            // misread as repositories, and unavailable/rate-limited API reads fall back safely.
            dispatch(
                run,
                WorkspaceGitHubRepositorySearch.searchRequest(request),
                onSuccess = { response ->
                    val candidates = response.use {
                        runCatching {
                            WorkspaceGitHubRepositorySearch.readSearch(it, request)
                        }.getOrDefault(emptyList())
                    }
                    if (candidates.isEmpty()) startWebSearch(run, request)
                    else startCandidateVerification(run, request, candidates)
                },
                onFailure = { startWebSearch(run, request) },
            )
        } else if (useFreeMetaSearch) {
            startFreeMetaSearch(run, request)
        } else {
            startWebSearch(run, request)
        }
    }

    /**
     * SearXNG is ONLY an optional public-research discovery backend. Public
     * instances can reject JSON or rate-limit requests; never retry or bypass
     * their restrictions. Fail over to the existing DuckDuckGo path.
     */
    private fun startFreeMetaSearch(
        run: Long, request: WorkspaceWebLinkIntent.Request,
    ) {
        synchronized(this) {
            if (run != generation) return
            if (System.currentTimeMillis() < metaUnavailableUntilMs) {
                startWebSearch(run, request)
                return
            }
        }
        val metaRequest = runCatching { WorkspaceFreeSearxSearch.request(request) }
            .getOrNull()
        if (metaRequest == null) {
            startWebSearch(run, request)
            return
        }
        listener.onEvent(
            WorkspaceWorkPhase.SEARCHING, "Searching free metasearch",
            request.query.take(100),
        )
        dispatch(
            run,
            metaRequest,
            onSuccess = { response ->
                val hits = response.use {
                    WorkspaceFreeSearxSearch.read(it, request)
                }
                if (hits.isEmpty()) startWebSearch(run, request)
                else startCandidateVerification(
                    run, request, hits, metaFallback = true)
            },
            onFailure = {
                synchronized(this) {
                    if (run == generation) {
                        metaUnavailableUntilMs = System.currentTimeMillis() + metaCooldownMs
                    }
                }
                startWebSearch(run, request)
            },
        )
    }

    private fun startWebSearch(
        run: Long, request: WorkspaceWebLinkIntent.Request, broadened: Boolean = false,
        previous: List<WorkspacePublicWebSearch.Verified> = emptyList(),
        original: WorkspaceWebLinkIntent.Request = request,
    ) {
        synchronized(this) { if (run != generation) return }
        listener.onEvent(
            WorkspaceWorkPhase.SEARCHING,
            "Searching the web",
            request.query.take(100),
        )
        dispatch(
            run,
            WorkspacePublicWebSearch.searchRequest(request),
            onSuccess = { response ->
                val candidates = response.use { WorkspacePublicWebSearch.readSearch(it, request) }
                if (candidates.isEmpty()) {
                    if (!broadened) {
                        val retry = WorkspacePublicWebSearch.followUpRequest(original, previous)
                        if (retry != null) {
                            startWebSearch(run, retry, true, previous, original)
                            return@dispatch
                        }
                    }
                    if (previous.isNotEmpty()) {
                        completeCandidates(run, original, previous, true)
                        return@dispatch
                    }
                }
                require(candidates.isNotEmpty()) {
                    "No confident public search result matched this link request"
                }
                startCandidateVerification(run, request, candidates, broadened, previous, original)
            },
            onFailure = {
                if (!broadened) {
                    val retry = WorkspacePublicWebSearch.followUpRequest(original, previous)
                    if (retry != null) startWebSearch(run, retry, true, previous, original)
                    else fail(run, it)
                } else if (previous.isNotEmpty()) {
                    completeCandidates(run, original, previous, true)
                } else fail(run, it)
            },
        )
    }

    private fun startCandidateVerification(
        run: Long,
        request: WorkspaceWebLinkIntent.Request,
        candidates: List<WorkspacePublicWebSearch.Candidate>,
        broadened: Boolean = false,
        previous: List<WorkspacePublicWebSearch.Verified> = emptyList(),
        original: WorkspaceWebLinkIntent.Request = request,
        metaFallback: Boolean = false,
    ) {
        synchronized(this) { if (run != generation) return }
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Verifying direct destination",
            candidates.first().title.take(100),
        )
        verifyCandidate(run, request, candidates, 0, null, 0, previous.toMutableList(),
            broadened, original, metaFallback)
    }

    @Synchronized fun cancel() {
        ++generation
        cancelLocked()
    }

    private fun cancelLocked() {
        active?.cancel()
        active = null
    }

    private fun verifyCandidate(
        run: Long,
        request: WorkspaceWebLinkIntent.Request,
        candidates: List<WorkspacePublicWebSearch.Candidate>,
        index: Int,
        redirected: WorkspaceAgentReachPolicy.Target?,
        depth: Int,
        verifiedResults: MutableList<WorkspacePublicWebSearch.Verified>,
        broadened: Boolean,
        original: WorkspaceWebLinkIntent.Request,
        metaFallback: Boolean = false,
    ) {
        // Keep the proven single-link path at 5 attempts. Plural requests can
        // inspect up to 8 candidates and finish with fewer than requested.
        val maxCandidates = if (request.maxResults == 1) 5 else 12
        val targetCount = if (broadened) minOf(6, original.maxResults + 3)
            else original.maxResults
        if (index >= minOf(candidates.size, maxCandidates) ||
            verifiedResults.size >= targetCount) {
            if (metaFallback && verifiedResults.isEmpty()) {
                // SearXNG returned links but none passed the existing guarded
                // HTTP verification. Don't report a research failure yet.
                startWebSearch(run, original)
            } else completeCandidates(run, original, verifiedResults, broadened)
            return
        }
        val candidate = candidates[index]
        fun next() {
            verifyCandidate(run, request, candidates, index + 1, null, 0, verifiedResults,
                broadened, original, metaFallback)
        }
        val target = redirected ?: runCatching {
            WorkspaceAgentReachPolicy.parse(candidate.url)
        }.getOrElse {
            next()
            return
        }
        dispatch(
            run,
            WorkspacePublicWebSearch.verifyRequest(target),
            onSuccess = { response ->
                response.use { result ->
                    if (result.code in 300..399) {
                        if (depth >= 2) {
                            next()
                            return@use
                        }
                        val redirect = runCatching {
                            WorkspacePublicWebSearch.redirect(
                                target, result.header("Location").orEmpty()
                            )
                        }.getOrElse {
                            next()
                            return@use
                        }
                        verifyCandidate(
                            run, request, candidates, index, redirect, depth + 1,
                            verifiedResults, broadened, original, metaFallback,
                        )
                    } else {
                        val verified = runCatching {
                            WorkspacePublicWebSearch.verify(result, candidate, target, request)
                        }.getOrNull()
                        if (verified != null &&
                            WorkspacePublicWebSearch.canAddResult(
                                original, verifiedResults, verified
                            )) {
                            verifiedResults.add(verified)
                        }
                        next()
                    }
                }
            },
            onFailure = { next() },
        )
    }

    private fun completeCandidates(
        run: Long,
        original: WorkspaceWebLinkIntent.Request,
        results: List<WorkspacePublicWebSearch.Verified>,
        broadened: Boolean,
    ) {
        if (!broadened) {
            val followUp = WorkspacePublicWebSearch.followUpRequest(original, results)
            if (followUp != null) {
                startWebSearch(run, followUp, true, results, original)
                return
            }
        }
        synchronized(this) {
            if (run != generation) return
            active = null
            ++generation
        }
        val selected = WorkspacePublicWebSearch.selectResults(original, results)
        if (selected.isEmpty()) {
            listener.onError("No direct public destination could be verified")
            return
        }
        listener.onEvent(
            WorkspaceWorkPhase.DONE,
            selected.size.toString() + " reachable public link(s)",
        )
        listener.onComplete(selected)
    }

    private fun dispatch(
        run: Long,
        request: Request,
        onSuccess: (Response) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        synchronized(this) { if (run != generation) return }
        val call = WorkspaceAgentReachGitHub.client.newCall(request)
        synchronized(this) {
            if (run != generation) {
                call.cancel()
                return
            }
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspacePublicWebSearchRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                onFailure("Public web network read failed")
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspacePublicWebSearchRunner) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                runCatching { onSuccess(response) }.onFailure {
                    response.close()
                    onFailure(it.message ?: "Public web link verification stopped safely")
                }
            }
        })
    }

    private fun fail(run: Long, message: String) {
        synchronized(this) {
            if (run != generation) return
            active = null
            ++generation
        }
        // finishWebLinkLookup records a single final failure; don't duplicate it in the trace.
        listener.onError(message)
    }
}
