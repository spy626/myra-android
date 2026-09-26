package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspacePromptFollowUpTest {
    private fun user(text: String) = WorkspaceConversationStore.Message("u", "user", text, 1L)
    private fun assistant(text: String) = WorkspaceConversationStore.Message("a", "assistant", text, 2L)
    private val original = user("Mujhe Android AI companion banane ke liye Codex ko prompt do")
    private val firstReply = assistant(
        "INTRO: Ye coding AI ke liye prompt hai.\nTITLE: Android Companion\nPROMPT:\n" +
            "Build an Android companion chat app with a voice option.\n" +
            "NEXT STEP: Coding AI ko paste karo."
    )
    private val followUp = user("Isme hands-free hona chahiye; main YouTube kholo bolun toh open ho")

    @Test fun revisesImmediatelyPrecedingPromptInsteadOfStartingNewPlan() {
        val messages = listOf(original, firstReply, followUp)
        assertNull(WorkspacePromptWriting.kind(followUp.text))
        assertEquals(WorkspacePromptWriting.Kind.BUILD, WorkspacePromptFollowUp.kind(messages))
        val sent = JSONObject(WorkspaceChatGateway.openRouterBody(messages)).getJSONArray("messages")
        assertEquals(4, sent.length())
        val instructions = sent.getJSONObject(0).getString("content")
        assertTrue(instructions.contains("REVISION of the directly preceding"))
        assertTrue(instructions.contains("ONE complete updated"))
        assertTrue(instructions.contains("previous assistant speculation is not a new"))
        assertTrue(instructions.contains("Do not silently add always-listening"))
        assertTrue(instructions.contains("PROMPT:"))
        assertEquals(firstReply.text, sent.getJSONObject(2).getString("content"))
        assertEquals(followUp.text, sent.getJSONObject(3).getString("content"))
        assertNull(WorkspaceChatIntent.requestedProjectType(
            "Create a development prompt to build an Android app"))
        assertEquals(WorkspaceProjectType.ANDROID_APP,
            WorkspaceChatIntent.requestedProjectType("Create an Android app"))
    }

    @Test fun followUpUsesSameCopyableWritingCardAndCopiesOnlyUpdatedPrompt() {
        val answer = "INTRO: Updated prompt mein hands-free add hai.\n" +
            "TITLE: Android Companion with Hands-free\n" +
            "PROMPT:\nBuild the companion chat app.\n" +
            "Add a user-triggered voice command to open YouTube.\n" +
            "NEXT STEP: Is updated prompt ko Codex mein paste karo."
        val card = requireNotNull(WorkspaceStoryScript.card(followUp.text, answer))
        assertTrue(card.promptCard)
        assertEquals("Android Companion with Hands-free", card.title)
        assertEquals("Updated prompt mein hands-free add hai.", card.intro)
        assertEquals("Build the companion chat app.\nAdd a user-triggered voice command to open YouTube.",
            card.copyText)
        assertEquals(card.body, card.copyText)
        assertFalse(card.copyText.contains("NEXT STEP"))
        assertFalse(card.copyText.contains("TITLE:"))
        assertEquals("Is updated prompt ko Codex mein paste karo.", card.tip)
    }

    @Test fun successiveFollowUpsKeepTheSamePromptArtifact() {
        val revised = assistant("INTRO: Updated.\nTITLE: Companion\nPROMPT:\nBuild chat and hands-free voice.\nNEXT STEP: Paste it.")
        val second = user("Usme voice command band karne ka option bhi add karo")
        assertEquals(WorkspacePromptWriting.Kind.BUILD,
            WorkspacePromptFollowUp.kind(listOf(original, firstReply, followUp, revised, second)))
        val instructions = JSONObject(WorkspaceChatGateway.openRouterBody(
            listOf(original, firstReply, followUp, revised, second)))
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(instructions.contains("directly preceding copyable development prompt"))
    }

    @Test fun unrelatedRepliesAndNewTopicsNeverBecomePromptRevisions() {
        assertNull(WorkspacePromptFollowUp.kind(listOf(original, firstReply, user("Hi bro"))))
        assertNull(WorkspaceStoryScript.card("Hi bro", firstReply.text))
        val switched = listOf(original, firstReply, user("Kese ho bro"), assistant("Theek hun"), followUp)
        assertNull(WorkspacePromptFollowUp.kind(switched))
        val sent = JSONObject(WorkspaceChatGateway.openRouterBody(switched)).getJSONArray("messages")
        assertEquals("system", sent.getJSONObject(0).getString("role"))
        assertEquals("user", sent.getJSONObject(1).getString("role"))
        assertNull(WorkspacePromptFollowUp.kind(listOf(original, assistant("Plain conversation reply"),
            user("Isme ek aur feature add karo"), assistant("Okay"), followUp)))
        assertNull(WorkspacePromptFollowUp.kind(listOf(user("Write a Python prompt"),
            assistant("PROMPT:\nprint('hello')"), user("Now explain this code"))))
    }

    @Test fun partialStructuredFollowUpKeepsRealPromptBodyWithoutInventingContent() {
        val answer = "INTRO: Chhota update.\nPROMPT:\nBuild chat with opt-in voice.\nNEXT STEP: Paste."
        val card = requireNotNull(WorkspaceStoryScript.card(followUp.text, answer))
        assertEquals("Updated prompt", card.title)
        assertEquals("Build chat with opt-in voice.", card.copyText)
        assertEquals("Paste.", card.tip)
    }
}
