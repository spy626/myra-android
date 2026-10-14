package com.myra.assistant.ui.workspace

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
        val pages = unique.size
        val websiteCount = domains.size
        return Update(
            key = "research-round-$index",
            // Page URLs and distinct publisher hosts are not the same count.
            status = "Found $pages reachable " +
                if (pages == 1) "page" else "pages",
            message = "From $websiteCount " +
                (if (websiteCount == 1) "website: " else "websites: ") +
                domains.joinToString(" · ") +
                ". Checking which pages provide usable evidence.",
        )
    }

    /**
     * Reconstruct chips from the bounded, source-derived public update only.
     * Reject arbitrary narration text; never turn unchecked model text into
     * clickable sources or reported search activity.
     */
    fun domainChips(update: WorkspacePublicWorkMessage): List<String> {
        if (!Regex("""research-round-\d{1,2}""").matches(update.key)) return emptyList()
        if (!Regex("""Found [1-9]\d* reachable pages?""").matches(update.statusLabel)) {
            return emptyList()
        }
        val marker = ". Checking which pages provide usable evidence."
        if (!update.text.endsWith(marker)) return emptyList()
        val body = update.text.removeSuffix(marker)
        val match = Regex("""From ([1-5]) websites?: (.+)""").matchEntire(body)
            ?: return emptyList()
        val hosts = match.groupValues[2].split(" · ")
        if (hosts.size != match.groupValues[1].toInt()) return emptyList()
        if (hosts.size !in 1..5) return emptyList()
        val pattern = Regex("""[a-z0-9](?:[a-z0-9.-]{0,120}[a-z0-9])?""")
        return hosts.takeIf { list ->
            list.all { host ->
                host.contains('.') && pattern.matches(host) && !host.contains("..")
            }
        }.orEmpty()
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
