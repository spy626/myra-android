package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConnectedGitHubHeadsIntentTest {
    private val phonePrompt =
        "LYRA, use your connected GitHub read capability to fetch the LIVE current HEAD commit " +
        "SHA of agent/myra-phase-1 and main in spy626/myra-android. " +
        "Do not guess from conversation history. Do not modify files, push commits, or start a build. " +
        "If the live read fails, clearly report the failure."

    @Test fun exactFailedPhonePromptRequestsTwoLiveReadsNeverWrite() {
        val value = requireNotNull(WorkspaceConnectedGitHubHeadsIntent.decide(phonePrompt))
        assertTrue(value.feature)
        assertTrue(value.main)
        assertEquals("agent/myra-phase-1", value.namedFeatureBranch)
        assertEquals("spy626/myra-android", value.namedRepository)
        assertFalse(WorkspaceGitHubSelfEdit.isExplicitRequest(phonePrompt))
        assertEquals(WorkspaceSemanticTurnIntent.Effect.READ,
            WorkspaceSemanticTurnIntent.propose(phonePrompt).effect)
    }

    @Test fun mainInNegativeConstraintIsNotAnotherRequestedBranch() {
        val value = requireNotNull(WorkspaceConnectedGitHubHeadsIntent.decide(
            "Fetch LIVE HEAD SHA of connected GitHub feature branch. Do not edit main, push, or build."
        ))
        assertTrue(value.feature)
        assertFalse(value.main)
        val base = requireNotNull(WorkspaceConnectedGitHubHeadsIntent.decide(
            "Show live GitHub HEAD commit SHA of main. No changes."
        ))
        assertFalse(base.feature)
        assertTrue(base.main)
    }

    @Test fun noImplicitSelfEditAndUnsupportedIntentsStayOutOfBranchRead() {
        listOf(
            "GitHub repo me button fix karo",
            "Can you read my GitHub?",
            "https://github.com/spy626/myra-android",
            "GitHub branch ka name kya hai?",
            "Show me the GitHub Actions build status",
            "Build my Android app from GitHub repo",
        ).forEach {
            assertNull(it, WorkspaceConnectedGitHubHeadsIntent.decide(it))
        }
    }

    @Test fun receiptShowsVerifiedFullShaWithoutRepeatedDeveloperDisclaimer() {
        val f = "a".repeat(40)
        val m = "b".repeat(40)
        val message = WorkspaceConnectedGitHubHeadsIntent.receipt(
            WorkspaceConnectedGitHubHeadsRunner.Completion(
                repository = "spy626/myra-android",
                branches = listOf(
                    WorkspaceGitHubConnector.Branch("agent/myra-phase-1", f),
                    WorkspaceGitHubConnector.Branch("main", m),
                ),
            )
        )
        assertTrue(message.contains("agent/myra-phase-1: $f"))
        assertTrue(message.contains("main: $m"))
        assertTrue(message.contains("Bro, current branch HEADs check ho gaye"))
        assertTrue(!message.contains("phone-pass"))
        assertTrue(!message.contains("commit/push/build"))
        assertTrue(!message.contains("GitHub branch GET"))
    }
}
