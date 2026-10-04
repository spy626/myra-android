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
    data class Page(
        val evidence: WorkspaceAgentReachEvidence.Evidence,
        val title: String,
        val headings: List<String>,
        val excerpt: String,
        val suggestedLinks: List<String>,
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

    private fun links(html: String, target: WorkspaceAgentReachPolicy.Target): List<String> {
        val base = URI(target.canonicalUrl)
        return Regex("""(?is)\bhref\s*=\s*["']([^"']{1,1024})["']""")
            .findAll(html).mapNotNull { match ->
                runCatching {
                    val next = WorkspaceAgentReachPolicy.parse(
                        base.resolve(unescape(match.groupValues[1])).toASCIIString())
                    next.canonicalUrl.takeIf { next.host == target.host &&
                        next.platform == target.platform && it != target.canonicalUrl }
                }.getOrNull()
            }.distinct().take(5).toList()
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
            .replace(Regex("""(?is)<(script|style|noscript|svg|iframe|form)\b[^>]*>.*?</\1\s*>"""), " ")
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
        return Page(evidence, title, headings, excerpt, if (html) links(raw, final) else emptyList())
    }
}

/** One page, at most two same-host safe redirects, cancellation on conversation changes. */
internal class WorkspaceAgentReachPublicWebRunner(
    private val currentTarget: () -> WorkspaceAgentReachPolicy.Target?,
    private val listener: Listener,
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(page: WorkspaceAgentReachPublicWeb.Page)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Call? = null

    @Synchronized fun start(target: WorkspaceAgentReachPolicy.Target) {
        cancel()
        val run = ++generation
        listener.onEvent(WorkspaceWorkPhase.VISITING, "Opening public page", target.host)
        visit(run, target, target, 0)
    }

    @Synchronized fun cancel() {
        ++generation
        active?.cancel()
        active = null
    }

    @Synchronized private fun current(run: Long, original: WorkspaceAgentReachPolicy.Target) =
        run == generation && currentTarget()?.canonicalUrl == original.canonicalUrl

    private fun visit(
        run: Long,
        original: WorkspaceAgentReachPolicy.Target,
        target: WorkspaceAgentReachPolicy.Target,
        depth: Int,
    ) {
        val call = WorkspaceAgentReachGitHub.client.newCall(WorkspaceAgentReachPublicWeb.request(target))
        synchronized(this) {
            if (!current(run, original)) return
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(run, "Public page network read failed. No retry sent.")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result ->
                    if (!current(run, original)) return@use
                    runCatching {
                        if (result.code in 300..399) {
                            require(depth < 2) { "Too many public page redirects" }
                            val next = WorkspaceAgentReachPublicWeb.redirect(
                                target, result.header("Location").orEmpty())
                            listener.onEvent(WorkspaceWorkPhase.VISITING, "Safe page redirect", next.host)
                            visit(run, original, next, depth + 1)
                        } else {
                            listener.onEvent(WorkspaceWorkPhase.READING, "Reading static page", target.host)
                            val page = WorkspaceAgentReachPublicWeb.read(result, original, target, now())
                            if (current(run, original)) {
                                listener.onEvent(WorkspaceWorkPhase.DONE, "Public page read complete", target.host)
                                listener.onComplete(page)
                            }
                        }
                    }.onFailure { fail(run, it.message ?: "Public page read stopped safely") }
                }
            }
        })
    }

    private fun fail(run: Long, reason: String) {
        synchronized(this) {
            if (run != generation) return
            active = null
        }
        listener.onEvent(WorkspaceWorkPhase.ERROR, "Public page read stopped", reason)
        listener.onError(reason)
    }
}
