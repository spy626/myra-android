package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConnectedGitHubRunIntentTest {
    @Test fun exactBuildNumberReadOnlyQuestionRoutesToConnectedRunVerifier() {
        val decision = WorkspaceConnectedGitHubRunIntent.decide(
            "3324 dekho kuch change mat karna green hai kya?"
        )
        assertEquals(3324L, decision?.runNumber)
        assertNull(decision?.localError)
    }

    @Test fun multipleRunNumbersAreRejectedWithoutGuessing() {
        val decision = WorkspaceConnectedGitHubRunIntent.decide(
            "Build #3324 aur #3328 check karo, no changes."
        )
        assertNull(decision?.runNumber)
        assertTrue(decision?.localError?.contains("one GitHub Actions run at a time") == true)
    }

    @Test fun writeTurnAndGitHubUrlStayOnExistingRoutes() {
        assertNull(WorkspaceConnectedGitHubRunIntent.decide(
            "GitHub repo me build label fix karo"
        ))
        assertNull(WorkspaceConnectedGitHubRunIntent.decide(
            "https://github.com/spy626/myra-android check karo"
        ))
    }

    @Test fun receiptSeparatesCiFromPhonePassAndStatesReadOnly() {
        val receipt = WorkspaceConnectedGitHubRunIntent.receipt(
            WorkspaceConnectedGitHubRunRunner.Completion(
                repository = "spy626/myra-android",
                branch = "agent/myra-phase-1",
                run = WorkspaceGitHubConnector.WorkflowRun(
                    id = 3L,
                    runNumber = 3324L,
                    name = "Build Android APK",
                    headSha = "1234567890abcdef1234567890abcdef12345678",
                    status = "completed",
                    conclusion = "success",
                    url = "https://github.com/spy626/myra-android/actions/runs/3",
                ),
            )
        )
        assertTrue(receipt.contains("#3324 is GREEN"))
        assertTrue(receipt.contains("no repository change was made"))
        assertTrue(receipt.contains("does not prove physical phone-pass"))
    }
}
