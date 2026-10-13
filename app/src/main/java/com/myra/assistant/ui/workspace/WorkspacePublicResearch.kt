package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.util.Locale

/**
 * Evidence-only public research. No AI-provider call, copied web instructions,
 * credentials, execution, browser JS, write access, or unsupported factual synthesis.
 * Produces an extractive, cited response from pages actually read at request time.
 */
internal object WorkspacePublicResearch {
    private const val MAX_PREFIX = 256_000L
    data class Extract(val source: WorkspacePublicWebSearch.Verified, val passages: List<String>)

    private val unsafeText = Regex(
        """(?iu)(?:ignore (?:all |previous |prior )?instructions|system prompt|""" +
        """you are (?:now )?(?:the )?(?:assistant|system)|""" +
        """(?:execute|run) (?:the |this )?(?:command|script)|""" +
        """(?:password|otp|token|authorization|secret|api[ -]?key)\s*[:=]|""" +
        """(?:cookies|tracking|subscribe|sign up|log in|privacy policy|""" +
        """accept all|advertisement))"""
    )
    // A heading that repeats the question is not a grounded finding.
    private val factualCue = Regex(
        """(?iu)\b(?:is|are|was|were|can|may|must|should|provides?|allows?|""" +
            """uses?|runs?|handles?|executes?|schedules?|supports?|requires?|""" +
            """starts?|stops?|ensures?|designed|intended|perform|performed|""" +
            """continue|continues|persist|needs?|display|displays|reduces?|reduced|""" +
            """improves?|improved|limits?|limited|manages?|managed|""" +
            """prevents?|prevented|helps?|helped|enables?|enabled|""" +
            """disables?|disabled|automatically|adjusts?|adjusted|saves?|saved)\b"""
    )
    private val ignored = setOf(
        "with", "which", "what", "when", "from", "have", "does", "how", "why",
        "that", "this", "about", "please", "explain", "difference", "between",
        "kya", "hai", "mein", "with", "reliable", "sources", "source", "saath",
        "karo", "batao", "android", "official", "documentation",
    )
    private fun normalize(text: String): String = text
        .replace("&nbsp;", " ", true).replace("&amp;", "&", true)
        .replace("&lt;", "<", true).replace("&gt;", ">", true)
        .replace("&quot;", "\"", true).replace("&#39;", "'", true)
        .replace("&minus;", "−", true).replace("&ndash;", "–", true)
        .replace("&mdash;", "—", true).replace("&rsquo;", "'", true)
        .replace(Regex("""&#(\d{1,6});""")) {
            it.groupValues[1].toIntOrNull()?.takeIf { n ->
                n in 32..0x10ffff && n !in 0xd800..0xdfff
            }?.let { n -> String(Character.toChars(n)) } ?: " "
        }
        .replace(Regex("""\s+"""), " ").trim()

    private fun terms(question: String): Set<String> =
        Regex("""[\p{L}\p{N}]{4,}""")
            .findAll(question.lowercase(Locale.ROOT)).map { it.value }
            .filterNot { it in ignored }.toSet()

    /**
     * Read real DOM article blocks rather than treating a small set of regex-matched
     * <p>/<li> tags as the whole webpage. Inspired by Android article readers:
     * headings provide observed topical context, but never count as standalone facts.
     * This remains a bounded, read-only static HTML parser; it does not execute JS.
     */
    private fun articleBlocks(html: String): List<String> {
        val doc = Jsoup.parse(html)
        doc.select(
            "script, style, noscript, svg, iframe, form, template, nav, footer, " +
                "header, aside, [role=navigation], [role=banner], [role=contentinfo]"
        ).remove()
        val main = doc.select("main, article, [role=main]")
            .maxByOrNull { it.text().length }
        val root: Element = main?.takeIf { it.text().length >= 120 }
            ?: doc.body() ?: return emptyList()

        var heading = ""
        val blocks = mutableListOf<String>()
        val nodes = root.select("h2, h3, h4, p, li, dd, dt, blockquote")
        for (node in nodes.take(420)) {
            if (node.tagName() in setOf("h2", "h3", "h4")) {
                heading = normalize(node.text()).take(120)
                continue
            }
            // Nested list paragraphs may repeat their parent text; de-duplicate later.
            val prose = normalize(node.text())
            if (prose.isBlank()) continue
            // A comparison heading repeats both topics; attaching it to every
            // paragraph would wrongly turn single-subject evidence into a
            // two-sided claim. Treat that heading as navigation, not evidence.
            val headingPrefix = heading.takeIf {
                it.isNotBlank() && !prose.contains(it, ignoreCase = true) &&
                    !Regex("""(?iu)\b(?:vs|versus|and|aur|compare|comparison)\b""")
                        .containsMatchIn(it)
            }
            if (prose.length <= 550) {
                blocks += if (headingPrefix == null) prose else headingPrefix + ": " + prose
                continue
            }
            // Long official documentation paragraphs must not disappear altogether.
            // Keep actual, ordered sentence spans; never generate new factual text.
            val sentences = prose.split(Regex("""(?<=[.!?])\s+"""))
            var chunk = ""
            fun flush() {
                if (chunk.isNotBlank()) {
                    blocks += if (headingPrefix == null) chunk
                        else headingPrefix + ": " + chunk
                    chunk = ""
                }
            }
            for (sentence in sentences) {
                if (sentence.length > 480) {
                    flush()
                    sentence.chunked(480).forEach { part ->
                        blocks += if (headingPrefix == null) part
                            else headingPrefix + ": " + part
                    }
                } else if (chunk.length + sentence.length + 1 > 470) {
                    flush()
                    chunk = sentence
                } else {
                    chunk = if (chunk.isEmpty()) sentence else chunk + " " + sentence
                }
            }
            flush()
        }
        // Some static pages use article DIVs rather than paragraph elements.
        if (blocks.isEmpty()) {
            root.select("div, section").toList().filter {
                it.children().none { child -> child.tagName() in setOf("div", "section", "p", "li") }
            }.take(100).forEach { node ->
                val prose = normalize(node.text())
                if (prose.length in 65..550) blocks += prose
            }
        }
        return blocks.distinct().take(420)
    }

