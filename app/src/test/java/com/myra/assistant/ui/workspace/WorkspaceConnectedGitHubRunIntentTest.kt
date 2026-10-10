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

    @Test fun latestBuildLinkRoutesToConnectedLatestReadWithoutMutation() {
        val decision = WorkspaceConnectedGitHubRunIntent.decide(
            "Latest GitHub build ka link do"
        )
        assertTrue(decision?.latest == true)
        assertNull(decision?.runNumber)
        assertNull(decision?.localError)
        assertTrue(!WorkspaceGitHubSelfEdit.isExplicitRequest("Latest GitHub build ka link do"))

        val withSha = WorkspaceConnectedGitHubRunIntent.decide(
            "Current GitHub build ka link aur commit SHA bhi batao"
        )
        assertTrue(withSha?.latest == true)
        assertTrue(withSha?.includeCommitSha == true)
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
            ),
            includeCommitSha = true,
        )
        assertTrue(receipt.contains("Haan bro 😂💚 **Build #3324 GREEN hai!**"))
        assertTrue(receipt.contains("  • **Status:** Completed ✅"))
        assertTrue(receipt.contains("  • **Result:** Success"))
        assertTrue(receipt.contains("  • **Branch:** `agent/myra-phase-1`"))
        assertTrue(receipt.contains("Commit SHA:\n`1234567890abcdef1234567890abcdef12345678`"))
        assertTrue(receipt.contains("[🔗 GitHub Build #3324 ↗](https://github.com/spy626/myra-android/actions/runs/3)"))
        assertTrue(!receipt.contains("phone-pass"))
        assertTrue(!receipt.contains("Verified read only"))
    }

    @Test fun shortHumanQuestionDoesNotDumpShaButExplicitRequestDoes() {
        val run = WorkspaceConnectedGitHubRunRunner.Completion(
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            run = WorkspaceGitHubConnector.WorkflowRun(
                id = 36841743155L,
                runNumber = 3376L,
                name = "Build Android APK",
                headSha = "e66f891b6ebc73bca7dd74349d209733783adb14",
                status = "completed",
                conclusion = "success",
                url = "https://github.com/spy626/myra-android/actions/runs/36841743155",
            ),
        )
        val natural = WorkspaceConnectedGitHubRunIntent.decide("bro 3376 green hai kya check kro")
        assertTrue(natural != null && !natural.includeCommitSha)
        val text = WorkspaceConnectedGitHubRunIntent.receipt(run, includeCommitSha = natural!!.includeCommitSha)
        assertTrue(text.startsWith("Haan bro 😂💚 **Build #3376 GREEN hai!**"))
        assertTrue(text.contains("  • **Result:** Success"))
        assertTrue(!text.contains("e66f891b6ebc73bca7dd74349d209733783adb14"))
        assertTrue(!text.contains("Details: https://"))
        assertTrue(text.contains("[🔗 GitHub Build #3376 ↗](https://github.com/"))
        val requested = WorkspaceConnectedGitHubRunIntent.decide("Bro #3376 status aur uska commit SHA bhi bata do")
        assertTrue(requested?.includeCommitSha == true)
        assertTrue(WorkspaceConnectedGitHubRunIntent.receipt(run, requested!!.includeCommitSha).contains(
            "Commit SHA:\n`e66f891b6ebc73bca7dd74349d209733783adb14`"
        ))
    }

    @Test fun failedAndRunningBuildsDoNotClaimGreen() {
        fun report(status: String, conclusion: String?): String {
            val value = WorkspaceConnectedGitHubRunRunner.Completion(
                repository = "spy626/myra-android",
                branch = "agent/myra-phase-1",
                run = WorkspaceGitHubConnector.WorkflowRun(
                    id = 42L, runNumber = 3376L, name = "Build Android APK",
                    headSha = "1234567890abcdef1234567890abcdef12345678",
                    status = status, conclusion = conclusion,
                    url = "https://github.com/spy626/myra-android/actions/runs/42",
                )
            )
            return WorkspaceConnectedGitHubRunIntent.receipt(value)
        }
        val failed = report("completed", "failure")
        assertTrue(failed.contains("Build #3376 FAILED hai"))
        assertTrue(failed.contains("  • **Result:** Failure"))
        assertTrue(!failed.contains("GREEN"))
        val running = report("in_progress", null)
        assertTrue(running.contains("Build #3376 abhi IN PROGRESS hai"))
        assertTrue(running.contains("  • **Result:** Pending"))
        assertTrue(!running.contains("GREEN"))
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
