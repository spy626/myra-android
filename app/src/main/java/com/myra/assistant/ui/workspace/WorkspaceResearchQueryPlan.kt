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
        val context = Regex("""[\p{L}\p{N}]{3,}""")
            .findAll(question.lowercase(Locale.ROOT))
            .map { it.value }.firstOrNull { it in background }.orEmpty()
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
        val ranked = WorkspacePublicWebSearch.selectResults(
            original.copy(maxResults = 3), results.distinctBy { it.finalUrl })
        val subjects = WorkspacePublicResearch.comparisonSubjects(question)
        if (subjects.size != 2) return ranked
        val all = results.distinctBy { it.finalUrl }
        fun topicHit(topic: String): WorkspacePublicWebSearch.Verified? {
            val tokens = topic.lowercase(Locale.ROOT).split(" ").filter { it.length > 2 }
            return all.firstOrNull { hit ->
                val uri = runCatching { URI(hit.finalUrl) }.getOrNull()
                val haystack = (hit.title + " " + uri?.path.orEmpty() + " " +
                    hit.snippet).lowercase(Locale.ROOT)
                tokens.all { it in haystack }
            }
        }
        val covered = subjects.mapNotNull(::topicHit).distinctBy { it.finalUrl }
        return (covered + ranked + all).distinctBy { it.finalUrl }
            .take(original.maxResults)
    }
}
