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
}
