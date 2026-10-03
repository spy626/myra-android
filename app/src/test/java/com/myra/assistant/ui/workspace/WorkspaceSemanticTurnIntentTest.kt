package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSemanticTurnIntentTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("id", role, text, 1L)

    @Test fun hypotheticalAndAccessQuestionsAreCapabilityOnly() {
        listOf(
            "Agar main feature add karne bolu to tum kar sakti ho kya?",
            "Bro GitHub repo me feature add kar sakti ho kya?",
            "Do you have GitHub access?",
            "GitHub access hai na?",
        ).forEach { text ->
            val proposal = WorkspaceSemanticTurnIntent.propose(text)
            assertEquals(text, WorkspaceSemanticTurnIntent.Kind.CAPABILITY_QUERY, proposal.kind)
            assertEquals(text, WorkspaceSemanticTurnIntent.Effect.NONE, proposal.effect)
            assertFalse(text, WorkspaceGitHubSelfEdit.isExplicitRequest(text))
            assertFalse(text, WorkspaceChatIntent.isCodingFollowUp(text))
        }
    }

    @Test fun directBuildAndEditRequestsStillExecute() {
        val website = "Can you build me a website?"
        assertEquals(
            WorkspaceSemanticTurnIntent.Kind.ACTION_REQUEST,
            WorkspaceSemanticTurnIntent.propose(website).kind,
        )
        assertEquals(
            WorkspaceProjectType.WEBSITE,
            WorkspaceChatIntent.requestedProjectType(website),
        )

        val github = "GitHub repo me runtime self model ka label fix karo"
        assertEquals(
            WorkspaceSemanticTurnIntent.Effect.WRITE,
            WorkspaceSemanticTurnIntent.propose(github).effect,
        )
        assertTrue(WorkspaceGitHubSelfEdit.isExplicitRequest(github))
    }

    @Test fun buildStatusWithExplicitNoMutationIsReadOnly() {
        val text = "3324 dekho kuch change mat karna green hai kya?"
        val proposal = WorkspaceSemanticTurnIntent.propose(text)
        assertEquals(WorkspaceSemanticTurnIntent.Kind.READ_ONLY_VERIFICATION, proposal.kind)
        assertEquals(WorkspaceSemanticTurnIntent.Effect.READ, proposal.effect)
        assertFalse(WorkspaceGitHubSelfEdit.isExplicitRequest(text))
        assertNull(WorkspaceChatIntent.requestedProjectType(text))
    }

    @Test fun normalChatReceivesTypedProposalWithoutRewritingUserTurn() {
        val text = "Bro GitHub repo me feature add kar sakti ho kya?"
        val payload = WorkspaceChatGateway.openAiMessages(listOf(message("user", text)))
        val system = payload.getJSONObject(0).getString("content")

        assertTrue(system.contains("TURN INTENT PROPOSAL"))
        assertTrue(system.contains("CAPABILITY_QUERY"))
        assertTrue(system.contains("requested effect: NONE"))
        assertEquals(text, payload.getJSONObject(payload.length() - 1).getString("content"))
    }

    @Test fun naturalRecentActionReferencesAreTypedWithoutExecuting() {
        listOf(
            "uska CI?",
            "same commit green tha?",
            "last change kis task ke liye tha?",
        ).forEach { text ->
            val proposal = WorkspaceSemanticTurnIntent.propose(text)
            assertEquals(text, WorkspaceSemanticTurnIntent.Kind.FOLLOW_UP_REFERENCE, proposal.kind)
            assertEquals(text, WorkspaceSemanticTurnIntent.Effect.NONE, proposal.effect)
            assertFalse(text, WorkspaceGitHubSelfEdit.isExplicitRequest(text))
            assertFalse(text, WorkspaceChatIntent.isCodingFollowUp(text))
        }
    }

    @Test fun specificSourcePurposeRequiresSourceProvenanceInsteadOfRecentReceiptGuess() {
        val text = "ye comment kisliye hai?"
        val proposal = WorkspaceSemanticTurnIntent.propose(text)

        assertEquals(WorkspaceSemanticTurnIntent.Kind.SOURCE_PROVENANCE_QUERY, proposal.kind)
        assertEquals(WorkspaceSemanticTurnIntent.Effect.READ, proposal.effect)
        assertFalse(WorkspaceGitHubSelfEdit.isExplicitRequest(text))
    }

    @Test fun explicitCommentWriteStillRemainsActionRequest() {
        val text = "GitHub repo me is comment ko update karo"
        val proposal = WorkspaceSemanticTurnIntent.propose(text)

        assertEquals(WorkspaceSemanticTurnIntent.Kind.ACTION_REQUEST, proposal.kind)
        assertEquals(WorkspaceSemanticTurnIntent.Effect.WRITE, proposal.effect)
        assertTrue(WorkspaceGitHubSelfEdit.isExplicitRequest(text))
    }

    @Test fun followUpReferencePromptCarriesUniqueCandidateRules() {
        val text = "uska CI?"
        val payload = WorkspaceChatGateway.openAiMessages(listOf(message("user", text)))
        val system = payload.getJSONObject(0).getString("content")

        assertTrue(system.contains("FOLLOW_UP_REFERENCE"))
        assertTrue(system.contains("RECENT_VERIFIED_GITHUB_ACTION"))
        assertTrue(system.contains("globally newest external action"))
    }

    @Test fun liveHeadReadWithNegativeActionListCannotBecomeSelfEdit() {
        val exact = "LYRA, use your connected GitHub read capability to fetch the LIVE current HEAD " +
            "commit SHA of agent/myra-phase-1 and main in spy626/myra-android. " +
            "Do not guess from conversation history. Do not modify files, push commits, or start a build. " +
            "If the live read fails, clearly report the failure."
        for (text in listOf(
            exact,
            "Fetch LIVE current GitHub HEAD SHA for agent/myra-phase-1. No edits or pushes.",
            "Show me the connected GitHub branch HEAD read-only. Do not change code or build.",
            "GitHub ki current branch SHA check karo; code change mat karna, build bhi mat karna.",
        )) {
            val proposal = WorkspaceSemanticTurnIntent.propose(text)
            assertEquals(text, WorkspaceSemanticTurnIntent.Kind.READ_ONLY_VERIFICATION, proposal.kind)
            assertEquals(text, WorkspaceSemanticTurnIntent.Effect.READ, proposal.effect)
            assertFalse(text, WorkspaceGitHubSelfEdit.isExplicitRequest(text))
            assertNull(text, WorkspaceChatIntent.requestedProjectType(text))
        }
        assertEquals(WorkspaceSemanticTurnIntent.Effect.WRITE,
            WorkspaceSemanticTurnIntent.propose(
                "GitHub repo me runtime label fix karo, but don't change main"
            ).effect)
    }
}
