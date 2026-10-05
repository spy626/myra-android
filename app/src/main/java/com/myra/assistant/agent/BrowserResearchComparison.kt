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
        TWO_DIFFERENT_PUBLIC_HOSTS_VERIFIED,
        MORE_RELEVANT_SOURCE_EVIDENCE_NEEDED,
    }

    enum class ClaimRelation {
        EXACT_SAFE_STATEMENT_MATCH,
        STRUCTURED_LITERAL_ANCHOR_SUPPORT,
        STRUCTURED_CLAIM_CONFLICT,
        CRITICAL_LITERAL_CONFLICT,
        NO_CLAIM_ALIGNMENT,
    }

    data class ClaimAssessment(
        val relation: ClaimRelation,
        val firstExcerpt: String? = null,
        val secondExcerpt: String? = null,
        val firstLiterals: List<String> = emptyList(),
        val secondLiterals: List<String> = emptyList(),
        val sharedAnchors: List<String> = emptyList(),
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
            """\b\d+(?:\.\d+)?\s*(?:bytes?|kb|mb|gb|tb|ms|sec(?:ond)?s?|mins?|minutes?|""" +
            """hours?|days?|weeks?|months?|years?)\b|\bv?\d+(?:\.\d+){1,3}\b|""" +
            """\b\d+(?:\.\d+)?%\b|(?:[$€£₹])\s*\d+(?:[.,]\d+)*(?:\s*[kmbt])?\b|""" +
            """\bq[1-4]\b|""" +
            """\b(?:january|february|march|april|june|july|august|september|october|november|december)\b|""" +
            """\b(?:19|20)\d{2}\b|\b\d+(?:[.,]\d+)*\b)"""
    )
    private val dateLiteral = Regex("""(?iu)^(?:\d{4}-\d{1,2}-\d{1,2}|\d{1,2}/\d{1,2}/\d{2,4})$""")
    private val versionLiteral = Regex("""(?iu)^v?\d+(?:\.\d+){1,3}$""")
    private val percentLiteral = Regex("""(?iu)^\d+(?:\.\d+)?%$""")
    private val moneyLiteral = Regex("""(?iu)^(?:[$€£₹])\s*\d+(?:[.,]\d+)*(?:\s*[kmbt])?$""")
    private val quantityLiteral = Regex(
        """(?iu)^\d+(?:\.\d+)?\s*(?:bytes?|kb|mb|gb|tb|ms|sec(?:ond)?s?|mins?|minutes?|""" +
            """hours?|days?|weeks?|months?|years?)$"""
    )
    private val quarterLiteral = Regex("""(?iu)^q[1-4]$""")
    private val monthLiteral = Regex(
        """(?iu)^(?:january|february|march|april|june|july|august|september|october|november|december)$"""
    )
    private val yearLiteral = Regex("""^(?:19|20)\d{2}$""")
    private val genericAnchor = setOf(
        "about", "after", "also", "before", "being", "could", "during", "from",
        "have", "into", "more", "most", "only", "other", "over", "than", "that",
        "their", "there", "these", "they", "this", "those", "under", "were", "what",
        "when", "where", "which", "while", "with", "would",
    )
    private val polarityMarker = setOf(
        "no", "not", "never", "without", "cannot", "can't", "cant", "didn't", "didnt",
        "doesn't", "doesnt", "isn't", "isnt", "wasn't", "wasnt", "weren't", "werent",
        "won't", "wont", "hasn't", "hasnt", "haven't", "havent", "aren't", "arent",
        "ain't", "aint", "nahi", "nahin", "mat",
    )
    private val actionFamily = mapOf(
        "add" to "ADD", "adds" to "ADD", "added" to "ADD", "adding" to "ADD",
        "introduce" to "ADD", "introduces" to "ADD", "introduced" to "ADD",
        "remove" to "REMOVE", "removes" to "REMOVE", "removed" to "REMOVE",
        "increase" to "INCREASE", "increases" to "INCREASE", "increased" to "INCREASE",
        "raise" to "INCREASE", "raises" to "INCREASE", "raised" to "INCREASE",
        "decrease" to "DECREASE", "decreases" to "DECREASE", "decreased" to "DECREASE",
        "reduce" to "DECREASE", "reduces" to "DECREASE", "reduced" to "DECREASE",
        "enable" to "ENABLE", "enables" to "ENABLE", "enabled" to "ENABLE",
        "disable" to "DISABLE", "disables" to "DISABLE", "disabled" to "DISABLE",
        "allow" to "ALLOW", "allows" to "ALLOW", "allowed" to "ALLOW",
        "deny" to "DENY", "denies" to "DENY", "denied" to "DENY",
        "support" to "SUPPORT", "supports" to "SUPPORT", "supported" to "SUPPORT",
        "block" to "BLOCK", "blocks" to "BLOCK", "blocked" to "BLOCK",
        "start" to "START", "starts" to "START", "started" to "START",
        "stop" to "STOP", "stops" to "STOP", "stopped" to "STOP",
        "pass" to "PASS", "passes" to "PASS", "passed" to "PASS",
        "fail" to "FAIL", "fails" to "FAIL", "failed" to "FAIL",
        "open" to "OPEN", "opens" to "OPEN", "opened" to "OPEN",
        "close" to "CLOSE", "closes" to "CLOSE", "closed" to "CLOSE",
        "ship" to "RELEASE", "ships" to "RELEASE", "shipped" to "RELEASE",
        "release" to "RELEASE", "releases" to "RELEASE", "released" to "RELEASE",
        "launch" to "RELEASE", "launches" to "RELEASE", "launched" to "RELEASE",
        "receive" to "RELEASE", "receives" to "RELEASE", "received" to "RELEASE",
        "available" to "RELEASE", "unavailable" to "UNAVAILABLE",
    )
    private val oppositeActionPairs = setOf(
        setOf("ADD", "REMOVE"), setOf("INCREASE", "DECREASE"),
        setOf("ENABLE", "DISABLE"), setOf("ALLOW", "DENY"),
        setOf("SUPPORT", "BLOCK"), setOf("START", "STOP"),
        setOf("PASS", "FAIL"), setOf("OPEN", "CLOSE"),
        setOf("RELEASE", "UNAVAILABLE"),
    )
    private val measurementConcept = mapOf(
        "revenue" to "REVENUE", "profit" to "PROFIT", "income" to "INCOME",
        "loss" to "LOSS", "sales" to "SALES", "price" to "PRICE", "cost" to "COST",
        "margin" to "MARGIN", "users" to "USERS", "downloads" to "DOWNLOADS",
        "installs" to "INSTALLS", "shipments" to "SHIPMENTS", "units" to "UNITS",
        "speed" to "SPEED", "size" to "SIZE", "weight" to "WEIGHT",
        "duration" to "DURATION", "rate" to "RATE",
    )
    private val properEntityIgnore = setOf(
        "security", "update", "version", "supported", "public", "source", "feature",
        "release", "report", "bulletin", "devices", "device",
    )

    private data class CriticalFact(val kind: String, val value: String)

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

    private fun criticalFacts(text: String): List<CriticalFact> =
        criticalLiteral.findAll(text).map { match ->
            val value = match.value.lowercase(Locale.ROOT)
                .replace(Regex("""\s+"""), " ").trim()
            val kind = when {
                dateLiteral.matches(value) -> "DATE"
                versionLiteral.matches(value) -> "VERSION"
                percentLiteral.matches(value) -> "PERCENT"
                moneyLiteral.matches(value) -> "MONEY"
                quantityLiteral.matches(value) -> "QUANTITY"
                quarterLiteral.matches(value) -> "QUARTER"
                monthLiteral.matches(value) -> "MONTH"
                yearLiteral.matches(value) -> "YEAR"
                else -> "NUMBER"
            }
            CriticalFact(kind, value)
        }.distinct().take(8).toList()

    private fun literals(text: String): List<String> =
        criticalFacts(text).map { it.value }

    private fun sameCriticalFacts(a: String, b: String): Boolean {
        val first = criticalFacts(a)
        val second = criticalFacts(b)
        return first.isNotEmpty() &&
            first.map { it.kind + ":" + it.value }.toSet() ==
                second.map { it.kind + ":" + it.value }.toSet()
    }

    private fun sameCriticalProfileWithDifferentValues(a: String, b: String): Boolean {
        val first = criticalFacts(a)
        val second = criticalFacts(b)
        if (first.isEmpty() || second.isEmpty()) return false
        return first.map { it.kind }.toSet() == second.map { it.kind }.toSet() &&
            first.map { it.kind + ":" + it.value }.toSet() !=
                second.map { it.kind + ":" + it.value }.toSet()
    }

    private fun literalSkeleton(text: String): String =
        normalizeStatement(text).replace(criticalLiteral, "{#}")
            .replace(Regex("""\s+"""), " ").trim()

    private fun lexicalAnchors(text: String): Set<String> =
        Regex("""[\p{L}\p{M}][\p{L}\p{M}\p{N}_-]{3,39}""")
            .findAll(criticalLiteral.replace(text.lowercase(Locale.ROOT), " "))
            .map { it.value }
            .filter {
                it !in genericAnchor && it !in polarityMarker &&
                    !sensitive.containsMatchIn(it)
            }
            .toSet()

    private fun hasNegativePolarity(text: String): Boolean =
        Regex("""[\p{L}']+""").findAll(text.lowercase(Locale.ROOT))
            .map { it.value }.any { it in polarityMarker }

    private fun actionFamilies(text: String): Set<String> =
        Regex("""[\p{L}']+""").findAll(text.lowercase(Locale.ROOT))
            .mapNotNull { actionFamily[it.value] }.toSet()

    private fun opposingActionConflict(a: String, b: String): Boolean {
        val first = actionFamilies(a)
        val second = actionFamilies(b)
        return first.any { left ->
            second.any { right -> setOf(left, right) in oppositeActionPairs }
        }
    }

    private fun actionsCompatible(a: String, b: String): Boolean {
        val first = actionFamilies(a)
        val second = actionFamilies(b)
        if (opposingActionConflict(a, b)) return false
        if (first.isEmpty() && second.isEmpty()) return true
        if (first.isEmpty() || second.isEmpty()) return false
        return first == second
    }

    private fun measurementConcepts(text: String): Set<String> =
        Regex("""[\p{L}]+""").findAll(text.lowercase(Locale.ROOT))
            .mapNotNull { measurementConcept[it.value] }.toSet()

    private fun conceptsCompatible(a: String, b: String): Boolean {
        val first = measurementConcepts(a)
        val second = measurementConcepts(b)
        if (first.isEmpty() && second.isEmpty()) return true
        return first == second
    }

    private fun namedEntityMarkers(text: String): Set<String> {
        val withoutCritical = criticalLiteral.replace(text, " ")
        val body = withoutCritical.trim().substringAfter(' ', "")
        if (body.isBlank()) return emptySet()
        return Regex("""\b[\p{Lu}][\p{L}\p{M}\p{N}_-]{2,39}\b""")
            .findAll(body)
            .map { it.value.lowercase(Locale.ROOT) }
            .filter { it !in properEntityIgnore }
            .toSet()
    }

    private fun entitiesCompatible(a: String, b: String): Boolean {
        val first = namedEntityMarkers(a)
        val second = namedEntityMarkers(b)
        if (first.isEmpty() && second.isEmpty()) return true
        return first == second
    }

    fun supportsBoundedClaim(relation: ClaimRelation): Boolean =
        relation == ClaimRelation.EXACT_SAFE_STATEMENT_MATCH ||
            relation == ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT

    private fun assessClaims(
        first: SourceEvidence,
        second: SourceEvidence,
    ): ClaimAssessment {
        val sharedGoalTerms = first.matchedTerms intersect second.matchedTerms

        // Conflict-first: never let one harmless duplicate sentence hide a contradictory
        // relevant excerpt elsewhere on the same two bounded source pages.
        for (a in first.excerpts) {
            val firstValues = literals(a)
            val anchorsA = lexicalAnchors(a)
            val skeletonA = literalSkeleton(a)
            for (b in second.excerpts) {
                val secondValues = literals(b)
                val sharedAnchors = (anchorsA intersect lexicalAnchors(b)).sorted()
                val strongTopicShape = sharedGoalTerms.size >= 2 && sharedAnchors.size >= 3

                if (firstValues.isNotEmpty() && secondValues.isNotEmpty()) {
                    val skeletonB = literalSkeleton(b)
                    if (skeletonA.length >= 28 && skeletonA == skeletonB &&
                        firstValues.toSet() != secondValues.toSet()
                    ) {
                        return ClaimAssessment(
                            relation = ClaimRelation.CRITICAL_LITERAL_CONFLICT,
                            firstExcerpt = a,
                            secondExcerpt = b,
                            firstLiterals = firstValues,
                            secondLiterals = secondValues,
                            sharedAnchors = sharedAnchors.take(8),
                        )
                    }
                    if (strongTopicShape && sharedAnchors.size >= 4 &&
                        sameCriticalProfileWithDifferentValues(a, b)
                    ) {
                        return ClaimAssessment(
                            relation = ClaimRelation.CRITICAL_LITERAL_CONFLICT,
                            firstExcerpt = a,
                            secondExcerpt = b,
                            firstLiterals = firstValues,
                            secondLiterals = secondValues,
                            sharedAnchors = sharedAnchors.take(8),
                        )
                    }
                }

                if (strongTopicShape && sameCriticalFacts(a, b) &&
                    (hasNegativePolarity(a) != hasNegativePolarity(b) ||
                        opposingActionConflict(a, b))
                ) {
                    return ClaimAssessment(
                        relation = ClaimRelation.STRUCTURED_CLAIM_CONFLICT,
                        firstExcerpt = a,
                        secondExcerpt = b,
                        firstLiterals = firstValues,
                        secondLiterals = secondValues,
                        sharedAnchors = sharedAnchors.take(8),
                    )
                }
            }
        }

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
            val anchorsA = lexicalAnchors(a)
            for (b in second.excerpts) {
                val secondValues = literals(b)
                if (secondValues.isEmpty() || !sameCriticalFacts(a, b) ||
                    hasNegativePolarity(a) != hasNegativePolarity(b) ||
                    !actionsCompatible(a, b) || !conceptsCompatible(a, b) ||
                    !entitiesCompatible(a, b)
                ) continue
                val sharedAnchors = (anchorsA intersect lexicalAnchors(b)).sorted()
                if (sharedAnchors.size >= 3) {
                    return ClaimAssessment(
                        relation = ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT,
                        firstExcerpt = a,
                        secondExcerpt = b,
                        firstLiterals = firstValues,
                        secondLiterals = secondValues,
                        sharedAnchors = sharedAnchors.take(8),
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
            decision = Decision.TWO_DIFFERENT_PUBLIC_HOSTS_VERIFIED,
            claimAssessment = assessClaims(session.first, second),
        )
    }
}
