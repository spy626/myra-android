package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceStoryScriptTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("test", role, text, 1L)

    @Test fun writingPromptIncludesIntroScriptAndVideoTipWithoutChangingNormalChat() {
        val story = JSONObject(WorkspaceChatGateway.openRouterBody(
            listOf(message("user", "Mujhe meri video ke liye ek horror kahani sunao"))))
        val entries = story.getJSONArray("messages")
        assertEquals("system", entries.getJSONObject(0).getString("role"))
        val instructions = entries.getJSONObject(0).getString("content")
        assertTrue(instructions.contains("INTRO:"))
        assertTrue(instructions.contains("TITLE:"))
        assertTrue(instructions.contains("SCRIPT:"))
        assertTrue(instructions.contains("VIDEO TIP:"))
        assertEquals("Mujhe meri video ke liye ek horror kahani sunao", entries.getJSONObject(1).getString("content"))
        val plain = JSONObject(WorkspaceChatGateway.openRouterBody(
            listOf(message("user", "Kese ho bro?")))).getJSONArray("messages")
        assertEquals(1, plain.length())
        assertEquals("user", plain.getJSONObject(0).getString("role"))
        assertFalse(WorkspaceStoryScript.isWritingRequest("Explain the meaning of a screenplay"))
        assertFalse(WorkspaceStoryScript.isWritingRequest("Write a Python script to automate my files"))
    }

    @Test fun structuredVideoWritingKeepsIntroTitleTipOutsideOnlyScriptCopied() {
        val reply = "INTRO: Bhai, yeh 60-second ki horror story hai.\n" +
            "TITLE: 3:17 AM — Mera Hi Phone\n" +
            "SCRIPT:\nRaat ke 3:17 baj rahe the.\n\n**Darwaza khula.**\n\nThe end.\n" +
            "VIDEO TIP: Aakhri line par music band karke 2 second silence rakho."
        val card = requireNotNull(WorkspaceStoryScript.card("Mujhe meri video ke liye horror story do", reply))
        assertEquals("Bhai, yeh 60-second ki horror story hai.", card.intro)
        assertEquals("3:17 AM — Mera Hi Phone", card.title)
        assertEquals("Raat ke 3:17 baj rahe the.\n\nDarwaza khula.\n\nThe end.", card.copyText)
        assertFalse(card.copyText.contains("INTRO:"))
        assertFalse(card.copyText.contains("VIDEO TIP:"))
        assertFalse(card.copyText.contains("Aakhri line"))
        assertEquals("Aakhri line par music band karke 2 second silence rakho.", card.tip)
    }

    @Test fun oldStyleTitleAndUntitledStoryArePreservedWhenModelIgnoresSections() {
        val card = requireNotNull(WorkspaceStoryScript.card("Write a short story", "# The Night Train\n\n**Scene 1**\nHello.\n\nThe end."))
        assertEquals("The Night Train", card.title)
        assertEquals("Scene 1\nHello.\n\nThe end.", card.copyText)
        assertTrue(card.body.startsWith("**Scene 1**"))
        assertNull(card.tip)
        val untitled = requireNotNull(WorkspaceStoryScript.card("Ek kahani sunao", "Once upon a time\nA door opened."))
        assertEquals("Story / Script", untitled.title)
        assertEquals("Once upon a time\nA door opened.", untitled.copyText)
        assertEquals(null, WorkspaceStoryScript.card("Hello", "# The Night Train\nStory"))
    }

    @Test fun legacyVideoStoryDoesNotInventSpecificTipAndPlainStoryHasNoVideoTip() {
        val old = requireNotNull(WorkspaceStoryScript.card("Give me a video script", "Voice-over: **Hello**\n\n[Pause] Hi!"))
        assertEquals("Voice-over: Hello\n\n[Pause] Hi!", old.copyText)
        assertEquals("Story / Script", old.title)
        assertNull(old.tip)
        val plain = requireNotNull(WorkspaceStoryScript.card("Write a story", "INTRO: A short tale.\nTITLE: Door\nSCRIPT:\nKnock.\nVIDEO TIP: Record at night."))
        assertEquals("Knock.\nVIDEO TIP: Record at night.", plain.copyText)
        assertNull(plain.tip)
    }
}
