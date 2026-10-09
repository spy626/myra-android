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
    private const val MAX_RESULTS = 8

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
        return !isRepositoryRequest || isGitHubRepositoryUrl(url)
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
        val anchor = Regex(
            """(?is)<a\b(?=[^>]{0,1200}\bclass\s*=\s*["'][^"']*\bresult__a\b[^"']*["'])""" +
                """[^>]{0,1600}\bhref\s*=\s*["']([^"']{1,3000})["'][^>]*>(.*?)</a\s*>"""
        )
        return anchor.findAll(html).mapIndexedNotNull { index, match ->
            if (index >= 20) return@mapIndexedNotNull null
            val url = resultUrl(match.groupValues[1]) ?: return@mapIndexedNotNull null
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
            val overlapTitle = queryTokens.count { it in titleTokens }
            val overlapUrl = queryTokens.count { it in urlTokens }
            val hostMatch = preferredHost?.let {
                target.host == it || target.host.endsWith("." + it)
            } == true
            val score = overlapTitle * 8 + overlapUrl * 3 +
                (if (hostMatch) 50 else 0) - index
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
        fun onComplete(result: WorkspacePublicWebSearch.Verified)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Call? = null

    @Synchronized fun start(request: WorkspaceWebLinkIntent.Request) {
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
        } else {
            startWebSearch(run, request)
        }
    }

    private fun startWebSearch(run: Long, request: WorkspaceWebLinkIntent.Request) {
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
                require(candidates.isNotEmpty()) {
                    "No confident public search result matched this link request"
                }
                startCandidateVerification(run, request, candidates)
            },
            onFailure = { fail(run, it) },
        )
    }

    private fun startCandidateVerification(
        run: Long,
        request: WorkspaceWebLinkIntent.Request,
        candidates: List<WorkspacePublicWebSearch.Candidate>,
    ) {
        synchronized(this) { if (run != generation) return }
        listener.onEvent(
            WorkspaceWorkPhase.VERIFYING,
            "Verifying direct destination",
            candidates.first().title.take(100),
        )
        verifyCandidate(run, request, candidates, 0, null, 0)
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
    ) {
        if (index >= minOf(candidates.size, 5)) {
            fail(run, "Search results were found, but no direct public destination could be verified")
            return
        }
        val candidate = candidates[index]
        val target = redirected ?: runCatching {
            WorkspaceAgentReachPolicy.parse(candidate.url)
        }.getOrElse {
            verifyCandidate(run, request, candidates, index + 1, null, 0)
            return
        }
        dispatch(
            run,
            WorkspacePublicWebSearch.verifyRequest(target),
            onSuccess = { response ->
                response.use { result ->
                    if (result.code in 300..399) {
                        if (depth >= 2) {
                            verifyCandidate(run, request, candidates, index + 1, null, 0)
                            return@use
                        }
                        val next = runCatching {
                            WorkspacePublicWebSearch.redirect(
                                target,
                                result.header("Location").orEmpty(),
                            )
                        }.getOrElse {
                            verifyCandidate(run, request, candidates, index + 1, null, 0)
                            return@use
                        }
                        verifyCandidate(run, request, candidates, index, next, depth + 1)
                    } else {
                        val verified = runCatching {
                            WorkspacePublicWebSearch.verify(result, candidate, target, request)
                        }.getOrElse {
                            verifyCandidate(run, request, candidates, index + 1, null, 0)
                            return@use
                        }
                        synchronized(this) {
                            if (run != generation) return@use
                            active = null
                            ++generation
                        }
                        listener.onEvent(
                            WorkspaceWorkPhase.DONE,
                            "Direct link verified",
                            runCatching { URI(verified.finalUrl).host }.getOrNull(),
                        )
                        listener.onComplete(verified)
                    }
                }
            },
            onFailure = {
                verifyCandidate(run, request, candidates, index + 1, null, 0)
            },
        )
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
