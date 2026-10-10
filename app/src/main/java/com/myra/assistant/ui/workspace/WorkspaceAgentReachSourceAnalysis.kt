package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Local, bounded extractive analysis of ONLY verified public static page evidence.
 * User goal selects relevance; page text never becomes a plan, instruction, authorization,
 * learned executable policy or automatically shared model prompt.
 */
internal object WorkspaceAgentReachSourceAnalysis {
    enum class PageRole { PRIMARY, VERIFIED_FOLLOW_UP }
    enum class Coverage { TOPIC_EVIDENCE_FOUND, NO_GROUNDED_TOPIC_EXCERPTS }
    enum class PreferredEvidence { PRIMARY, VERIFIED_FOLLOW_UP, NONE }

    data class Finding(
        val role: PageRole,
        val finalUrl: String,
        val contentSha256: String,
        val excerpt: String,
        val matchedTerms: List<String>,
    )

    /** Bounded per-goal outcome feedback, not a new memory system or permission grant. */
    data class Feedback(
        val primaryMatches: Int,
        val verifiedFollowUpMatches: Int,
        val preferredEvidence: PreferredEvidence,
    )

    data class Report(
        val coverage: Coverage,
        val findings: List<Finding>,
        val feedback: Feedback,
        val verifiedPageCount: Int,
    )

    private val ignore = setOf(
        "https", "http", "check", "read", "about", "site", "page", "website", "review",
        "analyse", "analyze", "analysis", "study", "inspect", "research", "explore",
        "summarize", "summary", "explain", "please", "this", "that", "with", "from",
        "entire", "whole", "link", "public", "content", "source", "bro", "dekho",
        "mujhe", "batao", "karo", "karna", "kro", "find", "information", "details"
    )
    private val unsafeExcerpt = Regex(
        """(?iu)(?:https?://|www\.|[\w.%+-]+@[\w.-]+\.[a-z]{2,}|(?:password|passcode|otp|token|secret|api.key|session|authorization)\s*[:=]|ignore (?:all |the )?(?:previous |prior )?instructions|system prompt|you are (?:now )?(?:the )?(?:assistant|system)|(?:execute|run) (?:this )?(?:command|script)|(?:send|exfiltrate) (?:your |the )?(?:credentials|cookies|passwords))"""
    )
    private fun words(text: String): Set<String> =
        Regex("""[\p{L}\p{M}\p{N}]{4,}""").findAll(text.lowercase(Locale.ROOT))
            .map { it.value }.toSet()

    private fun topic(userRequest: String): List<String> =
        words(userRequest.replace(Regex("""(?i)https://[^\s<>"']+"""), " "))
            .filterNot { it in ignore }.take(10)

    private data class Candidate(val finding: Finding, val strength: Int)

    private fun candidates(
        role: PageRole,
        page: WorkspaceAgentReachPublicWeb.Page,
        terms: List<String>,
    ): List<Candidate> {
        val p = page.evidence.provenance
        if (p.contentSha256.length != 64 || p.finalUrl.isBlank() ||
            p.adapter != "public-html-static" || p.platform == WorkspaceAgentReachPolicy.Platform.GITHUB
        ) return emptyList()
        return page.excerpt
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .take(90)
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length in 36..220 && it.count(Char::isLetter) >= 28 &&
                !unsafeExcerpt.containsMatchIn(it) &&
                !WorkspaceSourceContext.containsPossibleSecret(it) }
            .distinct()
            .mapNotNull { line ->
                val observed = words(line)
                val matched = terms.filter { it in observed }
                if (matched.isEmpty()) null else Candidate(
                    Finding(role, p.finalUrl, p.contentSha256, line.take(200), matched),
                    matched.size,
                )
            }.sortedWith(compareByDescending<Candidate> { it.strength }
                .thenByDescending { it.finding.excerpt.length })
            .take(3)
    }

    fun analyze(
        userRequest: String,
        journey: WorkspaceAgentReachWebNavigation.Journey,
    ): Report {
        val terms = topic(userRequest)
        // A page passed as 'followed' is not trusted just because a caller supplied it.
        // Revalidate the observed link and requested/final same-site provenance.
        val secondary = if (journey.selected != null && journey.followed != null &&
            runCatching {
                WorkspaceAgentReachWebNavigation.verify(
                    journey.primary, journey.selected, journey.followed)
            }.isSuccess
        ) journey.followed else null
        val primary = if (terms.isEmpty()) emptyList() else
            candidates(PageRole.PRIMARY, journey.primary, terms)
        val followed = if (terms.isEmpty() || secondary == null) emptyList() else
            candidates(PageRole.VERIFIED_FOLLOW_UP, secondary, terms)
        val primaryWeight = primary.sumOf { it.strength }
        val followWeight = followed.sumOf { it.strength }
        val preferred = when {
            primaryWeight == 0 && followWeight == 0 -> PreferredEvidence.NONE
            followWeight > primaryWeight -> PreferredEvidence.VERIFIED_FOLLOW_UP
            else -> PreferredEvidence.PRIMARY
        }
        // This feedback changes ONLY local evidence ordering for the current request.
        // It never selects another URL, triggers another network call or writes memory.
        val findings = (primary + followed)
            .sortedWith(compareByDescending<Candidate> { it.strength }
                .thenByDescending {
                    if (it.finding.role == PageRole.VERIFIED_FOLLOW_UP &&
                        preferred == PreferredEvidence.VERIFIED_FOLLOW_UP) 1 else 0
                }.thenBy { it.finding.finalUrl })
            .distinctBy { it.finding.excerpt.lowercase(Locale.ROOT) }
            .take(3).map { it.finding }
        return Report(
            coverage = if (findings.isEmpty()) Coverage.NO_GROUNDED_TOPIC_EXCERPTS
                else Coverage.TOPIC_EVIDENCE_FOUND,
            findings = findings,
            feedback = Feedback(primary.size, followed.size, preferred),
            verifiedPageCount = if (secondary != null) 2 else 1,
        )
    }
}
