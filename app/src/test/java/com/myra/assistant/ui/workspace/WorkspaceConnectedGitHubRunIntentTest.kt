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

    @Test fun normalBuildReceiptIsShortHinglishWithoutUnrequestedDeveloperDisclaimer() {
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
        assertTrue(receipt.contains("Bro, build #3324 ka status **GREEN** hai ✅"))
        assertTrue(receipt.contains("Commit SHA: `1234567890abcdef1234567890abcdef12345678`"))
        assertTrue(receipt.contains("https://github.com/spy626/myra-android/actions/runs/3"))
        assertTrue(!receipt.contains("phone-pass"))
        assertTrue(!receipt.contains("Verified read only"))
    }

    @Test fun naturalHinglishBuildStatusWorksWithoutToolNames() {
        listOf(
            "bro 3372 green hai kya check kro",
            "bro #3372 green hai kya check kro",
            "3372 green hai?",
            "bro #3372 build ka abhi ka status check karke bata. Green hai ya fail? " +
                "Uska commit SHA bhi bata dena. Kuch change mat karna, naya build bhi mat chalana.",
            "Show me Build #3372 status and its commit SHA. Do not edit files, push or build.",
            "Bhai run 3372 fail hua kya dekho",
        ).forEach { userText ->
            val decision = WorkspaceConnectedGitHubRunIntent.decide(userText)
            assertEquals(userText, 3372L, decision?.runNumber)
            assertNull(userText, decision?.localError)
            assertTrue(userText, !WorkspaceGitHubSelfEdit.isExplicitRequest(userText))
        }
    }

    @Test fun negatedBuildNumberIsNotMistakenForRequestedRun() {
        assertNull(WorkspaceConnectedGitHubRunIntent.decide(
            "Show the LIVE branch HEAD SHA; do not start build #3372."
        ))
        assertNull(WorkspaceConnectedGitHubRunIntent.decide(
            "Check current GitHub feature branch SHA. Do not start build #3372 or edit files."
        ))
        assertNull(WorkspaceConnectedGitHubRunIntent.decide(
            "GitHub repo me button fix karo. Do not start build #3372."
        ))
        assertNull(WorkspaceConnectedGitHubRunIntent.decide(
            "bro PR #3372 status check kro"
        ))
    }
}
