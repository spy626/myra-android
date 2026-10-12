package com.myra.assistant.ui.workspace

import java.net.URI
import java.util.Locale

/**
 * A bounded research query planner over the existing public read-only search runner.
 * Plain link/download/GitHub lookups never enter this flow.
 */
internal object WorkspaceResearchQueryPlan {
    private const val MAX_QUERIES = 3
    private val background = setOf(
        "android", "kotlin", "python", "java", "javascript", "linux",
        "windows", "ios", "health", "climate", "science",
    )

    fun queries(
        question: String, original: WorkspaceWebLinkIntent.Request,
    ): List<WorkspaceWebLinkIntent.Request> {
        if (question.isBlank() || original.maxResults == 1 ||
            original.preferredHost != null) return listOf(original)
        val subjects = WorkspacePublicResearch.comparisonSubjects(question)
        if (subjects.size != 2) return listOf(original)
        val focus = WorkspaceResearchQuestionFocus.inspect(question)
        val context = focus.topicWords.take(3).joinToString(" ").ifBlank {
            Regex("""[\p{L}\p{N}]{3,}""").findAll(question.lowercase(Locale.ROOT))
                .map { it.value }.firstOrNull { it in background }.orEmpty()
        }
        return (listOf(original) + subjects.map { subject ->
            original.copy(query = (subject + " " + context).trim().take(180),
                maxResults = 2)
        }).distinctBy { it.query.lowercase(Locale.ROOT) }.take(MAX_QUERIES)
    }

    fun choose(
        question: String,
        original: WorkspaceWebLinkIntent.Request,
        results: List<WorkspacePublicWebSearch.Verified>,
    ): List<WorkspacePublicWebSearch.Verified> {
        val relevant = WorkspaceResearchQuestionFocus.relevant(question, results)
        val focus = WorkspaceResearchQuestionFocus.inspect(question)
        val ranked = WorkspacePublicWebSearch.selectResults(
            original.copy(maxResults = 3), relevant)
        if (focus.subjects.size != 2) return ranked

        // When both sides exist, reserve a slot for each before adding the top
        // remaining result. Never fill the shortlist with only one platform
        // merely because its publisher ranks higher.
        val covered = focus.subjects.mapNotNull { subject ->
            relevant.firstOrNull {
                WorkspaceResearchQuestionFocus.sourceMentionsSubject(it, subject)
            }
        }.distinctBy { it.finalUrl }
        return (covered + ranked + relevant).distinctBy { it.finalUrl }
            .take(original.maxResults)
    }
}
