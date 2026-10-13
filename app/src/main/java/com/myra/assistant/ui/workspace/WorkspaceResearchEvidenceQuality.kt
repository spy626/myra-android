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
        // Android's developer documentation and Google support are one owner.
        if (host == "android.com" || host.endsWith(".android.com")) return "google.com"
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
        """(?iu)(?<![\p{L}\p{N}])(\d+(?:[.,]\d+)?|one|two|three|four|""" +
            """five|six|seven|eight|nine|ten|eleven|twelve)\s*(\+)?\s*""" +
            """(?:extra\s+|additional\s+|more\s+)?""" +
            """(%|percent|hours?|hrs?|minutes?|mins?|days?|weeks?|""" +
            """mah|wh|watts?|watt-hours?|seconds?|secs?|times?)(?![\p{L}])"""
    )

    fun measurements(text: String): Set<String> =
        measured.findAll(text).map { match ->
            val number = when (match.groupValues[1].lowercase(Locale.ROOT)) {
                "one" -> "1"
                "two" -> "2"
                "three" -> "3"
                "four" -> "4"
                "five" -> "5"
                "six" -> "6"
                "seven" -> "7"
                "eight" -> "8"
                "nine" -> "9"
                "ten" -> "10"
                "eleven" -> "11"
                "twelve" -> "12"
                else -> match.groupValues[1].replace(",", ".")
            }
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
        question: String,
    ): Boolean {
        val claimed = measurements(claim)
        if (claimed.isEmpty()) return true
        return claimed.all { value ->
            val subjects = WorkspacePublicResearch.comparisonSubjects(question)
                .filter { WorkspaceResearchQuestionFocus.mentionsSubject(claim, it) }
            supporting.filter { evidence ->
                value in measurements(evidence.passage) && subjects.all { subject ->
                    WorkspaceResearchQuestionFocus.mentionsSubject(
                        evidence.passage + " " + evidence.source.title, subject)
                }
            }.mapNotNull { publisher(it.source.finalUrl) }
                .distinct().size >= 2
        }
    }

    /** Explicit user requirement for more than one independently owned website. */
    fun requestsMultiplePublishers(question: String): Boolean =
        Regex("""(?iu)\b(?:multiple|several|independent|do\s+alag|two\s+sources|2\s+sources)\b""")
            .containsMatchIn(question) &&
            Regex("""(?iu)\b(?:sources?|websites?|publishers?|references?)\b""")
                .containsMatchIn(question)

    fun publisherCount(extracts: List<WorkspacePublicResearch.Extract>): Int =
        extracts.mapNotNull { publisher(it.source.finalUrl) }.distinct().size

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
