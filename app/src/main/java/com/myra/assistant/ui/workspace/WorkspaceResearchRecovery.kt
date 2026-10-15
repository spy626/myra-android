package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Bounded, question-aware SECOND search round, only after verified public pages
 * were read and the surviving evidence exposes gaps. No additional model, new
 * provider, parallel coordinator, permissions, private context or tool writes.
 *
 * This is source-discovery planning, not intent classification; the model has
 * already decided the user wanted public research.
 */
internal object WorkspaceResearchRecovery {
    const val MAX_EXTRA_SEARCHES = 4

    private fun relevant(
        question: String, extracts: List<WorkspacePublicResearch.Extract>,
    ): List<WorkspacePublicResearch.Extract> =
        WorkspaceResearchQuestionFocus.relevantExtracts(question, extracts)

    private fun hasPrimaryEvidence(
        subject: String, context: String,
        readable: List<WorkspacePublicResearch.Extract>,
    ): Boolean {
        val preferred = WorkspacePublicWebSearch.officialDocumentationHost(
            (subject + " " + context).trim()) ?: return true
        val publisher = WorkspaceResearchEvidenceQuality.publisher("https://" + preferred)
        return readable.any { extract ->
            WorkspaceResearchQuestionFocus.sourceMentionsSubject(extract.source, subject) &&
                WorkspaceResearchEvidenceQuality.publisher(
                    extract.source.finalUrl) == publisher
        }
    }

    /** Both subjects, independent publishers when requested, and primary docs if reachable. */
    fun needsRecovery(
        question: String, extracts: List<WorkspacePublicResearch.Extract>,
    ): Boolean {
        val readable = relevant(question, extracts)
        if (!WorkspaceResearchQuestionFocus.coversQuestion(
                question, readable.map { it.source })) return true
        if (WorkspaceResearchEvidenceQuality.requestsMultiplePublishers(question) &&
            WorkspaceResearchEvidenceQuality.publisherCount(readable) < 2) return true
        val focus = WorkspaceResearchQuestionFocus.inspect(question)
        val context = focus.topicWords.take(3).joinToString(" ")
        return focus.subjects.any { subject ->
            !hasPrimaryEvidence(subject, context, readable)
        }
    }

    /** Propose an untried, public-only search for the strongest evidence gap. */
    fun next(
        question: String,
        original: WorkspaceWebLinkIntent.Request,
        extracts: List<WorkspacePublicResearch.Extract>,
        tried: List<WorkspaceWebLinkIntent.Request>,
    ): WorkspaceWebLinkIntent.Request? {
        if (original.maxResults == 1 || original.preferredHost != null ||
            !needsRecovery(question, extracts)) return null
        val focus = WorkspaceResearchQuestionFocus.inspect(question)
        val readable = relevant(question, extracts)
        val context = focus.topicWords.take(3).joinToString(" ")
            .ifBlank { original.query.take(110) }
        val missing = focus.subjects.filter { subject ->
            readable.none {
                WorkspaceResearchQuestionFocus.sourceMentionsSubject(it.source, subject)
            }
        }
        val missingPrimary = focus.subjects.filter { subject ->
            !hasPrimaryEvidence(subject, context, readable)
        }
        val priority = (missing + missingPrimary + focus.subjects).distinct()
        val candidates = buildList {
            priority.forEach { subject ->
                val subjectQuery = (subject + " " + context).trim()
                val official = WorkspacePublicWebSearch.officialDocumentationHost(subjectQuery)
                if (official != null) {
                    add(WorkspaceWebLinkIntent.Request(
                        query = (subjectQuery + " official guidance").take(180),
                        preferredHost = official, maxResults = 3))
                }
            }
            priority.forEach { subject ->
                // If an official page is blocked or lacks readable paragraphs,
                // try more than one site instead of forcing a vendor domain.
                add(WorkspaceWebLinkIntent.Request(
                    query = (subject + " " + context + " support guide background").take(180),
                    maxResults = 3))
            }
            add(WorkspaceWebLinkIntent.Request(
                query = (original.query + " independent evidence explained").take(180),
                maxResults = 3))
        }
        fun key(it: WorkspaceWebLinkIntent.Request) =
            it.query.trim().lowercase(Locale.ROOT) + "|" +
                it.preferredHost.orEmpty().lowercase(Locale.ROOT)
        val previously = tried.map(::key).toSet()
        return candidates.distinctBy(::key).firstOrNull { key(it) !in previously }
    }
}
