package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceStoryScriptTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("test", role, text, 1L)

    private fun instructions(prompt: String): String =
        JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message("user", prompt))))
            .getJSONArray("messages").getJSONObject(0).getString("content")

    @Test fun videoStoryStaysProseStoryNotVoiceOverOrSceneScript() {
        val prompt = "Mujhe meri video ko liya ek horror story do"
        assertEquals(WorkspaceStoryScript.Kind.STORY, WorkspaceStoryScript.kind(prompt))
        val entries = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message("user", prompt))))
            .getJSONArray("messages")
        assertEquals("system", entries.getJSONObject(0).getString("role"))
        val instruction = entries.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("STORY: Start the complete STORY"))
        assertTrue(instruction.contains("A STORY for a video is still a STORY"))
        assertTrue(instruction.contains("Do NOT include [VOICEOVER], [SCENE]"))
        assertTrue(instruction.contains("VIDEO TIP:"))
        assertEquals(prompt, entries.getJSONObject(1).getString("content"))
        assertEquals(WorkspaceStoryScript.Kind.STORY,
            WorkspaceStoryScript.kind("Mujhe meri video ke liye ek lambi suspense aur horror story do jisme ending unexpected ho"))
    }

    @Test fun explicitlyRequestedVoiceOverAndSceneScriptHaveSeparateInstructions() {
        val voiceOver = "Meri video ke liye horror voice-over likho"
        assertEquals(WorkspaceStoryScript.Kind.VOICE_OVER, WorkspaceStoryScript.kind(voiceOver))
        assertTrue(instructions(voiceOver).contains("VOICEOVER: On the next line"))
        assertFalse(instructions(voiceOver).contains("STORY: Start the complete STORY"))
        val script = "Mujhe horror video script likho"
        assertEquals(WorkspaceStoryScript.Kind.SCENE_SCRIPT, WorkspaceStoryScript.kind(script))
        assertTrue(instructions(script).contains("SCRIPT: On the next line"))
        assertFalse(instructions(script).contains("VOICEOVER: On the next line"))
        assertEquals(WorkspaceStoryScript.Kind.STORY,
            WorkspaceStoryScript.kind("Horror kahani likho, video ke liye"))
        assertEquals(WorkspaceStoryScript.Kind.STORY,
            WorkspaceStoryScript.kind("मेरी वीडियो के लिए डरावनी कहानी लिखो"))
    }

    @Test fun normalChatAndTechnicalScriptNeverGetWritingInstructions() {
        val plain = JSONObject(WorkspaceChatGateway.openRouterBody(
            listOf(message("user", "Kese ho bro?")))).getJSONArray("messages")
        assertEquals(2, plain.length())
        assertEquals("system", plain.getJSONObject(0).getString("role"))
        assertEquals("user", plain.getJSONObject(1).getString("role"))
        assertFalse(WorkspaceStoryScript.isWritingRequest("Explain the meaning of a screenplay"))
        assertFalse(WorkspaceStoryScript.isWritingRequest("Write a Python script to automate my files"))
        assertNull(WorkspaceStoryScript.kind("Write a Python script to automate my files"))
    }

    @Test fun structuredVideoStoryKeepsProseIntroTitleAndTipSeparateFromCopy() {
        val reply = "INTRO: Bhai, yeh chhoti horror kahani hai.\n" +
            "TITLE: 3:17 AM — Mera Hi Phone\n" +
            "STORY:\nRaat ke 3:17 baj rahe the.\n\n**Darwaza khula.**\n\nThe end.\n" +
            "VIDEO TIP: Aakhri line par music band karke 2 second silence rakho."
        val card = requireNotNull(WorkspaceStoryScript.card("Meri video ke liye horror story do", reply))
        assertEquals("Bhai, yeh chhoti horror kahani hai.", card.intro)
        assertEquals("3:17 AM — Mera Hi Phone", card.title)
        assertEquals("Raat ke 3:17 baj rahe the.\n\nDarwaza khula.\n\nThe end.", card.copyText)
        assertFalse(card.copyText.contains("INTRO:"))
        assertFalse(card.copyText.contains("VIDEO TIP:"))
        assertFalse(card.copyText.contains("Aakhri line"))
        assertEquals("Aakhri line par music band karke 2 second silence rakho.", card.tip)
    }

    @Test fun voiceOverAndLegacyScriptMarkersAreStillRenderedWithoutChangingContent() {
        val voice = requireNotNull(WorkspaceStoryScript.card("Voice-over likho", 
            "INTRO: Yeh narration hai.\nTITLE: Darwaza\nVOICEOVER:\nKoi tha.\n\nWoh paas aaya."))
        assertEquals("Koi tha.\n\nWoh paas aaya.", voice.copyText)
        val old = requireNotNull(WorkspaceStoryScript.card("Write a short story",
            "INTRO: Ek kahani.\nTITLE: Door\nSCRIPT:\nKnock.\n\nThe end."))
        assertEquals("Door", old.title)
        assertEquals("Knock.\n\nThe end.", old.copyText)
        val plain = requireNotNull(WorkspaceStoryScript.card("Write a story",
            "INTRO: A short tale.\nTITLE: Door\nSTORY:\nKnock.\nVIDEO TIP: Record at night."))
        assertEquals("Knock.\nVIDEO TIP: Record at night.", plain.copyText)
        assertNull(plain.tip)
    }

    @Test fun unstructuredModelOutputIsNotSilentlyRewritten() {
        val card = requireNotNull(WorkspaceStoryScript.card("Write a story", 
            "# The Night Train\n\n**Scene 1**\nHello.\n\nThe end."))
        assertEquals("The Night Train", card.title)
        assertEquals("Scene 1\nHello.\n\nThe end.", card.copyText)
        assertTrue(card.body.startsWith("**Scene 1**"))
        val fallback = requireNotNull(WorkspaceStoryScript.card("Meri video ke liye story do",
            "[VOICEOVER]\nKoi darwaze par tha."))
        assertTrue(fallback.copyText.contains("[VOICEOVER]"))
        assertFalse(fallback.intro.contains("voice-over", ignoreCase = true))
        assertNull(WorkspaceStoryScript.card("Hello", "# The Night Train\nStory"))
    }
}
