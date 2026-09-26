package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/** Trusted local evidence for one Workspace mutation. No provider/model output can create PASS. */
internal enum class WorkspaceVerificationStatus { PASS, FAIL_FIXABLE, UNKNOWN, BLOCKED }

internal enum class WorkspaceVerificationFailure {
    NONE,
    TASK_CHANGED,
    ROLLBACK_MISSING,
    ROLLBACK_MISMATCH,
    SOURCE_UNREADABLE,
    EXPECTED_WRITE_NOT_OBSERVED,
    SOURCE_CHANGED_AFTER_WRITE,
    WEBSITE_FILES_UNREADABLE,
    WEBSITE_WRITE_NOT_OBSERVED,
    WEBSITE_FILES_CHANGED_AFTER_WRITE,
}

internal data class WorkspaceVerificationResult(
    val status: WorkspaceVerificationStatus,
    val failure: WorkspaceVerificationFailure,
    val summary: String,
    val evidence: List<String> = emptyList(),
) {
    val passed: Boolean get() = status == WorkspaceVerificationStatus.PASS
}

/**
 * Deterministic post-write verification for existing Workspace owners.
 *
 * It never writes files, calls a model/provider, changes task approval, clears rollback,
 * or claims Preview/build/phone acceptance. UNKNOWN may be re-observed; mutation is never
 * repeated by this verifier.
 */
internal object WorkspaceSelfVerification {
    const val MAX_UNKNOWN_REOBSERVATIONS = 2

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun taskCurrent(tasks: WorkspaceTaskStore, projectId: String,
                            taskId: String, specToken: String): Boolean {
        val task = tasks.get(projectId) ?: return false
        return task.taskId == taskId &&
            WorkspaceTaskContract.specToken(task) == specToken &&
            WorkspaceTaskContract.isSpecApproved(task) &&
            task.status != WorkspaceTaskStatus.PAUSED
    }

    fun verifyScopedEdit(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projects: WorkspaceProjectStore,
        proposal: WorkspaceScopedEdit.Proposal,
    ): WorkspaceVerificationResult {
        if (!taskCurrent(tasks, proposal.projectId, proposal.taskId, proposal.specToken)) {
            return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.TASK_CHANGED,
                "Saved task/spec changed after the edit; completion is not trusted.",
                listOf("task_or_spec_changed"),
            )
        }

