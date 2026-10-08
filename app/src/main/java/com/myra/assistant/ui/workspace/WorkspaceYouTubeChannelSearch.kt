package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Free, unauthenticated, read-only YouTube channel lookup.
 *
 * Search HTML is evidence only. The returned destination is the stable channel-ID URL observed
 * inside a YouTube channelRenderer; no model-generated URL is accepted as verification.
 */
internal object WorkspaceYouTubeChannelSearch {
    private const val MAX_HTML_BYTES = 2_000_000L
    private const val BLOCK_CHARS = 24_000
    private val channelMarker = "\"channelRenderer\":{"
    private val channelId = Regex("""\"channelId\"\s*:\s*\"(UC[A-Za-z0-9_-]{20,30})\"""")
    private val simpleTitle = Regex(
        """(?s)\"title\"\s*:\s*\{\s*\"simpleText\"\s*:\s*\"((?:\\.|[^\"])*)\""""
    )
    private val runTitle = Regex(
        """(?s)\"title\"\s*:\s*\{\s*\"runs\"\s*:\s*\[\s*\{\s*\"text\"\s*:\s*\"((?:\\.|[^\"])*)\""""
    )

    data class Candidate(
        val channelId: String,
        val title: String,
        val verifiedBadge: Boolean,
        val resultIndex: Int,
    ) {
        val url: String get() = "https://www.youtube.com/channel/" + channelId
    }

    private fun decodeJsonString(raw: String): String = runCatching {
        JSONArray("[\"" + raw + "\"]").getString(0)
    }.getOrDefault(raw.replace("\\u0026", "&").replace("\\/", "/"))

    private fun compact(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun tokens(value: String): Set<String> = Regex("""[\p{L}\p{N}]{2,}""")
        .findAll(value.lowercase(Locale.ROOT)).map { it.value }.toSet()

    private fun score(query: String, candidate: Candidate): Int {
        val qCompact = compact(query)
        val tCompact = compact(candidate.title)
        if (qCompact.length < 2 || tCompact.length < 2) return Int.MIN_VALUE
        val qTokens = tokens(query)
        val tTokens = tokens(candidate.title)
        val overlap = qTokens.count { it in tTokens }
        var score = when {
            qCompact == tCompact -> 1_000
            tCompact.contains(qCompact) || qCompact.contains(tCompact) -> 650
            qTokens.isNotEmpty() && qTokens.all { it in tTokens } -> 500
            overlap > 0 -> overlap * 90
            else -> Int.MIN_VALUE
        }
        if (score == Int.MIN_VALUE) return score
        if (candidate.verifiedBadge) score += 180
        score -= candidate.resultIndex.coerceAtMost(50)
        return score
    }

    fun request(query: String): Request {
        val clean = query.trim()
        require(clean.length in 2..100 && clean.none(Char::isISOControl)) {
            "YouTube channel query is invalid"
        }
        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val url = "https://www.youtube.com/results?search_query=" + encoded
        val target = WorkspaceAgentReachPolicy.parse(url)
        require(target.platform == WorkspaceAgentReachPolicy.Platform.YOUTUBE &&
            target.host.endsWith("youtube.com")) {
            "YouTube lookup escaped the approved public destination"
        }
        return Request.Builder()
            .url(target.canonicalUrl)
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "LYRA-ExactLink/1")
            .get()
            .build()
    }

    internal fun findCandidate(html: String, query: String): Candidate? {
        if (html.isBlank()) return null
        val starts = buildList {
            var from = 0
            while (true) {
                val at = html.indexOf(channelMarker, from)
                if (at < 0 || size >= 40) break
                add(at)
                from = at + channelMarker.length
            }
        }
        val candidates = starts.mapIndexedNotNull { index, start ->
            val next = starts.getOrNull(index + 1) ?: html.length
            val end = minOf(next, start + BLOCK_CHARS, html.length)
            val block = html.substring(start, end)
            val id = channelId.find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val rawTitle = simpleTitle.find(block)?.groupValues?.get(1)
                ?: runTitle.find(block)?.groupValues?.get(1)
                ?: return@mapIndexedNotNull null
            val title = decodeJsonString(rawTitle).trim().take(120)
            if (title.isBlank()) return@mapIndexedNotNull null
            Candidate(
                channelId = id,
                title = title,
                verifiedBadge = block.contains("BADGE_STYLE_TYPE_VERIFIED") ||
                    block.contains("Verified", ignoreCase = true),
                resultIndex = index,
            )
        }.distinctBy { it.channelId }

        return candidates
            .map { it to score(query, it) }
            .filter { it.second >= 500 }
            .maxWithOrNull(compareBy<Pair<Candidate, Int>> { it.second }
                .thenBy { -it.first.resultIndex })
            ?.first
    }

    fun read(response: Response, query: String): Candidate {
        require(response.isSuccessful) { "YouTube lookup failed (HTTP " + response.code + ")" }
        val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
        require(type.startsWith("text/html")) { "YouTube lookup did not return HTML" }
        // Parse only a bounded prefix. A normal YouTube results page can be larger than this,
        // but page size alone must not turn a safe read into a false failure.
        val bytes = response.peekBody(MAX_HTML_BYTES).bytes()
        require(bytes.isNotEmpty()) { "YouTube search response was empty" }
        return requireNotNull(findCandidate(String(bytes, Charsets.UTF_8), query)) {
            "No confident direct YouTube channel match was found"
        }
    }

    fun receipt(candidate: Candidate): String {
        val badge = if (candidate.verifiedBadge) " verified" else ""
        return "Ye direct" + badge + " YouTube channel mila bro: [" +
            candidate.title + "](" + candidate.url + ")"
    }
}

internal class WorkspaceYouTubeChannelSearchRunner(
    private val listener: Listener,
) {
    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(candidate: WorkspaceYouTubeChannelSearch.Candidate)
        fun onError(message: String)
    }

    private var generation = 0L
    private var active: Call? = null

    @Synchronized fun start(query: String) {
        cancelLocked()
        val run = ++generation
        listener.onEvent(WorkspaceWorkPhase.READING, "Searching YouTube channels", query.take(80))
        val request = runCatching { WorkspaceYouTubeChannelSearch.request(query) }
            .getOrElse {
                listener.onError(it.message ?: "YouTube link lookup could not start")
                return
            }
        val call = WorkspaceAgentReachGitHub.client.newCall(request)
        active = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceYouTubeChannelSearchRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                listener.onError("YouTube channel lookup network read failed")
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    runCatching { WorkspaceYouTubeChannelSearch.read(it, query) }
                }
                synchronized(this@WorkspaceYouTubeChannelSearchRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                result.onSuccess {
                    listener.onEvent(
                        WorkspaceWorkPhase.VERIFYING,
                        "Direct YouTube channel resolved",
                        it.title.take(100),
                    )
                    listener.onComplete(it)
                }.onFailure {
                    listener.onError(it.message ?: "Exact YouTube channel could not be verified")
                }
            }
        })
    }

    @Synchronized fun cancel() {
        ++generation
        cancelLocked()
    }

    private fun cancelLocked() {
        active?.cancel()
        active = null
    }
}
