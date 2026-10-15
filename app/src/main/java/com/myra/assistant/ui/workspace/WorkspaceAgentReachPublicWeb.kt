package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Read-only public HTTPS adapter. Uses existing guarded DNS client; never runs JS or uses cookies. */
internal object WorkspaceAgentReachPublicWeb {
    private const val MAX_BYTES = 96_000L
    data class Link(val url: String, val label: String)
    data class Page(
        val evidence: WorkspaceAgentReachEvidence.Evidence,
        val title: String,
        val headings: List<String>,
        val excerpt: String,
        val suggestedLinks: List<String>,
        val observedLinks: List<Link> = emptyList(),
    )

    fun request(target: WorkspaceAgentReachPolicy.Target): Request {
        require(target.platform != WorkspaceAgentReachPolicy.Platform.GITHUB) {
            "GitHub must use the existing pinned revision adapter"
        }
        return Request.Builder().url(target.canonicalUrl)
            .header("Accept", "text/html, text/plain")
            .header("User-Agent", "LYRA-AgentReach/1")
            .get().build()
    }

    fun redirect(
        previous: WorkspaceAgentReachPolicy.Target,
        location: String,
    ): WorkspaceAgentReachPolicy.Target {
        require(location.isNotBlank() && location.length <= 2_048) { "Invalid page redirect" }
        val next = WorkspaceAgentReachPolicy.validateRedirect(
            previous, URI(previous.canonicalUrl).resolve(location).toASCIIString())
        require(next.host == previous.host && next.platform == previous.platform) {
            "Redirect left the approved public site; no new host was contacted"
        }
        return next
    }

    private fun unescape(raw: String): String = raw
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'")