        val backup = runCatching { WorkspaceScopedEdit.pending(projects, proposal.projectId) }
            .getOrElse {
                return WorkspaceVerificationResult(
                    WorkspaceVerificationStatus.UNKNOWN,
                    WorkspaceVerificationFailure.SOURCE_UNREADABLE,
                    "Protected rollback could not be read for verification.",
                    listOf("rollback_read_failed"),
                )
            }
            ?: return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.ROLLBACK_MISSING,
                "Protected rollback is missing; completion cannot be proven safely.",
                listOf("rollback_missing"),
            )

        if (backup.taskId != proposal.taskId || backup.path != proposal.path ||
            backup.beforeSha256 != proposal.baseSha256 ||
            backup.afterSha256 != proposal.resultSha256) {
            return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.ROLLBACK_MISMATCH,
                "Protected rollback does not match the approved proposal.",
                listOf("rollback_mismatch"),
            )
        }

        val current = runCatching { files.readFile(proposal.projectId, proposal.path) }
            .getOrElse {
                return WorkspaceVerificationResult(
                    WorkspaceVerificationStatus.UNKNOWN,
                    WorkspaceVerificationFailure.SOURCE_UNREADABLE,
                    "Saved source could not be re-read after the edit.",
                    listOf("source_read_failed"),
                )
            }
        val currentHash = sha(current)
        return when (currentHash) {
            proposal.resultSha256 -> WorkspaceVerificationResult(
                WorkspaceVerificationStatus.PASS,
                WorkspaceVerificationFailure.NONE,
                "Saved file hash and protected rollback match the approved edit.",
                listOf("task_spec_current", "rollback_matches", "saved_file_hash_matches"),
            )
            proposal.baseSha256 -> WorkspaceVerificationResult(
                WorkspaceVerificationStatus.FAIL_FIXABLE,
                WorkspaceVerificationFailure.EXPECTED_WRITE_NOT_OBSERVED,
                "The file is still exactly at its approved pre-edit state; the expected write was not observed.",
                listOf("task_spec_current", "rollback_matches", "source_still_original"),
            )
            else -> WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.SOURCE_CHANGED_AFTER_WRITE,
                "The file differs from both the approved before-state and expected result; newer work is protected.",
                listOf("task_spec_current", "rollback_matches", "unexpected_source_hash"),
            )
        }
    }

    fun verifyWebsite(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projects: WorkspaceProjectStore,
        snapshot: WorkspaceWebsiteGeneration.Snapshot,
        expected: Map<String, String>,
    ): WorkspaceVerificationResult {
        if (!taskCurrent(tasks, snapshot.projectId, snapshot.taskId, snapshot.specToken)) {
            return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.TASK_CHANGED,
                "Saved website task/spec changed after generation; completion is not trusted.",
                listOf("task_or_spec_changed"),
            )
        }
        if (expected.keys != WorkspaceWebsiteGeneration.PATHS.toSet()) {
            return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.ROLLBACK_MISMATCH,
                "Expected website result is incomplete.",
                listOf("expected_file_set_mismatch"),
            )
        }

        val backup = runCatching { WorkspaceWebsiteGeneration.pending(projects, snapshot.projectId) }
            .getOrElse {
                return WorkspaceVerificationResult(
                    WorkspaceVerificationStatus.UNKNOWN,
                    WorkspaceVerificationFailure.WEBSITE_FILES_UNREADABLE,
                    "Website rollback record could not be re-read.",
                    listOf("website_rollback_read_failed"),
                )
            }
            ?: return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.ROLLBACK_MISSING,
                "Website rollback record is missing; completion cannot be proven safely.",
                listOf("website_rollback_missing"),
            )

        val expectedHashes = WorkspaceWebsiteGeneration.PATHS.associateWith { sha(expected.getValue(it)) }
        if (backup.projectId != snapshot.projectId || backup.original != snapshot.original ||
            backup.afterHashes != expectedHashes) {
            return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.ROLLBACK_MISMATCH,
                "Website rollback record does not match the approved generation snapshot.",
                listOf("website_rollback_mismatch"),
            )
        }

        val actual = runCatching {
            val present = files.list(snapshot.projectId).filterNot { it.folder }.map { it.path }.toSet()
            WorkspaceWebsiteGeneration.PATHS.associateWith { path ->
                if (path in present) files.readFile(snapshot.projectId, path) else null
            }
        }.getOrElse {
            return WorkspaceVerificationResult(
                WorkspaceVerificationStatus.UNKNOWN,
                WorkspaceVerificationFailure.WEBSITE_FILES_UNREADABLE,
                "Saved website files could not all be re-read.",
                listOf("website_files_read_failed"),
            )
        }

        return when {
            WorkspaceWebsiteGeneration.PATHS.all { actual[it] == expected[it] } ->
                WorkspaceVerificationResult(
                    WorkspaceVerificationStatus.PASS,
                    WorkspaceVerificationFailure.NONE,
                    "All three saved website files match the verified generation output and rollback record.",
                    listOf("task_spec_current", "website_rollback_matches", "all_saved_files_match"),
                )
            WorkspaceWebsiteGeneration.PATHS.all { actual[it] == snapshot.original[it] } ->
                WorkspaceVerificationResult(
                    WorkspaceVerificationStatus.FAIL_FIXABLE,
                    WorkspaceVerificationFailure.WEBSITE_WRITE_NOT_OBSERVED,
                    "Website files remain exactly at the approved pre-generation state; expected writes were not observed.",
                    listOf("task_spec_current", "website_rollback_matches", "website_still_original"),
                )
            else -> WorkspaceVerificationResult(
                WorkspaceVerificationStatus.BLOCKED,
                WorkspaceVerificationFailure.WEBSITE_FILES_CHANGED_AFTER_WRITE,
                "Website files are mixed or changed after generation; newer work is protected.",
                listOf("task_spec_current", "website_rollback_matches", "unexpected_website_state"),
            )
        }
    }

    /** Bounded read-only recovery for uncertain evidence. It never repeats a mutation/provider call. */
    fun settleUnknown(
        initial: WorkspaceVerificationResult,
        reobserve: () -> WorkspaceVerificationResult,
        maxReobservations: Int = MAX_UNKNOWN_REOBSERVATIONS,
    ): WorkspaceVerificationResult {
        require(maxReobservations in 0..MAX_UNKNOWN_REOBSERVATIONS) {
            "Unknown verification re-observation budget is out of bounds"
        }
        var current = initial
        repeat(maxReobservations) {
            if (current.status != WorkspaceVerificationStatus.UNKNOWN) return current
            current = reobserve()
        }
        return current
    }
}
