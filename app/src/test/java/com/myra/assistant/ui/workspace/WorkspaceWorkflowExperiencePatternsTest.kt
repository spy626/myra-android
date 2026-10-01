package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowExperiencePatternsTest {
    private fun record(
        index: Int,
        task: String = "verified task " + index,
        repository: String = "spy626/myra-android",
        branch: String = "agent/myra-phase-1",
    ): WorkspaceWorkflowExperience.Record {
        val commit = index.toString(16).padStart(40, '0')
        return WorkspaceWorkflowExperience.Record(
            id = "github:" + commit,
            kind = WorkspaceWorkflowExperience.Kind.CONNECTED_GITHUB_SELF_EDIT,
            userTask = task,
            repository = repository,
            branch = branch,
            commitSha = commit,
            changedFiles = listOf(
                "app/src/main/java/com/myra/assistant/ui/workspace/File" + index + ".kt"
            ),
            capabilities = listOf(
                "CONNECTED_REPOSITORY_READ",
                "PROTECTED_FEATURE_BRANCH_WRITE",
                "GITHUB_ACTIONS_CI_VERIFY",
            ),
            evidenceKind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            evidenceSignal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            verificationRef = "ci:" + (3300 + index),
            verificationUrl =
                "https://github.com/spy626/myra-android/actions/runs/" + (3300 + index),
            outcome = WorkspaceWorkflowExperience.Outcome.VERIFIED_SUCCESS,
            capturedAtMs = index.toLong(),
        )
    }

    @Test fun oneVerifiedExecutionDoesNotBecomeLearnedPattern() {
        assertTrue(
            WorkspaceWorkflowExperiencePatterns.recognize(
                listOf(record(1))
            ).isEmpty()
        )
    }

    @Test fun repeatedDistinctVerifiedExecutionsBecomeOneMechanicsCandidate() {
        val patterns = WorkspaceWorkflowExperiencePatterns.recognize(
            listOf(
                record(1, "fix runtime state"),
                record(2, "add safe read route"),
                record(3, "persist provenance"),
            )
        )

        assertEquals(1, patterns.size)
        val candidate = patterns.single()
        assertEquals(3, candidate.verifiedExecutions)
        assertEquals("ci:3303", candidate.latestVerificationRefs.first())
        assertEquals(
            listOf("persist provenance", "add safe read route", "fix runtime state"),
            candidate.taskExamples,
        )
        assertTrue(candidate.capabilities.contains("GITHUB_ACTIONS_CI_VERIFY"))
    }

    @Test fun differentRepositoryMechanicsDoNotMerge() {
        val patterns = WorkspaceWorkflowExperiencePatterns.recognize(
            listOf(
                record(1),
                record(2),
                record(3, repository = "spy626/other-repo"),
                record(4, repository = "spy626/other-repo"),
            )
        )

        assertEquals(2, patterns.size)
        assertTrue(patterns.all { it.verifiedExecutions == 2 })
    }

    @Test fun promptSaysPatternIsPlanningEvidenceNotExecutionAuthority() {
        val candidate = WorkspaceWorkflowExperiencePatterns.recognize(
            listOf(record(1), record(2))
        ).single()
        val text = WorkspaceWorkflowExperiencePatterns.instructions(listOf(candidate))

        assertTrue(text.contains("VERIFIED WORKFLOW EXPERIENCE PATTERNS"))
        assertTrue(text.contains("NEVER action authority"))
        assertTrue(text.contains("do not execute unless the exact current user turn"))
        assertTrue(text.contains("do not claim current GitHub state"))
    }
}
