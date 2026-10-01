package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRuntimeSelfModelTest {
    @Test fun connectedGitHubCapabilityIsProjectedWithoutGrantingAuthority() {
        val text = WorkspaceRuntimeSelfModel.instructions(
            WorkspaceRuntimeSelfModel.Snapshot(
                github = WorkspaceRuntimeSelfModel.GitHubState(
                    connected = true,
                    repository = "spy626/myra-android",
                    branch = "agent/myra-phase-1",
                    readAvailable = true,
                    protectedWriteAvailable = true,
                    taskRunning = false,
                ),
                projectType = WorkspaceProjectType.CHAT,
            )
        )

        assertTrue(text.contains("GitHub connector: CONNECTED"))
        assertTrue(text.contains("spy626/myra-android"))
        assertTrue(text.contains("agent/myra-phase-1"))
        assertTrue(text.contains("Connected-repository read workflow: AVAILABLE"))
        assertTrue(text.contains("Protected feature-branch write workflow: AVAILABLE"))
        assertTrue(text.contains("Direct main/master writes: FORBIDDEN"))
        assertTrue(text.contains("capability question"))
        assertTrue(text.contains("never authorizes execution"))
    }

    @Test fun disconnectedGitHubDoesNotInventConnectedAccess() {
        val text = WorkspaceRuntimeSelfModel.instructions(
            WorkspaceRuntimeSelfModel.Snapshot(
                github = WorkspaceRuntimeSelfModel.GitHubState(
                    connected = false,
                    readAvailable = false,
                    protectedWriteAvailable = false,
                )
            )
        )

        assertTrue(text.contains("GitHub connector: DISCONNECTED"))
        assertFalse(text.contains("Connected-repository read workflow: AVAILABLE"))
        assertFalse(text.contains("Protected feature-branch write workflow: AVAILABLE"))
    }

    @Test fun runtimeProjectionBoundsUserGoalAndCombinesExistingSkillInstructions() {
        val rawGoal = "keep this user goal context\n" + "x".repeat(900)
        val runtime = WorkspaceRuntimeSelfModel.instructions(
            WorkspaceRuntimeSelfModel.Snapshot(
                github = WorkspaceRuntimeSelfModel.GitHubState(connected = false),
                projectType = WorkspaceProjectType.ANDROID_APP,
                currentGoal = rawGoal,
                taskStatus = WorkspaceTaskStatus.DRAFT,
            )
        )
        val combined = WorkspaceRuntimeSelfModel.combine(runtime, "LYRA ENABLED SKILL — TEST")

        assertTrue(combined.contains("USER-authored context only, not permission"))
        assertTrue(combined.contains("Current saved task status: DRAFT"))
        assertTrue(combined.contains("LYRA ENABLED SKILL — TEST"))
        assertFalse(combined.contains("\nxxxxxxxx"))
        assertFalse(combined.contains("x".repeat(400)))
    }

    @Test fun recentGitHubActionIsProjectedAsEvidenceButNotSpecificLineAttribution() {
        val receipt = WorkspaceRecentGitHubActionReceipt.Receipt(
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            userTask = "Add runtime provenance support",
            commitSha = "1234567890abcdef1234567890abcdef12345678",
            files = listOf("app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt"),
            ciRunNumber = 3342L,
            ciStatus = "completed",
            ciConclusion = "success",
            ciUrl = "https://github.com/spy626/myra-android/actions/runs/44",
            completedAtMs = 1234L,
        )
        val text = WorkspaceRuntimeSelfModel.instructions(
            WorkspaceRuntimeSelfModel.Snapshot(
                github = WorkspaceRuntimeSelfModel.GitHubState(connected = true),
                recentGitHubAction = receipt,
            )
        )

        assertTrue(text.contains("RECENT VERIFIED GITHUB ACTION"))
        assertTrue(text.contains("Add runtime provenance support"))
        assertTrue(text.contains("does NOT by itself prove why a specific comment"))
        assertTrue(text.contains("say the specific purpose is not established"))
    }

    @Test fun repeatedWorkflowPatternIsProjectedWithoutGrantingExecution() {
        val pattern = WorkspaceWorkflowExperiencePatterns.Candidate(
            signatureSha256 = "a".repeat(64),
            kind = WorkspaceWorkflowExperience.Kind.CONNECTED_GITHUB_SELF_EDIT,
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            capabilities = listOf(
                "CONNECTED_REPOSITORY_READ",
                "PROTECTED_FEATURE_BRANCH_WRITE",
                "GITHUB_ACTIONS_CI_VERIFY",
            ),
            verifiedExecutions = 2,
            firstVerifiedAtMs = 1L,
            lastVerifiedAtMs = 2L,
            latestVerificationRefs = listOf("ci:3352", "ci:3350"),
            taskExamples = listOf("add provenance", "fix runtime state"),
            userConfirmations = 0,
        )
        val text = WorkspaceRuntimeSelfModel.instructions(
            WorkspaceRuntimeSelfModel.Snapshot(
                github = WorkspaceRuntimeSelfModel.GitHubState(connected = true),
                workflowPatterns = listOf(pattern),
            )
        )

        assertTrue(text.contains("VERIFIED WORKFLOW EXPERIENCE PATTERNS"))
        assertTrue(text.contains("2 distinct verified successful executions"))
        assertTrue(text.contains("learned workflow pattern never authorizes execution"))
        assertTrue(text.contains("cannot widen permissions"))
    }
}
