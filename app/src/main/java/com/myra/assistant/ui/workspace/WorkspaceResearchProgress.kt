package com.myra.assistant.ui.workspace

import java.net.URI
import java.util.Locale

/**
 * Public narration of *observed* web research milestones.
 * Search candidates, model thoughts and unchecked snippets never become findings.
 * All URLs here already passed the existing public HTTPS verification path.
 */
internal object WorkspaceResearchProgress {
    internal data class Update(val key: String, val status: String, val message: String)

    fun kickoff(): Update = Update(
        key = "research-plan",
        status = "Thinking",
        message = "I'll look for relevant public sources, read the available webpages " +
            "and compare what their evidence actually supports.",
    )

    fun searchRound(
        index: Int,
        sources: List<WorkspacePublicWebSearch.Verified>,
    ): Update? {
        require(index in 0..12)
        // Empty result sets are not announced as successful searches.
        val unique = sources.distinctBy { it.finalUrl }
        if (unique.isEmpty()) return null
        val domains = unique.mapNotNull { source ->
            runCatching {
                val parsed = WorkspaceAgentReachPolicy.parse(source.finalUrl)
                parsed.host.lowercase(Locale.ROOT).removePrefix("www.")
            }.getOrNull()
        }.distinct().take(5)
        if (domains.isEmpty()) return null
        val count = unique.size
        return Update(
            key = "research-round-$index",
            status = "Found $count reachable " +
                if (count == 1) "website" else "websites",
            message = domains.joinToString(" · ") +
                ". Checking which pages provide usable evidence.",
        )
    }

    fun readEvidence(
        verifiedPages: Int,
        relevantPages: Int,
    ): Update? {
        if (verifiedPages <= 0) return null
        require(relevantPages in 0..verifiedPages)
        val status = if (relevantPages > 0) {
            "Read $relevantPages evidence-bearing " +
                if (relevantPages == 1) "page" else "pages"
        } else "Reading sources completed"
        val message = if (relevantPages > 0) {
            "I found relevant passages on $relevantPages of $verifiedPages checked " +
                "pages. I'll check the remaining gaps before writing the answer."
        } else {
            "The checked pages did not provide suitable evidence yet. " +
                "I'll check whether another source search is needed."
        }
        return Update("research-pages", status, message)
    }
}
