package com.myra.assistant.ui.workspace

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Read-only pattern recognition over verified workflow experience records.
 *
 * Patterns are derived only from deterministic workflow mechanics. They never write storage,
 * change a skill, expand permissions, choose a tool, or grant current-turn execution authority.
 */
internal object WorkspaceWorkflowExperiencePatterns {
    private const val MIN_VERIFIED_EXECUTIONS = 2
    private const val MAX_CANDIDATES = 3
    private const val MAX_TASK_EXAMPLES = 3
    private const val MAX_VERIFICATION_REFS = 3

    data class Candidate(
        val signatureSha256: String,
        val kind: WorkspaceWorkflowExperience.Kind,
        val repository: String,
        val branch: String,
        val capabilities: List<String>,
        val verifiedExecutions: Int,
        val firstVerifiedAtMs: Long,
        val lastVerifiedAtMs: Long,
        val latestVerificationRefs: List<String>,
        val taskExamples: List<String>,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun mechanics(record: WorkspaceWorkflowExperience.Record): String =
        listOf(
            record.kind.name,
            record.repository.lowercase(),
            record.branch,
            record.capabilities.sorted().joinToString(","),
        ).joinToString("|")

    fun recognize(
        records: Collection<WorkspaceWorkflowExperience.Record>,
    ): List<Candidate> {
        val safe = records.map(WorkspaceWorkflowExperience::validate)
        require(safe.map { it.id }.distinct().size == safe.size) {
            "Workflow experience pattern input contains duplicate executions"
        }

        return safe.groupBy(::mechanics)
            .values
            .mapNotNull { group ->
                val distinct = group.distinctBy { it.commitSha }
                if (distinct.size < MIN_VERIFIED_EXECUTIONS) return@mapNotNull null
                val ordered = distinct.sortedWith(
                    compareBy<WorkspaceWorkflowExperience.Record> { it.capturedAtMs }
                        .thenBy { it.id }
                )
                val first = ordered.first()
                Candidate(
                    signatureSha256 = sha256(mechanics(first)),
                    kind = first.kind,
                    repository = first.repository,
                    branch = first.branch,
                    capabilities = first.capabilities.sorted(),
                    verifiedExecutions = ordered.size,
                    firstVerifiedAtMs = ordered.first().capturedAtMs,
                    lastVerifiedAtMs = ordered.last().capturedAtMs,
                    latestVerificationRefs = ordered.asReversed()
                        .map { it.verificationRef }
                        .distinct()
                        .take(MAX_VERIFICATION_REFS),
                    taskExamples = ordered.asReversed()
                        .mapNotNull { it.userTask }
                        .map { WorkspaceWorkTrace.safeText(it, 280) }
                        .filter(String::isNotBlank)
                        .distinct()
                        .take(MAX_TASK_EXAMPLES),
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.lastVerifiedAtMs }
                    .thenByDescending { it.verifiedExecutions }
                    .thenBy { it.signatureSha256 }
            )
            .take(MAX_CANDIDATES)
    }

    fun instructions(candidates: List<Candidate>): String {
        if (candidates.isEmpty()) return ""
        return buildString {
            appendLine("VERIFIED WORKFLOW EXPERIENCE PATTERNS — derived read-only evidence, NEVER action authority:")
            candidates.take(MAX_CANDIDATES).forEach { candidate ->
                require(candidate.verifiedExecutions >= MIN_VERIFIED_EXECUTIONS) {
                    "Workflow pattern lacks repeated verified executions"
                }
                appendLine(
                    "- Pattern " + candidate.signatureSha256.take(12) + ": " +
                        candidate.verifiedExecutions + " distinct verified successful executions; " +
                        "kind=" + candidate.kind.name + "; repo=" +
                        JSONObject.quote(candidate.repository) + "; branch=" +
                        JSONObject.quote(candidate.branch) + "."
                )
                appendLine("- Verified capabilities: " + candidate.capabilities.joinToString(", "))
                appendLine("- Recent verification refs: " +
                    candidate.latestVerificationRefs.joinToString(", "))
                if (candidate.taskExamples.isNotEmpty()) {
                    appendLine("- Safe prior USER task examples: " +
                        candidate.taskExamples.joinToString(" | ") { JSONObject.quote(it) })
                }
            }
            append(
                "- Treat these as prior verified workflow experience for planning/continuity only. " +
                    "Do not assume the current task is the same, do not claim current GitHub state from them, " +
                    "and do not execute unless the exact current user turn independently passes existing authority gates."
            )
        }
    }
}