    fun extract(
        response: Response, source: WorkspacePublicWebSearch.Verified, question: String,
    ): Extract? {
        if (!response.isSuccessful) return null
        val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
        if (!type.startsWith("text/html") && !type.startsWith("text/plain")) return null
        if (type.contains("charset=") &&
            !Regex("""(?i)charset\s*=\s*["']?utf-8\b""").containsMatchIn(type)) return null
        val bytes = response.peekBody(MAX_PREFIX).bytes()
        if (bytes.size < 100) return null
        val raw = bytes.toString(Charsets.UTF_8)
        val candidates = if (type.startsWith("text/plain")) raw.split('\n')
            else articleBlocks(raw)
        val topic = terms(question)
        val subjects = comparisonSubjects(question)
        val candidatesWithScores = candidates.asSequence()
            .map(::normalize)
            .filter { it.length in 65..550 && it.count(Char::isLetter) >= 40 &&
                it.split(Regex("""\s+""")).size >= 10 &&
                factualCue.containsMatchIn(it) &&
                !unsafeText.containsMatchIn(it) &&
                !WorkspaceSourceContext.containsPossibleSecret(it) }
            .map { line ->
                val lineWords = Regex("""[\p{L}\p{N}]{4,}""")
                    .findAll(line.lowercase(Locale.ROOT)).map { it.value }.toSet()
                val onTopic = WorkspaceResearchQuestionFocus.passageOnTopic(
                    question, source, line)
                // Discovery is deliberately recall-friendly: keep paragraphs whose
                // words match the actual question even if the contextual
                // topic heuristic is uncertain. The separate relevantExtracts
                // and model citation audit remain strict before final output.
                line to topic.count { it in lineWords } + (if (onTopic) 1 else 0)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .toList()
        // Preserve independent factual passages for both comparison subjects.
        // A comparison headline alone can never become evidence for either side.
        val focused = if (subjects.size == 2) subjects.mapNotNull { subject ->
            candidatesWithScores.firstOrNull { (line, _) ->
                line.contains(subject, ignoreCase = true) &&
                    subjects.filterNot { it == subject }.none {
                        line.contains(it, ignoreCase = true)
                    }
            }?.first
        } else emptyList()
        val selected = (focused + candidatesWithScores.map { it.first })
            .distinct().take(if (subjects.size == 2) 3 else 2)
            .map { it.take(320).trimEnd(' ', ',', ';', ':') }
        return if (selected.isEmpty()) null else Extract(source, selected)
    }

    /** Bounded comparison formatting, using *only* page passages for both sides. */
    internal fun comparisonSubjects(question: String): List<String> {
        if (!Regex("""(?iu)\b(?:difference|different|compare|comparison|versus|vs|farq)\b""")
                .containsMatchIn(question)) return emptyList()
        val pair = Regex(
            """(?iu)\b([\p{L}][\p{L}\p{N}+#-]{2,}(?:\s+[\p{L}][\p{L}\p{N}+#-]{2,})?)""" +
                """\s+(?:aur|and|vs|versus)\s+""" +
                """([\p{L}][\p{L}\p{N}+#-]{2,}(?:\s+[\p{L}][\p{L}\p{N}+#-]{2,})?)"""
        ).find(question)
        val contrasted = if (pair == null) Regex(
            """(?iu)\b([\p{L}][\p{L}\p{N}+#-]{2,})\s+""" +
                """(?:different\s+from|differs\s+from)\s+""" +
                """([\p{L}][\p{L}\p{N}+#-]{2,}(?:\s+[\p{L}][\p{L}\p{N}+#-]{2,})?)"""
        ).find(question) else null
        val found = pair ?: contrasted ?: return emptyList()
        return listOf(found.groupValues[1], found.groupValues[2]).map { part ->
            part.split(' ').filterNot {
                it.lowercase(Locale.ROOT) in setOf(
                    "mein", "main", "the", "and", "aur", "compare",
                    "comparison", "difference", "between", "vs", "versus",
                    "explain", "farq", "ki", "ka", "ke", "ko", "karo",
                )
            }.joinToString(" ").let { cleaned ->
                val pieces = cleaned.split(' ')
                if (pieces.size == 2 && pieces[1].lowercase(Locale.ROOT) in setOf(
                        "battery", "optimization", "development", "performance",
                        "camera", "privacy", "security", "charging", "usage"
                    )) pieces.first() else cleaned
            }
        }.takeIf { it.all { x -> x.length in 3..50 } } ?: emptyList()
    }

    /** Every factual passage has its own observed, verified destination URL. */
    fun render(
        question: String, evidence: List<Extract>, reachable: Int,
    ): String {
        if (evidence.isEmpty()) return "Bro, " + reachable +
            " public link(s) reachable mile, lekin un pages se question-related " +
            "readable text verify nahi hua. Main bina page evidence ke answer invent nahi karunga."
        return buildString {
            appendLine("**Verified webpages se findings**")
            appendLine()
            appendLine("Question: " + question.take(200))
            appendLine()
            appendLine("Neeche direct page text se relevant points hain; ye AI-generated " +
                "full comparison nahi, aur sources ka reachable hona unki correctness guarantee nahi karta.")
            val subjects = comparisonSubjects(question)
            if (subjects.size == 2) {
                val observed = evidence.flatMap { hit ->
                    hit.passages.map { it to hit.source.finalUrl }
                }
                val lines = subjects.mapNotNull { subject ->
                    observed.firstOrNull { (passage, _) ->
                        passage.contains(subject, ignoreCase = true) &&
                            subjects.filterNot { it == subject }.none {
                                passage.contains(it, ignoreCase = true)
                            } && factualCue.containsMatchIn(passage)
                    }?.let { (passage, url) -> Triple(subject, passage, url) }
                }
                if (lines.size == 2 && lines[0].second != lines[1].second) {
                    appendLine()
                    appendLine("**Side-by-side evidence**")
                    lines.forEach { (subject, passage, url) ->
                        appendLine("**" + subject + ":** " +
                            passage.replace(Regex("""[\[\]*]"""), "").take(320) +
                            " ([Source](" + url + "))")
                    }
                }
            }
            evidence.forEachIndexed { index, hit ->
                appendLine()
                appendLine("**Source " + (index + 1) + " · " +
                    hit.source.title.replace(Regex("""[\[\]*]"""), "").take(110) + "**")
                hit.passages.forEach { passage ->
                    appendLine("• " + passage.replace(Regex("""[\[\]*]"""), ""))
                }
                appendLine("[Open source " + (index + 1) + "](" + hit.source.finalUrl + ")")
            }
            if (evidence.size < reachable) {
                appendLine()
                append("Some reachable pages had no suitable readable evidence; omitted.")
            }
        }.trim().take(4_900)
    }
}

/** Cancel-safe sequential HTTP reads of up to three previously verified public sites. */
internal class WorkspacePublicResearchRunner(
    private val listener: Listener,
) {
    interface Listener {
        fun onEvent(label: String)
        fun onComplete(evidence: List<WorkspacePublicResearch.Extract>)
    }
    private var generation = 0L
    private var active: Call? = null
    @Synchronized fun cancel() {
        generation++
        active?.cancel()
        active = null
    }
    @Synchronized fun start(
        question: String, sources: List<WorkspacePublicWebSearch.Verified>,
    ) {
        cancel()
        val run = generation
        inspect(run, question, sources.take(3), 0, emptyList())
    }
    private fun inspect(
        run: Long, question: String, sources: List<WorkspacePublicWebSearch.Verified>,
        index: Int, found: List<WorkspacePublicResearch.Extract>,
    ) {
        synchronized(this) { if (run != generation) return }
        if (index >= sources.size) {
            listener.onComplete(found)
            return
        }
        val source = sources[index]
        val target = runCatching { WorkspaceAgentReachPolicy.parse(source.finalUrl) }
            .getOrNull()
        if (target == null || source.contentType !in setOf("text/html", "text/plain")) {
            inspect(run, question, sources, index + 1, found)
            return
        }
        listener.onEvent("Reading public page " + (index + 1) + " of " + sources.size)
        val request = Request.Builder().url(target.canonicalUrl)
            .header("Accept", "text/html, text/plain")
            .header("Range", "bytes=0-255999")
            .header("User-Agent", "LYRA-PublicResearch/1")
            .get().build()
        val call = WorkspaceAgentReachGitHub.client.newCall(request)
        synchronized(this) {
            if (run != generation) { call.cancel(); return }
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspacePublicResearchRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                inspect(run, question, sources, index + 1, found)
            }
            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspacePublicResearchRunner) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                // Source URL must still be the actual final destination at read time.
                // HTTP redirects after discovery cannot relabel unrelated page text.
                val hit = response.use { fetched ->
                    runCatching {
                        if (fetched.request.url.toString() != target.canonicalUrl) null
                        else WorkspacePublicResearch.extract(fetched, source, question)
                    }.getOrNull()
                }
                inspect(run, question, sources, index + 1,
                    if (hit == null) found else found + hit)
            }
        })
    }
}
