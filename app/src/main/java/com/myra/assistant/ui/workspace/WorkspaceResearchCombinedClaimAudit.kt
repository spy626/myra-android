package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Narrow structural grounding contract for claims which join two comparison
 * subjects. This is NOT semantic entailment or an independent truth verifier.
 *
 * Source title/snippet alone must never authorize a combined relationship.
 * Each subject needs actual observed passage support from a different URL;
 * shared-mechanism assertions must be supported on both sides separately.
 */
internal object WorkspaceResearchCombinedClaimAudit {
    private val word = Regex("""[\p{L}\p{N}]{4,}""")
    private val sharedAssertion = Regex(
        """(?iu)\b(?:both|each|similarly|same|equally|identical)\b"""
    )
    private val generic = setOf(
        "android", "iphone", "ipad", "apple", "ios", "both", "each",
        "similar", "similarly", "same", "equally", "identical", "the",
        "these", "those", "their", "with", "while", "whereas", "than",
        "that", "this", "which", "also", "and", "but", "for", "from",
        "have", "has", "they", "are", "being", "would", "could",
        "battery", "batteries", "optimization", "optimized", "optimize",
    )

    fun supported(
        question: String,
        statement: String,
        passages: List<WorkspaceResearchSynthesis.Evidence>,
    ): Boolean {
        val subjects = WorkspaceResearchQuestionFocus.inspect(question).subjects
        if (subjects.size != 2 ||
            subjects.any { !WorkspaceResearchQuestionFocus.mentionsSubject(statement, it) }) {
            return true
        }

        val sides = subjects.map { subject ->
            passages.filter {
                // The subject MUST be in observed page text, not merely
                // on the search result title or URL.
                WorkspaceResearchQuestionFocus.mentionsSubject(it.passage, subject)
            }
        }
        if (sides.any { it.isEmpty() }) return false
        if (sides[0].none { first ->
                sides[1].any { second ->
                    first.source.finalUrl != second.source.finalUrl
                }
            }) return false

        // Shared claims are especially prone to falsely combining two
        // mechanisms. Every significant word of the shared predicate must
        // occur in separately cited observations for both subjects.
        val shared = sharedAssertion.find(statement) ?: return true
        val suffix = statement.substring(shared.range.last + 1)
        val predicate = word.findAll(suffix.lowercase(Locale.ROOT))
            .map { it.value }.filterNot { it in generic }.distinct().toList()
        if (predicate.isEmpty()) return false
        return sides.all { side ->
            side.any { evidence ->
                val observed = word.findAll(evidence.passage.lowercase(Locale.ROOT))
                    .map { it.value }.toSet()
                predicate.all { it in observed }
            }
        }
    }
}
