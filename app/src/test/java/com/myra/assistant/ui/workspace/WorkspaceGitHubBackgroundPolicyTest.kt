package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubBackgroundPolicyTest {
    @Test fun onlyValidCheckpointShaIsResumable() {
        assertTrue(
            WorkspaceGitHubBackgroundPolicy.hasCheckpoint(
                "a".repeat(40)
            )
        )
        assertTrue(
            WorkspaceGitHubBackgroundPolicy.hasCheckpoint(
                "B".repeat(64)
            )
        )
        assertFalse(WorkspaceGitHubBackgroundPolicy.hasCheckpoint(null))
        assertFalse(WorkspaceGitHubBackgroundPolicy.hasCheckpoint("abc"))
        assertFalse(WorkspaceGitHubBackgroundPolicy.hasCheckpoint("g".repeat(40)))
    }
}
