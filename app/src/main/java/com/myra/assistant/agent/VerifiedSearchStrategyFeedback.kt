package com.myra.assistant.agent

import java.security.MessageDigest

/**
 * Small process-local history for the existing GeneralAgentRuntime. Stores only
 * verified search capability outcomes and coarse app scopes; never page content.
 */
class VerifiedSearchStrategyFeedback(private val limit: Int = 32) {
    init { require(limit in 4..64) }
    data class Counts(val successes: Int, val failures: Int) {
        val score: Int get() = 2 * (successes - failures)
    }
    /** Only a digest of the internal task ID is retained across process restarts. */
    data class Record(
        val taskKey: String,
        val capability: ToolCapability,
        val scope: String,
        val status: GeneralVerificationStatus,
    )
    private val outcomes = ArrayDeque<Record>()
    private val taskHash = Regex("""[0-9a-f]{64}""")
    private fun digest(id: String) =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun isValid(row: Record) = taskHash.matches(row.taskKey) &&
        row.capability in setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH) &&
        row.status in setOf(GeneralVerificationStatus.SUCCESS, GeneralVerificationStatus.FAILURE) &&
        (row.scope == "unspecified" || packageName.matches(row.scope))
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
            outcomes.any { it.taskKey == digest(task.id) && it.capability == step.capability }
        ) return false
        outcomes.addLast(Record(digest(task.id), step.capability,
            scope(task.intent.relevantApp), result.status))
        while (outcomes.size > limit) outcomes.removeFirst()
        return true
    }

    @Synchronized fun snapshot(): List<Record> = outcomes.toList()

    /** DB restore cannot override newer in-process evidence or duplicate an execution. */
    @Synchronized fun restore(restored: Collection<Record>) {
        val current = outcomes.toList()
        val currentKeys = current.map { it.taskKey to it.capability }.toSet()
        val merged = (restored.filter(::isValid).filterNot {
            (it.taskKey to it.capability) in currentKeys
        } + current.filter(::isValid))
            .distinctBy { it.taskKey to it.capability }.takeLast(limit)
        outcomes.clear()
        merged.forEach(outcomes::addLast)
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
