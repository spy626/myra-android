package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceStoryScriptTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("test", role, text, 1L)

    @Test fun addsWritingInstructionsOnlyForCreativeRequests() {
        val story = JSONObject(WorkspaceChatGateway.openRouterBody(
            listOf(message("user", "Mujhe ek horror kahani sunao"))))
        val entries = story.getJSONArray("messages")
        assertEquals("system", entries.getJSONObject(0).getString("role"))
        assertTrue(entries.getJSONObject(0).getString("content").contains("no preface", ignoreCase = true))
        assertEquals("Mujhe ek horror kahani sunao", entries.getJSONObject(1).getString("content"))
        val plain = JSONObject(WorkspaceChatGateway.openRouterBody(
            listOf(message("user", "Kese ho bro?")))).getJSONArray("messages")
        assertEquals(1, plain.length())
        assertEquals("user", plain.getJSONObject(0).getString("role"))
        assertFalse(WorkspaceStoryScript.isWritingRequest("Explain the meaning of a screenplay"))
        assertFalse(WorkspaceStoryScript.isWritingRequest("Write a Python script to automate my files"))
    }

    @Test fun onlyRecognizedTitleIsRemovedAndCopyIsClean() {
        val card = requireNotNull(WorkspaceStoryScript.card("Write a short story", "# The Night Train\n\n**Scene 1**\nHello.\n\nThe end."))
        assertEquals("The Night Train", card.title)
        assertEquals("Scene 1\nHello.\n\nThe end.", card.copyText)
        assertTrue(card.body.startsWith("**Scene 1**"))
        val untitled = requireNotNull(WorkspaceStoryScript.card("Ek kahani sunao", "Once upon a time\nA door opened."))
        assertEquals("Story / Script", untitled.title)
        assertEquals("Once upon a time\nA door opened.", untitled.copyText)
        assertEquals(null, WorkspaceStoryScript.card("Hello", "# The Night Train\nStory"))
    }

    @Test fun quotedSpeechAndUntitledParagraphsAreNotDiscarded() {
        val card = requireNotNull(WorkspaceStoryScript.card("Give me a video script", "Voice-over: **Hello**\n\n[Pause] Hi!"))
        assertEquals("Voice-over: Hello\n\n[Pause] Hi!", card.copyText)
        assertEquals("Story / Script", card.title)
    }
}