    private fun text(raw: String): String = unescape(raw)
        .replace(Regex("""(?s)<[^>]{0,1024}>"""), " ")
        .replace(Regex("""[\p{Cntrl}&&[^\n\t]]"""), " ")
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n\s*\n+"""), "\n").trim()

    /** Only anchors observed in sanitized static HTML are eligible for later navigation. */
    private fun links(html: String, target: WorkspaceAgentReachPolicy.Target): List<Link> {
        val base = URI(target.canonicalUrl)
        return Regex("""(?is)<a\b[^>]{0,1800}\bhref\s*=\s*["']([^"']{1,1024})["'][^>]{0,1800}>(.*?)</a\s*>""")
            .findAll(html).mapNotNull { match ->
                runCatching {
                    val next = WorkspaceAgentReachPolicy.parse(
                        base.resolve(unescape(match.groupValues[1])).toASCIIString())
                    val label = text(match.groupValues[2]).take(120)
                    Link(next.canonicalUrl, label).takeIf {
                        next.host == target.host && next.platform == target.platform &&
                            next.canonicalUrl != target.canonicalUrl
                    }
                }.getOrNull()
            }.distinctBy { it.url }.take(16).toList()
    }

    fun read(
        response: Response,
        requested: WorkspaceAgentReachPolicy.Target,
        final: WorkspaceAgentReachPolicy.Target,
        atMs: Long,
    ): Page {
        require(response.isSuccessful) { "Public page HTTP " + response.code + "; no retry" }
        val contentType = response.header("Content-Type").orEmpty().lowercase(java.util.Locale.ROOT)
        require(contentType.startsWith("text/html") || contentType.startsWith("text/plain")) {
            "Not supported static HTML/plain text; media/PDF/dynamic/login need another adapter"
        }
        require(!contentType.contains("charset=") ||
            Regex("""(?i)charset\s*=\s*["']?utf-8\b""").containsMatchIn(contentType)) {
            "Public page is not UTF-8"
        }
        require((response.body?.contentLength() ?: -1L) <= MAX_BYTES) {
            "Public page exceeds bounded read"
        }
        val bytes = response.peekBody(MAX_BYTES + 1L).bytes()
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_BYTES) {
            "Public page is empty or too large"
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val raw = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { throw IllegalArgumentException("Invalid UTF-8 public page") }
        val html = contentType.startsWith("text/html")
        val title = if (html) Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
            .find(raw)?.groupValues?.get(1)?.let(::text).orEmpty().take(180) else ""
        val headings = if (html) Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]\s*>""")
            .findAll(raw).map { text(it.groupValues[1]).take(160) }
            .filter(String::isNotBlank).distinct().take(8).toList() else emptyList()
        val cleaned = if (html) raw
            .replace(Regex("""(?s)<!--.*?-->"""), " ")
            .replace(Regex("""(?is)<(script|style|noscript|svg|iframe|form|template)\b[^>]*>.*?</\1\s*>"""), " ")
            .replace(Regex("""(?is)<head\b[^>]*>.*?</head\s*>"""), " ")
            .replace(Regex("""(?is)<br\b[^>]*>"""), "\n")
            .replace(Regex("""(?is)</(?:p|div|article|main|section|li|h[1-6])\s*>"""), "\n")
            else raw
        val excerpt = text(cleaned).take(5_000)
        require(excerpt.isNotBlank() || title.isNotBlank() || headings.isNotEmpty()) {
            "No readable static page text; interactive browser may be needed"
        }
        val content = buildString {
            if (title.isNotBlank()) appendLine("Title: " + title)
            headings.forEach { appendLine("Heading: " + it) }
            appendLine("Static page excerpt:")
            append(excerpt)
        }.take(12_000)
        val evidence = WorkspaceAgentReachEvidence.create(
            requested, final, "public-html-static", content, atMs)
        val observed = if (html) links(cleaned, final) else emptyList()
        return Page(evidence, title, headings, excerpt,
            observed.take(5).map { it.url }, observed)
    }
}

/**
 * Existing native public reader's bounded observe -> choose -> verify extension.
 * One initial page + at most ONE user-topic-relevant observed same-site link; no recursion.
 * Source text and navigational labels are never sent to an AI provider by this runner.
 */
internal class WorkspaceAgentReachPublicWebRunner(
    private val currentTarget: () -> WorkspaceAgentReachPolicy.Target?,
    private val listener: Listener,
    private val now: () -> Long = System::currentTimeMillis,
    private val executor: Executor = OkHttpExecutor,
) {
    interface Cancelable {
        fun cancel()
    }

    fun interface Executor {
        fun enqueue(request: Request, callback: (Result<Response>) -> Unit): Cancelable
    }

    private object OkHttpExecutor : Executor {
        override fun enqueue(
            request: Request,
            callback: (Result<Response>) -> Unit,
        ): Cancelable {
            val call = WorkspaceAgentReachGitHub.client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    callback(Result.success(response))
                }
            })
            return object : Cancelable {
                override fun cancel() = call.cancel()
            }
        }
    }

    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(journey: WorkspaceAgentReachWebNavigation.Journey)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Cancelable? = null

    @Synchronized fun start(
        target: WorkspaceAgentReachPolicy.Target,
        userRequest: String,
    ) {
        cancel()
        val run = ++generation
        listener.onEvent(WorkspaceWorkPhase.VISITING, "Opening public page", target.host)
        visit(run, target, target, target, depth = 0,
            onPage = { primary ->
                val choice = WorkspaceAgentReachWebNavigation.choose(primary, userRequest)
                if (choice == null) {
                    complete(run, target, WorkspaceAgentReachWebNavigation.Journey(primary), userRequest)
                } else {
                    listener.onEvent(WorkspaceWorkPhase.THINKING,
                        "Choosing observed relevant same-site link",
                        choice.matchedTerms.joinToString(", ").take(90))
                    listener.onEvent(WorkspaceWorkPhase.VISITING,
                        "Opening one relevant public page", choice.target.canonicalUrl.take(160))
                    visit(run, target, choice.target, choice.target, depth = 0,
                        onPage = { followed ->
                            val journey = runCatching {
                                WorkspaceAgentReachWebNavigation.verify(primary, choice, followed)
                            }.getOrElse {
                                WorkspaceAgentReachWebNavigation.Journey(primary, null, choice,
                                    "Follow-up verification failed; no secondary content was accepted")
                            }
                            complete(run, target, journey, userRequest)
                        },
                        onError = {
                            complete(run, target, WorkspaceAgentReachWebNavigation.Journey(
                                primary, null, choice,
                                "Relevant follow-up page was unavailable; initial verified page retained"), userRequest)
                        })
                }
            }, onError = { fail(run, it) })
    }

    @Synchronized fun cancel() {
        ++generation
        active?.cancel()
        active = null
    }

    @Synchronized private fun current(
        run: Long,
        origin: WorkspaceAgentReachPolicy.Target,
    ) = run == generation && currentTarget()?.canonicalUrl == origin.canonicalUrl

    private fun visit(
        run: Long,
        origin: WorkspaceAgentReachPolicy.Target,
        requested: WorkspaceAgentReachPolicy.Target,
        target: WorkspaceAgentReachPolicy.Target,
        depth: Int,
        onPage: (WorkspaceAgentReachPublicWeb.Page) -> Unit,
        onError: (String) -> Unit,
    ) {
        val request = WorkspaceAgentReachPublicWeb.request(target)
        synchronized(this) { if (!current(run, origin)) return }
        val handle = executor.enqueue(request) { responseResult ->
            val response = responseResult.getOrElse {
                if (current(run, origin)) onError("Public page network read failed; no retry")
                return@enqueue
            }
            response.use { result ->
                if (!current(run, origin)) return@use
                runCatching {
                    if (result.code in 300..399) {
                        require(depth < 2) { "Too many redirects" }
                        val next = WorkspaceAgentReachPublicWeb.redirect(
                            target, result.header("Location").orEmpty())
                        require(next.host == origin.host) {
                            "Redirect left the originally approved public site"
                        }
                        listener.onEvent(WorkspaceWorkPhase.VISITING, "Safe page redirect", next.host)
                        visit(run, origin, requested, next, depth + 1, onPage, onError)
                    } else {
                        listener.onEvent(WorkspaceWorkPhase.READING, "Reading bounded static page",
                            target.canonicalUrl.take(160))
                        val page = WorkspaceAgentReachPublicWeb.read(
                            result, requested, target, now())
                        if (current(run, origin)) onPage(page)
                    }
                }.onFailure {
                    if (current(run, origin))
                        onError(it.message ?: "Public page read stopped safely")
                }
            }
        }
        synchronized(this) {
            if (current(run, origin)) active = handle else handle.cancel()
        }
    }

    private fun complete(
        run: Long,
        origin: WorkspaceAgentReachPolicy.Target,
        journey: WorkspaceAgentReachWebNavigation.Journey,
        userRequest: String,
    ) {
        synchronized(this) {
            if (!current(run, origin)) return
            active = null
            ++generation // Exactly one terminal receipt for this request.
        }
        // Deterministic bounded synthesis runs only on this accepted terminal path.
        // No new network step, model provider request, persistence or permission.
        val analysis = WorkspaceAgentReachSourceAnalysis.analyze(userRequest, journey)
        val completed = journey.copy(analysis = analysis)
        listener.onEvent(WorkspaceWorkPhase.VERIFYING, "Verified public evidence",
            if (analysis.verifiedPageCount == 2) "2 bounded pages" else "1 bounded page")
        listener.onEvent(WorkspaceWorkPhase.THINKING, "Comparing goal-relevant source evidence",
            "${analysis.findings.size} bounded excerpts; goal completion unverified")
        listener.onComplete(completed)
    }

    private fun fail(run: Long, message: String) {
        synchronized(this) {
            if (run != generation) return
            active = null
            ++generation
        }
        listener.onEvent(WorkspaceWorkPhase.ERROR, "Public page read stopped", message)
        listener.onError(message)
    }
}
