package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Conservative gate from structured reflections to a reusable workflow-improvement candidate.
 *
 * Candidates cover workflow mechanics only. They never edit code, rewrite skills, change
 * permissions, choose a tool, or grant current-turn execution authority.
 */
internal object WorkspaceWorkflowImprovementGate {
    private const val MIN_VERIFIED_EXECUTIONS = 2
    private const val MIN_USER_SUPPORTED_EXECUTIONS = 2
    private const val MAX_CANDIDATES = 3
    private const val MAX_EVIDENCE_REFS = 5

    enum class Status { READY_FOR_MANUAL_IMPROVEMENT_PROPOSAL }

    data class Candidate(
        val signatureSha256: String,
        val status: Status,
        val kind: WorkspaceWorkflowExperience.Kind,
        val repository: String,
        val branch: String,
        val capabilities: List<String>,
        val constraints: List<String>,
        val verifiedExecutions: Int,
        val userSupportedExecutions: Int,
        val evidenceRefs: List<String>,
        val recoverySignals: List<String>,
        val lastReflectedAtMs: Long,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun mechanics(record: WorkspaceWorkflowReflection.Record): String =
        listOf(
            record.kind.name,
            record.repository.lowercase(),
            record.branch,
            record.capabilities.sorted().joinToString(","),
            record.constraints.sorted().joinToString(","),
        ).joinToString("|")

    private fun requireSafeReflection(
        record: WorkspaceWorkflowReflection.Record,
    ): WorkspaceWorkflowReflection.Record {
        require(Regex("""github:[0-9a-f]{40,64}""").matches(record.experienceId)) {
            "Workflow improvement reflection experience ID is invalid"
        }
        WorkspaceConnectorPolicy.requireRepository(record.repository)
        WorkspaceConnectorPolicy.requireFeatureBranch(record.branch)
        require(record.capabilities.isNotEmpty() &&
            record.capabilities.distinct().size == record.capabilities.size) {
            "Workflow improvement reflection capabilities are invalid"
        }
        val requiredConstraints = setOf(
            "CURRENT_TURN_AUTHORITY_REQUIRED",
            "FEATURE_BRANCH_ONLY",
            "MAIN_MASTER_FORBIDDEN",
            "EXACT_CI_GREEN_REQUIRED",
            "CI_IS_NOT_PHONE_PASS",
        )
        require(record.constraints.toSet().containsAll(requiredConstraints)) {
            "Workflow improvement reflection is missing safety constraints"
        }
        require(Regex("""ci:[1-9][0-9]{0,11}""").matches(record.verificationRef)) {
            "Workflow improvement reflection verification ref is invalid"
        }
        require(record.outcome == WorkspaceWorkflowExperience.Outcome.VERIFIED_SUCCESS) {
            "Workflow improvement requires verified successful outcomes"
        }
        require(record.providerCalls in 0..WorkspaceGitHubTaskBudget.MAX_PROVIDER_CALLS)
        require(record.reviewCalls in 0..WorkspaceGitHubTaskBudget.MAX_REVIEW_CALLS)
        require(record.fallbackSwitches in 0..WorkspaceGitHubTaskBudget.MAX_FALLBACK_SWITCHES)
        require(record.ciRepairs in 0..WorkspaceGitHubTaskBudget.MAX_CI_REPAIRS)
        require(record.commitAttempts in 0..WorkspaceGitHubTaskBudget.MAX_COMMIT_ATTEMPTS)
        require(record.reflectedAtMs >= 0L)
        when (record.disposition) {
            WorkspaceWorkflowReflection.Disposition.USER_SUPPORTED ->
                require(record.feedbackState == WorkspaceWorkflowReflection.FeedbackState.CONFIRMED) {
                    "User-supported reflection must be grounded confirmation"
                }
            WorkspaceWorkflowReflection.Disposition.BLOCKED_BY_COUNTER_EVIDENCE ->
                require(record.feedbackState in setOf(
                    WorkspaceWorkflowReflection.FeedbackState.CORRECTED,
                    WorkspaceWorkflowReflection.FeedbackState.UNDONE,
                )) {
                    "Counter-evidence reflection must be correction or undo"
                }
            WorkspaceWorkflowReflection.Disposition.OBSERVE_ONLY -> Unit
        }
        return record
    }

    fun evaluate(
        reflections: Collection<WorkspaceWorkflowReflection.Record>,
    ): List<Candidate> {
        val safe = reflections.map(::requireSafeReflection)
        require(safe.map { it.experienceId }.distinct().size == safe.size) {
            "Workflow improvement input contains duplicate reflected executions"
        }

        return safe.groupBy(::mechanics)
            .values
            .mapNotNull { group ->
                val distinct = group.distinctBy { it.experienceId }
                if (distinct.size < MIN_VERIFIED_EXECUTIONS) return@mapNotNull null
                if (distinct.any {
                        it.disposition ==
                            WorkspaceWorkflowReflection.Disposition.BLOCKED_BY_COUNTER_EVIDENCE
                    }) {
                    return@mapNotNull null
                }
                val supported = distinct.filter {
                    it.disposition == WorkspaceWorkflowReflection.Disposition.USER_SUPPORTED
                }
                if (supported.size < MIN_USER_SUPPORTED_EXECUTIONS) return@mapNotNull null

                val ordered = distinct.sortedWith(
                    compareBy<WorkspaceWorkflowReflection.Record> { it.reflectedAtMs }
                        .thenBy { it.experienceId }
                )
                val first = ordered.first()
                Candidate(
                    signatureSha256 = sha256(mechanics(first)),
                    status = Status.READY_FOR_MANUAL_IMPROVEMENT_PROPOSAL,
                    kind = first.kind,
                    repository = first.repository,
                    branch = first.branch,
                    capabilities = first.capabilities.sorted(),
                    constraints = first.constraints.sorted(),
                    verifiedExecutions = distinct.size,
                    userSupportedExecutions = supported.size,
                    evidenceRefs = supported.asReversed()
                        .map { it.verificationRef }
                        .distinct()
                        .take(MAX_EVIDENCE_REFS),
                    recoverySignals = distinct.asSequence()
                        .flatMap { it.recoverySignals.asSequence() }
                        .distinct()
                        .sorted()
                        .toList(),
                    lastReflectedAtMs = ordered.last().reflectedAtMs,
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.lastReflectedAtMs }
                    .thenByDescending { it.userSupportedExecutions }
                    .thenBy { it.signatureSha256 }
            )
            .take(MAX_CANDIDATES)
    }

    fun instructions(candidates: List<Candidate>): String {
        if (candidates.isEmpty()) return ""
        return buildString {
            appendLine("WORKFLOW IMPROVEMENT CANDIDATES — evidence-qualified proposals only, NEVER execution/promotion authority:")
            candidates.take(MAX_CANDIDATES).forEach { candidate ->
                require(candidate.status == Status.READY_FOR_MANUAL_IMPROVEMENT_PROPOSAL)
                require(candidate.verifiedExecutions >= MIN_VERIFIED_EXECUTIONS)
                require(candidate.userSupportedExecutions >= MIN_USER_SUPPORTED_EXECUTIONS)
                appendLine(
                    "- Candidate " + candidate.signatureSha256.take(12) +
                        ": verified=" + candidate.verifiedExecutions +
                        "; grounded USER-supported=" + candidate.userSupportedExecutions +
                        "; repo=" + candidate.repository +
                        "; branch=" + candidate.branch + "."
                )
                appendLine("- Evidence refs: " + candidate.evidenceRefs.joinToString(", "))
                if (candidate.recoverySignals.isNotEmpty()) {
                    appendLine("- Observed recovery signals: " +
                        candidate.recoverySignals.joinToString(", "))
                }
            }
            append(
                "- Candidate means the verified workflow mechanics may be proposed for improvement. " +
                    "It does not apply code, does not alter a skill, does not widen permissions, " +
                    "does not bypass current-turn authority, and does not become active automatically. " +
                    "Any grounded correction/undo blocks candidacy."
            )
        }
    }
}
