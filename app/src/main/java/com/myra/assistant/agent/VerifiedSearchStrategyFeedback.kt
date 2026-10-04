package com.myra.assistant.agent

/**
 * Small process-local history for the existing GeneralAgentRuntime. Stores only
 * verified search capability outcomes and coarse app scopes; never page content.
 */
class VerifiedSearchStrategyFeedback(private val limit: Int = 32) {
    init { require(limit in 4..64) }
    data class Counts(val successes: Int, val failures: Int) {
        val score: Int get() = 2 * (successes - failures)
    }
    private data class Outcome(
        val taskId: String,
        val capability: ToolCapability,
        val scope: String,
        val status: GeneralVerificationStatus,
    )
    private val outcomes = ArrayDeque<Outcome>()
    private val packageName = Regex("""[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,7}""")
    private fun scope(app: String?): String =
        app?.take(100)?.takeIf { packageName.matches(it) } ?: "unspecified"

    @Synchronized fun record(
        task: GeneralRuntimeTask,
        step: GeneralPlanStep,
        result: GeneralVerificationResult,
    ): Boolean {
        if (!task.intent.requiresAction || step.taskId != task.id ||
            step.capability !in setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH) ||
            result.status !in setOf(GeneralVerificationStatus.SUCCESS, GeneralVerificationStatus.FAILURE) ||
            result.confidence < .60 || "fresh_observation" !in result.evidence ||
            outcomes.any { it.taskId == task.id && it.capability == step.capability }
        ) return false
        outcomes.addLast(Outcome(task.id, step.capability, scope(task.intent.relevantApp), result.status))
        while (outcomes.size > limit) outcomes.removeFirst()
        return true
    }

    @Synchronized fun counts(capability: ToolCapability, app: String?): Counts {
        val selected = outcomes.filter { it.scope == scope(app) && it.capability == capability }
        return Counts(selected.count { it.status == GeneralVerificationStatus.SUCCESS },
            selected.count { it.status == GeneralVerificationStatus.FAILURE })
    }

    /** May rank only two already declared and executable equivalent read-only tools. */
    @Synchronized fun recommend(
        default: ToolCapability,
        alternative: ToolCapability?,
        declared: Set<ToolCapability>,
        executable: Set<ToolCapability>,
        app: String?,
    ): ToolCapability {
        if (alternative == null ||
            setOf(default, alternative) != setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH) ||
            !declared.containsAll(setOf(default, alternative)) ||
            !executable.containsAll(setOf(default, alternative))
        ) return default
        val baseline = counts(default, app)
        val candidate = counts(alternative, app)
        return if (candidate.successes >= 2 && candidate.successes > candidate.failures &&
            candidate.score >= baseline.score + 2
        ) alternative else default
    }
}
