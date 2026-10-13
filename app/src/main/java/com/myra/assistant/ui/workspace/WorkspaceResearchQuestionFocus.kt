package com.myra.assistant.ui.workspace

import java.net.URI
import java.util.Locale

/**
 * Question-aware read-only research relevance. This is a topic/coverage check,
 * NOT a claim about truth or about the independence of source publishers.
 * Direct-link and GitHub repository lookups do not go through this policy.
 */
internal object WorkspaceResearchQuestionFocus {
    data class Focus(
        val subjects: List<String>,
        val topicWords: List<String>,
    ) {
        val topicAnchor: String? get() = topicWords.firstOrNull()
    }

    private val words = Regex("""[\p{L}\p{N}+#.-]{3,}""")
    private val instructionWords = setOf(
        "lyra", "bro", "please", "mujhe", "kya", "hai", "aur", "and",
        "mein", "main", "mai", "ke", "ki", "ka", "ko", "se", "saath",
        "batao", "bataye", "karo", "kar", "lekar", "answer", "reply",
        "give", "tell", "explain", "difference", "different", "compare",
        "comparison", "versus", "reliable", "multiple", "websites", "website",
        "sources", "source", "citations", "citation", "references", "evidence",
        "information", "details", "search", "research", "online", "using",
        "from", "with", "about", "these", "their", "this", "that", "what",
        "when", "where", "which", "between", "both", "more", "latest",
        "facts", "fact", "official", "find", "best", "how", "why",
    )

    private fun tokens(text: String): List<String> =
        words.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    fun inspect(question: String): Focus {
        val subjects = WorkspacePublicResearch.comparisonSubjects(question)
        val subjectTokens = subjects.flatMap(::tokens).toSet()
        val topic = tokens(question)
            .filterNot { it in instructionWords || it in subjectTokens }
            .distinct().take(5)
        return Focus(subjects, topic)
    }

    private fun textFor(source: WorkspacePublicWebSearch.Verified): String = buildString {
        append(source.title)
        append(" ")
        append(source.snippet)
        append(" ")
        append(runCatching {
            val uri = URI(source.finalUrl)
            uri.host.orEmpty() + " " + uri.path.orEmpty()
        }.getOrDefault(""))
    }.lowercase(Locale.ROOT)

    private fun includesToken(haystack: String, token: String): Boolean =
        Regex("""(?iu)(?<![\p{L}\p{N}])""" + Regex.escape(token) +
            """(?![\p{L}\p{N}])""").containsMatchIn(haystack)

    private fun aliases(subject: String): List<String> = when (subject.lowercase(Locale.ROOT)) {
        "ios" -> listOf("ios", "iphone", "ipad", "apple")
        "android" -> listOf("android", "pixel", "samsung")
        else -> listOf(subject.lowercase(Locale.ROOT))
    }

    fun mentionsSubject(text: String, subject: String): Boolean =
        aliases(subject).any { alias ->
            if (' ' in alias) text.contains(alias, ignoreCase = true)
            else includesToken(text.lowercase(Locale.ROOT), alias)
        }

    fun sourceMentionsSubject(
        source: WorkspacePublicWebSearch.Verified, subject: String,
    ): Boolean = mentionsSubject(textFor(source), subject)

    fun mentionsTopic(text: String, focus: Focus): Boolean {
        // Ecosystem/context labels are not evidence topics. An Android
        // documentation page about WorkManager need not repeat "Android" in
        // every factual paragraph. Preserve the former context exemption.
        val anchor = focus.topicAnchor?.takeUnless {
            it in setOf("android", "ios", "kotlin", "java", "python",
                "linux", "windows", "development", "technology")
        } ?: return true
        return includesToken(text.lowercase(Locale.ROOT), anchor)
    }

    /**
     * Contextual relevance when publishers name an official feature instead of
     * repeating the user's category word in every paragraph. Require the page
     * metadata to match the actual topic AND at least two distinctive terms to
     * occur in both the metadata and the observed factual paragraph. This is an
     * admission heuristic, not a truth or citation proof: the strict evidence
     * and quote audit remains authoritative downstream.
     */
    fun passageOnTopic(
        question: String,
        source: WorkspacePublicWebSearch.Verified,
        passage: String,
    ): Boolean {
        val focus = inspect(question)
        val anchor = focus.topicAnchor ?: return true
        if (includesToken(passage.lowercase(Locale.ROOT), anchor)) return true
        if (sourceScore(source, focus) <= 0) return false

        val generic = instructionWords + focus.topicWords +
            focus.subjects.flatMap(::aliases) +
            setOf("optimization", "optimized", "optimize", "overview",
                "article", "website", "official", "application", "applications",
                "page", "guide", "documentation", "information")
        val meta = tokens(source.title + " " + source.snippet).toSet()
        return tokens(passage).distinct().count { token ->
            token !in generic && token in meta && token.length >= 4
        } >= 2
    }

    /**
     * A source can be about battery while an unrelated paragraph discusses R8.
     * Extract-level topic checking prevents that paragraph from turning into
     * a battery claim. Generic platform contexts are allowed as page metadata.
     */
    fun relevantExtracts(
        question: String,
        extracts: List<WorkspacePublicResearch.Extract>,
    ): List<WorkspacePublicResearch.Extract> {
        val focus = inspect(question)
        return extracts.mapNotNull { extract ->
            if (sourceScore(extract.source, focus) <= 0) return@mapNotNull null
            val passages = extract.passages.filter {
                passageOnTopic(question, extract.source, it)
            }
            if (passages.isEmpty()) null else extract.copy(passages = passages)
        }
    }

    /** Topic overlap is mandatory for a source; platform/name overlap alone is not. */
    fun sourceScore(source: WorkspacePublicWebSearch.Verified, focus: Focus): Int {
        val text = textFor(source)
        if (!mentionsTopic(text, focus)) return 0
        val subjectHits = focus.subjects.count { mentionsSubject(text, it) }
        if (focus.subjects.size == 2 && subjectHits == 0) return 0
        val topicHits = focus.topicWords.count { includesToken(text, it) }
        return 3 + topicHits * 5 + subjectHits * 4
    }

    fun relevant(
        question: String, sources: List<WorkspacePublicWebSearch.Verified>,
    ): List<WorkspacePublicWebSearch.Verified> {
        val focus = inspect(question)
        return sources.distinctBy { it.finalUrl }
            .mapNotNull { source ->
                sourceScore(source, focus).takeIf { it > 0 }?.let { it to source }
            }
            .sortedByDescending { it.first }.map { it.second }
    }

    /** True only when sources cover both requested comparison subjects. */
    fun coversQuestion(
        question: String, sources: List<WorkspacePublicWebSearch.Verified>,
    ): Boolean {
        val focus = inspect(question)
        val valid = sources.filter { sourceScore(it, focus) > 0 }
        if (valid.isEmpty()) return false
        return focus.subjects.all { subject ->
            valid.any { mentionsSubject(textFor(it), subject) }
        }
    }
}
