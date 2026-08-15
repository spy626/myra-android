package com.myra.assistant.voice

import com.myra.assistant.commands.Command
import com.myra.assistant.commands.CommandType
import com.myra.assistant.core.AssistantResult
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceResponseFormatterTest {
    @Test fun formatsHumanAppConfirmation() {
        val command = Command(CommandType.OPEN_APP, "YouTube", sourceText = "YouTube open karo")
        val result = AssistantResult(true, false, "OPEN_APP", "YouTube", "Opening YouTube")
        assertEquals("Zopy, YouTube khol rahi hoon.", VoiceResponseFormatter.format(command, result))
    }
    @Test fun neverRewritesFailureAsSuccess() {
        val command = Command(CommandType.OPEN_APP, "YouTube", sourceText = "open")
        val result = AssistantResult(false, false, "OPEN_APP", "YouTube", "YouTube nahi mila.")
        assertEquals("YouTube nahi mila.", VoiceResponseFormatter.format(command, result))
    }
    @Test fun formatsCompletedYouTubeSearchNaturally() {
        val command = Command(CommandType.SEARCH_YOUTUBE, "YouTube", "jonathan gaming", "search", null)
        val result = AssistantResult(true, false, "SEARCH_YOUTUBE", "YouTube", "accepted")
        assertEquals(
            "Done Zopy, YouTube par Jonathan Gaming search kar diya. Aur kuch karun?",
            VoiceResponseFormatter.format(command, result)
        )
    }
    @Test fun formatsHumanFlashlightResponses() {
        val on = Command(CommandType.FLASHLIGHT_ON, sourceText = "torch on")
        val off = Command(CommandType.FLASHLIGHT_OFF, sourceText = "torch off")
        val onResult = AssistantResult(true, true, "FLASHLIGHT_ON", spokenMessage = "on")
        val offResult = AssistantResult(true, true, "FLASHLIGHT_OFF", spokenMessage = "off")
        assertEquals("Flashlight on kar diya. Aur kuch karun?", VoiceResponseFormatter.format(on, onResult))
        assertEquals("Flashlight off kar diya. Aur kuch chahiye aapko?", VoiceResponseFormatter.format(off, offResult))
    }
    @Test fun closeResponseDoesNotClaimForceStopOrVerification() {
        val command = Command(CommandType.CLOSE_APP, "YouTube", sourceText = "YouTube close karo")
        val result = AssistantResult(true, false, "CLOSE_APP", "YouTube", "accepted")
        assertEquals("Theek hai Zopy, YouTube se bahar aa rahi hoon.", VoiceResponseFormatter.format(command, result))
    }
}
