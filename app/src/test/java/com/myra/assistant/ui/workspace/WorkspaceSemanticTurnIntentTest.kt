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
}
