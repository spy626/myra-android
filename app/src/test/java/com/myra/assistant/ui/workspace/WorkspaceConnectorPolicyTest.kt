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
        assertTrue(WorkspaceConnectorPolicy.allowedInC1(
            WorkspaceConnectorPolicy.GitHubAction.READ_REPOSITORY))
        assertTrue(WorkspaceConnectorPolicy.allowedInC1(
            WorkspaceConnectorPolicy.GitHubAction.READ_ACTIONS))
        assertFalse(WorkspaceConnectorPolicy.allowedInC1(
            WorkspaceConnectorPolicy.GitHubAction.CREATE_OR_UPDATE_FILES))
        assertFalse(WorkspaceConnectorPolicy.allowedInC1(
            WorkspaceConnectorPolicy.GitHubAction.MODIFY_MAIN_OR_MASTER))
    }

    @Test fun c2AllowsOnlyBoundedFeatureBranchWritesAndDraftPrLane() {
        assertTrue(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.READ_REPOSITORY))
        assertTrue(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.CREATE_OR_UPDATE_FILES))
        assertTrue(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.CREATE_OR_UPDATE_PULL_REQUEST))
        assertFalse(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.CREATE_FEATURE_BRANCH))
        assertFalse(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.MODIFY_MAIN_OR_MASTER))
        assertFalse(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.FORCE_PUSH))
        assertFalse(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.CHANGE_SECRETS))
        assertFalse(WorkspaceConnectorPolicy.allowedInC2(
            WorkspaceConnectorPolicy.GitHubAction.DELETE_REPOSITORY_OR_BRANCH))
    }
}
