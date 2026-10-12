package com.myra.assistant.agent

import java.net.URI
import java.util.Locale

/**
 * Read-only multi-step goal source evidence. Does not authorize phone navigation,
 * trust webpage instructions, or turn reachable links into verified factual claims.
 * Only the existing public-search and extraction runners may supply these receipts.
 */
data class VerifiedResearchSource(val url: String, val factualPassages: Int)

object AutonomousSourceAnalysisPolicy {
    fun boundQuery(task: GeneralRuntimeTask): String? {
        if (task.status != AgentRuntimeStatus.PLANNING ||
            task.intent.turnIntent != TurnIntent.MULTI_STEP_GOAL ||
            ToolCapability.OBSERVE_SCREEN !in task.intent.requiredCapabilities ||
            task.verifiedGoalSubsteps.size != 2 ||
            task.verifiedGoalSubsteps.first().capability !in setOf(
                ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH) ||
            task.verifiedGoalSubsteps.last().capability != ToolCapability.OBSERVE_SCREEN ||
            task.currentStep != null) return null
        // The final spoken search topic and the bound runtime query must agree.
        // A model, webpage, or restored receipt cannot expand that authority.
        return AutonomousRecoveryPolicy.safeSearch(task)?.query
    }

    fun independentEvidence(sources: List<VerifiedResearchSource>): Int {
        val hosts = sources.asSequence().filter { it.factualPassages > 0 }
            .mapNotNull { evidence ->
                runCatching {
                    val parsed = URI(evidence.url)
                    if (parsed.scheme?.lowercase(Locale.ROOT) != "https" ||
                        parsed.userInfo != null || parsed.host.isNullOrBlank()) null
                    else parsed.host.lowercase(Locale.ROOT).removePrefix("www.")
                }.getOrNull()
            }.distinct().take(3).toList()
        return hosts.size
    }
}
