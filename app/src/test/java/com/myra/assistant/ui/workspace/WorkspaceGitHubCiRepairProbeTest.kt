package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test-only physical-phone acceptance probe for the bounded CI repair loop.
 *
 * Normal repository state must always be SAFE. A controlled phone test may set BROKEN_ONCE for
 * the first commit only; exact CI should fail, then LYRA's same-task CI repair must restore SAFE.
 */
internal object WorkspaceGitHubCiRepairProbe {
    const val PROBE_STATE = "SAFE"
}

class WorkspaceGitHubCiRepairProbeTest {
    @Test fun controlledProbeMustEndSafe() {
        assertEquals(
            "CI repair acceptance probe must be restored to SAFE",
            "SAFE",
            WorkspaceGitHubCiRepairProbe.PROBE_STATE,
        )
    }
}
