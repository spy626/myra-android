package com.myra.assistant.ui.workspace

/**
 * Last-resort, read-only comparison presentation for a model that failed the
 * existing claim-level audit. This never promotes an unsupported generated
 * statement to a fact. It only quotes extracted page text from previously
 * verified public HTTPS destinations, and keeps the incomplete-result label.
 */
internal object WorkspaceResearchComparisonFallback {
    fun sideBySide(
        question: String,
        extracts: List<WorkspacePublicResearch.Extract>,
    ): String? {
        val subjects = WorkspaceResearchQuestionFocus.inspect(question).subjects
        if (subjects.size != 2) return null
        // Reuse the numerical-corroboration guard, then exact question relevance.
        val safe = WorkspaceResearchQuestionFocus.relevantExtracts(
            question, WorkspaceResearchEvidenceQuality.safeFallbackExtracts(extracts))
        if (WorkspaceResearchEvidenceQuality.requestsMultiplePublishers(question) &&
            WorkspaceResearchEvidenceQuality.publisherCount(safe) < 2) return null

        data class Finding(
            val subject: String,
            val passage: String,
            val source: WorkspacePublicWebSearch.Verified,
        )
        val findings = subjects.mapNotNull { subject ->
            safe.asSequence().filter { extract ->
                WorkspaceResearchQuestionFocus.sourceMentionsSubject(extract.source, subject)
            }.flatMap { extract ->
                extract.passages.asSequence().filter { passage ->
                    // A title or search snippet naming the platform does not
                    // authorize us to label unrelated body text as its evidence.
                    WorkspaceResearchQuestionFocus.mentionsSubject(passage, subject) &&
                        subjects.filterNot { it == subject }.none { other ->
                            WorkspaceResearchQuestionFocus.mentionsSubject(passage, other)
                        }
                }.map { passage -> Finding(subject, passage, extract.source) }
            }.firstOrNull()
        }
        if (findings.size != 2 ||
            findings[0].source.finalUrl == findings[1].source.finalUrl) return null

        return buildString {
            appendLine("**Side-by-side excerpts from readable sources**")
            appendLine()
            appendLine("The AI-written comparison did not pass all evidence checks. " +
                "These are source excerpts, not independently verified conclusions.")
            findings.forEach { finding ->
                val quote = finding.passage
                    .replace(Regex("""[\r\n\t]+"""), " ")
                    .replace(Regex("""[\[\]*<>{}]"""), "")
                    .replace(Regex("""\s{2,}"""), " ")
                    .trim()
                    .take(280)
                if (quote.isNotBlank()) {
                    appendLine()
                    appendLine("**" + finding.subject + "**")
                    appendLine("> " + quote)
                    appendLine("[Open source](" + finding.source.finalUrl + ")")
                }
            }
        }.trim()
    }
}
