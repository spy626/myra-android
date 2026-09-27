package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConnectorPolicyTest {
    @Test fun repositoryAndFeatureBranchAreCanonicalized() {
        val binding = WorkspaceConnectorPolicy.binding(
            "https://github.com/spy626/myra-android.git",
            "agent/myra-phase-1",
        )
        assertEquals("spy626/myra-android", binding.repository)
        assertEquals("agent/myra-phase-1", binding.branch)
    }

    @Test fun mainAndMasterCannotBeSelfEditBranches() {
        assertTrue(runCatching {
            WorkspaceConnectorPolicy.requireFeatureBranch("main")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceConnectorPolicy.requireFeatureBranch("master")
        }.isFailure)
    }

    @Test fun c1AllowsReadsButNoWritesOrDangerousActions() {
        assertTrue(
            WorkspaceConnectorPolicy.allowedInC1(
                WorkspaceConnectorPolicy.GitHubAction.READ_REPOSITORY
            )
        )
        assertTrue(
            WorkspaceConnectorPolicy.allowedInC1(
                WorkspaceConnectorPolicy.GitHubAction.READ_ACTIONS
            )
        )
        assertFalse(
            WorkspaceConnectorPolicy.allowedInC1(
                WorkspaceConnectorPolicy.GitHubAction.CREATE_OR_UPDATE_FILES
            )
        )
        assertFalse(
            WorkspaceConnectorPolicy.allowedInC1(
                WorkspaceConnectorPolicy.GitHubAction.MODIFY_MAIN_OR_MASTER
            )
        )
        assertFalse(
            WorkspaceConnectorPolicy.allowedInC1(
                WorkspaceConnectorPolicy.GitHubAction.FORCE_PUSH
            )
        )
    }
}
