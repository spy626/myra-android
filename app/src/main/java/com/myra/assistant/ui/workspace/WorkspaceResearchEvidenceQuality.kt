package com.myra.assistant.ui.workspace

import java.net.URI
import java.util.Locale

/**
 * Research-only provenance and quantitative-claim guard. This does NOT decide
 * intent, factual truth, or tool authorization. Search reachability isn't a
 * credibility score. Primary publishers are ranking hints, never a whitelist.
 */
internal object WorkspaceResearchEvidenceQuality {
    /** Publisher grouping rather than raw subdomains; no invented source identity. */
    fun publisher(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }
            .getOrNull()?.removePrefix("www.") ?: return null
        val parts = host.split('.')
        if (parts.size < 2) return null
        val suffix = if (parts.size >= 3 && parts[parts.lastIndex - 1] in setOf(
                "co", "com", "org", "ac", "gov", "edu", "net"
            ) && parts.last().length == 2) 3 else 2
        return parts.takeLast(suffix).joinToString(".")
    }

    private fun atHost(url: String, official: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }
            .getOrNull() ?: return false
        return host == official || host.endsWith("." + official)
    }

    /**
     * Platform owners' original documentation/support. A non-primary source can
     * still contribute a relevant, sourced qualitative fact.
     */
    fun primaryFor(
        source: WorkspacePublicWebSearch.Verified,
        question: String,
    ): Boolean {
        val subjects = WorkspaceResearchQuestionFocus.inspect(question).subjects
            .map { it.lowercase(Locale.ROOT) }
        val questionText = question.lowercase(Locale.ROOT)
        val url = source.finalUrl
        return when {
            ("android" in subjects || "android" in questionText) &&
                (atHost(url, "developer.android.com") ||
                    atHost(url, "support.google.com") ||
                    atHost(url, "source.android.com")) -> true
            (subjects.any { it in setOf("ios", "iphone", "ipad") } ||
                listOf("ios", "iphone", "ipad").any { it in questionText }) &&
                (atHost(url, "support.apple.com") ||
                    atHost(url, "developer.apple.com") ||
                    atHost(url, "apple.com")) -> true
            "kotlin" in questionText && atHost(url, "kotlinlang.org") -> true
            "python" in questionText && atHost(url, "docs.python.org") -> true
            else -> false
        }
    }

    /**
     * Measurements (e.g. 2+ hours, up to 3 hours, 20%, 15 mAh) must be
     * independently corroborated. Version names or dates are not measured
     * performance and continue through the existing literal-evidence audit.
     */
    private val measured = Regex(
        """(?iu)(?<![\p{L}\p{N}])(\d+(?:[.,]\d+)?)\s*(\+)?\s*""" +
            """(%|percent|hours?|hrs?|minutes?|mins?|days?|weeks?|""" +
            """mah|wh|watts?|watt-hours?|seconds?|secs?|times?)(?![\p{L}])"""
    )

    fun measurements(text: String): Set<String> =
        measured.findAll(text).map { match ->
            val number = match.groupValues[1].replace(",", ".")
            val plus = match.groupValues[2]
            val unit = when (match.groupValues[3].lowercase(Locale.ROOT)) {
                "hours", "hrs", "hr" -> "hour"
                "minutes", "mins", "min" -> "minute"
                "days" -> "day"
                "weeks" -> "week"
                "watts", "watt" -> "watt"
                "seconds", "secs", "sec" -> "second"
                "percent" -> "%"
                "times" -> "time"
                else -> match.groupValues[3].lowercase(Locale.ROOT)
            }
            number + plus + ":" + unit
        }.toSet()

    /**
     * One passage from each separate publisher has to show the SAME number/unit.
     * Don't count two pages belonging to the same owner as independent sources.
     * This is a modest corroboration test, not a guarantee of statistical truth.
     */
    fun corroboratesMeasurements(
        claim: String,
        supporting: List<WorkspaceResearchSynthesis.Evidence>,
    ): Boolean {
        val claimed = measurements(claim)
        if (claimed.isEmpty()) return true
        return claimed.all { value ->
            supporting.filter { value in measurements(it.passage) }
                .mapNotNull { publisher(it.source.finalUrl) }
                .distinct().size >= 2
        }
    }

    /** Extractive fallback must not repeat an uncorroborated numerical promise. */
    fun safeFallbackExtracts(
        extracts: List<WorkspacePublicResearch.Extract>,
    ): List<WorkspacePublicResearch.Extract> {
        val observations = extracts.flatMap { extract ->
            extract.passages.map { extract.source to it }
        }
        return extracts.mapNotNull { extract ->
            val safe = extract.passages.filter { passage ->
                val claims = measurements(passage)
                claims.isEmpty() || claims.all { unit ->
                    observations.filter { (source, observed) ->
                        unit in measurements(observed) &&
                            publisher(source.finalUrl) != null
                    }.mapNotNull { (source, _) -> publisher(source.finalUrl) }
                        .distinct().size >= 2
                }
            }
            if (safe.isEmpty()) null else extract.copy(passages = safe)
        }
    }
}
