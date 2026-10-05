package com.myra.assistant.agent

import java.net.URI
import java.util.Locale

/**
 * Ephemeral working-task evidence for comparing a user-selected first public source with
 * one independently selected second public source. Nothing here is long-term memory,
 * navigation authority, provider context or a factual truth engine.
 */
internal object BrowserResearchComparison {
    const val MAX_AGE_MS = 10L * 60_000L

    enum class Decision {
        TWO_INDEPENDENT_SOURCES_VERIFIED,
        MORE_RELEVANT_SOURCE_EVIDENCE_NEEDED,
    }

    enum class ClaimRelation {
        EXACT_SAFE_STATEMENT_MATCH,
        CRITICAL_LITERAL_CONFLICT,
        NO_CLAIM_ALIGNMENT,
    }

    data class ClaimAssessment(
        val relation: ClaimRelation,
        val firstExcerpt: String? = null,
        val secondExcerpt: String? = null,
        val firstLiterals: List<String> = emptyList(),
        val secondLiterals: List<String> = emptyList(),
    )

    data class SourceEvidence(
        val finalUrl: String,
        val host: String,
        val contentSha256: String,
        val excerpts: List<String>,
        val matchedTerms: Set<String>,
        val capturedAt: Long,
    )

    data class Session(
        val taskId: String,
        val query: String,
        val first: SourceEvidence,
        val createdAt: Long,
    ) {
        val claimKey: String get() = "$taskId:${first.contentSha256}:$createdAt"
    }

    data class Result(
        val taskId: String,
        val query: String,
        val first: SourceEvidence,
        val second: SourceEvidence,
        val sharedTerms: List<String>,
        val firstOnlyTerms: List<String>,
        val secondOnlyTerms: List<String>,
        val decision: Decision,
        val claimAssessment: ClaimAssessment,
    )

    private val safeHash = Regex("""[0-9a-f]{64}""")
    private val safeTerm = Regex("""[\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N}_-]{1,39}""")
    private val sensitive = Regex(
        """(?iu)\b(?:otp|password|passphrase|passcode|pin|cvv|api[ -]?key|""" +
            """access[ -]?token|private[ -]?key|secret|authorization|cookie|session)\b"""
    )
    private val criticalLiteral = Regex(
        """(?iu)(?:\b\d{4}-\d{1,2}-\d{1,2}\b|\b\d{1,2}/\d{1,2}/\d{2,4}\b|""" +
            """\bv?\d+(?:\.\d+){1,3}\b|\b\d+(?:\.\d+)?%\b|""" +
            """(?:[$€£₹])\s*\d+(?:[.,]\d+)*|\b\d+(?:[.,]\d+)*\b)"""
    )

    fun source(
        finalUrl: String,
        host: String,
        contentSha256: String,
        excerpts: List<String>,
        matchedTerms: Collection<String>,
        capturedAt: Long,
    ): SourceEvidence? {
        val uri = runCatching { URI(finalUrl) }.getOrNull() ?: return null
        val normalizedHost = host.trim().lowercase(Locale.ROOT)
        val terms = matchedTerms.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { safeTerm.matches(it) && !sensitive.containsMatchIn(it) }
            .distinct().take(10).toSet()
        val safeExcerpts = excerpts.asSequence()
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter {
                it.length in 36..200 && !it.any(Char::isISOControl) &&
                    !sensitive.containsMatchIn(it)
            }
            .distinct().take(3).toList()
        if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) != normalizedHost ||
            normalizedHost.isBlank() || finalUrl.length > 2_048 ||
            !safeHash.matches(contentSha256.lowercase(Locale.ROOT)) ||
            capturedAt <= 0L || safeExcerpts.isEmpty() || terms.isEmpty()
        ) return null
        return SourceEvidence(
            finalUrl = finalUrl,
            host = normalizedHost,
            contentSha256 = contentSha256.lowercase(Locale.ROOT),
            excerpts = safeExcerpts,
            matchedTerms = terms,
            capturedAt = capturedAt,
        )
    }

    fun start(
        handoff: BrowserResearchSourceHandoff.Pending,
        first: SourceEvidence,
        nowMs: Long,
    ): Session? {
        if (nowMs < first.capturedAt || nowMs - first.capturedAt > MAX_AGE_MS ||
            handoff.taskId.isBlank() || handoff.query.isBlank()
        ) return null
        return Session(handoff.taskId, handoff.query, first, nowMs)
    }

    fun fresh(session: Session, nowMs: Long): Session? {
        if (session.createdAt <= 0L || session.createdAt > nowMs ||
            nowMs - session.createdAt > MAX_AGE_MS
        ) return null
        return session
    }

    private fun normalizeStatement(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{M}\p{N}%$€£₹.]+"""), " ")
            .trim().replace(Regex("""\s+"""), " ")

    private fun literals(text: String): List<String> =
        criticalLiteral.findAll(text).map { it.value.lowercase(Locale.ROOT) }
            .distinct().take(8).toList()

    private fun literalSkeleton(text: String): String =
        normalizeStatement(text).replace(criticalLiteral, "{#}")
            .replace(Regex("""\s+"""), " ").trim()

    private fun assessClaims(
        first: SourceEvidence,
        second: SourceEvidence,
    ): ClaimAssessment {
        for (a in first.excerpts) {
            val normalizedA = normalizeStatement(a)
            for (b in second.excerpts) {
                val normalizedB = normalizeStatement(b)
                if (normalizedA.length >= 28 && normalizedA == normalizedB) {
                    return ClaimAssessment(
                        relation = ClaimRelation.EXACT_SAFE_STATEMENT_MATCH,
                        firstExcerpt = a,
                        secondExcerpt = b,
                    )
                }
            }
        }
        for (a in first.excerpts) {
            val firstValues = literals(a)
            if (firstValues.isEmpty()) continue
            val skeletonA = literalSkeleton(a)
            if (skeletonA.length < 28) continue
            for (b in second.excerpts) {
                val secondValues = literals(b)
                if (secondValues.isEmpty()) continue
                val skeletonB = literalSkeleton(b)
                if (skeletonA == skeletonB && firstValues != secondValues) {
                    return ClaimAssessment(
                        relation = ClaimRelation.CRITICAL_LITERAL_CONFLICT,
                        firstExcerpt = a,
                        secondExcerpt = b,
                        firstLiterals = firstValues,
                        secondLiterals = secondValues,
                    )
                }
            }
        }
        return ClaimAssessment(ClaimRelation.NO_CLAIM_ALIGNMENT)
    }

    fun compare(
        session: Session,
        second: SourceEvidence,
        nowMs: Long,
    ): Result? {
        if (fresh(session, nowMs) == null || second.capturedAt > nowMs ||
            second.host == session.first.host ||
            second.finalUrl == session.first.finalUrl ||
            second.contentSha256 == session.first.contentSha256
        ) return null
        val shared = (session.first.matchedTerms intersect second.matchedTerms)
            .sorted().take(10)
        val firstOnly = (session.first.matchedTerms - second.matchedTerms)
            .sorted().take(10)
        val secondOnly = (second.matchedTerms - session.first.matchedTerms)
            .sorted().take(10)
        return Result(
            taskId = session.taskId,
            query = session.query,
            first = session.first,
            second = second,
            sharedTerms = shared,
            firstOnlyTerms = firstOnly,
            secondOnlyTerms = secondOnly,
            decision = Decision.TWO_INDEPENDENT_SOURCES_VERIFIED,
            claimAssessment = assessClaims(session.first, second),
        )
    }
}
