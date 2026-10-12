package com.myra.assistant.agent

import java.net.URI
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A read-only, URL-bound extract from a verified public page, never an instruction. */
data class VerifiedResearchSource(
    val url: String,
    val factualPassages: Int,
    val passages: List<String> = emptyList(),
)

enum class ResearchEvidenceStatus { CORROBORATED, CONFLICT, INSUFFICIENT }

data class CorroboratedResearchPassages(
    val firstUrl: String,
    val firstPassage: String,
    val secondUrl: String,
    val secondPassage: String,
)

data class ResearchEvidenceAssessment(
    val status: ResearchEvidenceStatus,
    val independentSources: Int,
    val agreements: List<CorroboratedResearchPassages> = emptyList(),
    val conflictCount: Int = 0,
)

/**
 * Conservatively checks factual passages from independent public sites.
 * Lexical similarity is not semantic truth. Disagreement is a safety stop;
 * independent URLs alone can never complete a research goal.
 */
object AutonomousSourceAnalysisPolicy {
    private val wordPattern = Regex("""[\p{L}\p{N}]+""")
    private val numberPattern = Regex("""(?<![\p{L}\p{N}])\d+(?:[.,]\d+)*%?(?![\p{L}\p{N}])""")
    private val nonFactual = Regex(
        """(?iu)\b(?:ignore previous instructions|system prompt|execute this|password|otp|api key)\b"""
    )
    private val excludedWords = setOf(
        "the", "and", "that", "with", "from", "this", "which", "their",
        "your", "about", "there", "have", "been", "these", "those",
        "will", "would", "should", "could", "they", "into", "does",
        "were", "also", "where", "when", "while", "not", "never",
        "without", "cannot", "isnt", "doesnt", "no",
    )
    private val negations = setOf("not", "never", "without", "cannot", "isnt", "doesnt", "no")
    private data class Passage(
        val url: String,
        val domain: String,
        val text: String,
        val terms: Set<String>,
        val numbers: Set<String>,
        val hasNegation: Boolean,
    )

    fun boundQuery(task: GeneralRuntimeTask): String? {
        if (task.status != AgentRuntimeStatus.PLANNING ||
            task.intent.turnIntent != TurnIntent.MULTI_STEP_GOAL ||
            ToolCapability.OBSERVE_SCREEN !in task.intent.requiredCapabilities ||
            task.verifiedGoalSubsteps.size != 2 ||
            task.verifiedGoalSubsteps.first().capability !in setOf(
                ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH) ||
            task.verifiedGoalSubsteps.last().capability != ToolCapability.OBSERVE_SCREEN ||
            task.currentStep != null) return null
        return AutonomousRecoveryPolicy.safeSearch(task)?.query
    }

    private fun domainOf(url: String): String? = runCatching {
        val parsed = URI(url)
        if (!parsed.scheme.equals("https", ignoreCase = true) ||
            parsed.userInfo != null || parsed.host.isNullOrBlank()) null
        else url.toHttpUrlOrNull()?.topPrivateDomain()?.lowercase(Locale.ROOT)
    }.getOrNull()

    /** Count independent registrable domains, not hostnames or HTTP 200 responses. */
    fun independentEvidence(sources: List<VerifiedResearchSource>): Int =
        sources.asSequence().filter { it.factualPassages > 0 }
            .mapNotNull { domainOf(it.url) }.distinct().take(3).count()

    /**
     * Match only bounded, substantial near-identical statements from two sites.
     * Contradictory numeric anchors or negation within the same strongly matching
     * context prevent completion even if another passage pair appears to agree.
     * Unrelated sources and unsupported paraphrases remain INSUFFICIENT.
     */
    fun assess(sources: List<VerifiedResearchSource>): ResearchEvidenceAssessment {
        val count = independentEvidence(sources)
        if (count < 2) return ResearchEvidenceAssessment(ResearchEvidenceStatus.INSUFFICIENT, count)
        val lines = sources.take(3).flatMap { source ->
            val domain = domainOf(source.url) ?: return@flatMap emptyList()
            source.passages.take(minOf(source.factualPassages.coerceAtLeast(0), 3))
                .mapNotNull { text ->
                    val cleaned = text.trim().replace(Regex("""\s+"""), " ")
                    if (cleaned.length !in 65..550 || nonFactual.containsMatchIn(cleaned))
                        return@mapNotNull null
                    val tokens = wordPattern.findAll(cleaned.lowercase(Locale.ROOT))
                        .map { it.value }.toList()
                    val terms = tokens.filter {
                        it.length >= 3 && !it.all(Char::isDigit) && it !in excludedWords
                    }.toSet()
                    if (tokens.size < 10 || terms.size < 7) return@mapNotNull null
                    Passage(source.url, domain, cleaned, terms,
                        numberPattern.findAll(cleaned).map { it.value }.toSet(),
                        tokens.any { it in negations })
                }
        }
        var conflicts = 0
        val agreements = mutableListOf<CorroboratedResearchPassages>()
        for (first in lines.indices) {
            for (second in first + 1 until lines.size) {
                val a = lines[first]
                val b = lines[second]
                if (a.domain == b.domain) continue
                val overlap = a.terms.intersect(b.terms).size
                if (overlap < 6 || overlap.toDouble() / maxOf(a.terms.size, b.terms.size) < .75)
                    continue
                if (a.numbers != b.numbers || a.hasNegation != b.hasNegation) {
                    conflicts++
                    continue
                }
                agreements += CorroboratedResearchPassages(a.url, a.text, b.url, b.text)
            }
        }
        return when {
            conflicts > 0 -> ResearchEvidenceAssessment(
                ResearchEvidenceStatus.CONFLICT, count, conflictCount = conflicts)
            agreements.isNotEmpty() -> ResearchEvidenceAssessment(
                ResearchEvidenceStatus.CORROBORATED, count, agreements.take(3))
            else -> ResearchEvidenceAssessment(ResearchEvidenceStatus.INSUFFICIENT, count)
        }
    }
}
