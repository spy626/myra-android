package com.myra.assistant.voice

import com.myra.assistant.commands.Command
import com.myra.assistant.commands.CommandType
import com.myra.assistant.core.AssistantResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test fun gfOpenResponsesChangeWithoutRepeating() {
        val command = Command(CommandType.OPEN_APP, "YouTube", sourceText = "YouTube open karo")
        val result = AssistantResult(true, false, "OPEN_APP", "YouTube", "accepted")

        val first = VoiceResponseFormatter.format(command, result, personality = "GF")
        val second = VoiceResponseFormatter.format(command, result, personality = "GF")

        assertNotEquals(first, second)
        assertTrue(first.contains("YouTube"))
        assertTrue(first.contains("open", ignoreCase = true) || first.contains("khol", ignoreCase = true))
    }

    @Test fun gfFlashlightResponsesChangeWithoutRepeating() {
        val on = Command(CommandType.FLASHLIGHT_ON, sourceText = "torch on")
        val off = Command(CommandType.FLASHLIGHT_OFF, sourceText = "torch off")
        val onResult = AssistantResult(true, true, "FLASHLIGHT_ON", spokenMessage = "on")
        val offResult = AssistantResult(true, true, "FLASHLIGHT_OFF", spokenMessage = "off")

        val firstOn = VoiceResponseFormatter.format(on, onResult, personality = "GF")
        val secondOn = VoiceResponseFormatter.format(on, onResult, personality = "GF")
        val firstOff = VoiceResponseFormatter.format(off, offResult, personality = "GF")
        val secondOff = VoiceResponseFormatter.format(off, offResult, personality = "GF")

        assertNotEquals(firstOn, secondOn)
        assertNotEquals(firstOff, secondOff)
        assertTrue(firstOn.contains("flashlight", ignoreCase = true) || firstOn.contains("roshni", ignoreCase = true))
        assertTrue(firstOff.contains("flashlight", ignoreCase = true) || firstOff.contains("roshni", ignoreCase = true))
    }

    @Test fun assistantFlashlightResponsesStayNeutral() {
        val on = Command(CommandType.FLASHLIGHT_ON, sourceText = "torch on")
        val off = Command(CommandType.FLASHLIGHT_OFF, sourceText = "torch off")
        val onResult = AssistantResult(true, true, "FLASHLIGHT_ON", spokenMessage = "on")
        val offResult = AssistantResult(true, true, "FLASHLIGHT_OFF", spokenMessage = "off")
        assertEquals(
            "Flashlight on kar diya. Aur kuch karun?",
            VoiceResponseFormatter.format(on, onResult, personality = "Assistant")
        )
        assertEquals(
            "Flashlight off kar diya. Aur kuch chahiye aapko?",
            VoiceResponseFormatter.format(off, offResult, personality = "Assistant")
        )
    }

    @Test fun gfCompletedCloseResponsesChangeWithoutRepeating() {
        val first = VoiceResponseFormatter.closeCompleted("YouTube", "GF")
        val second = VoiceResponseFormatter.closeCompleted("YouTube", "GF")

        assertNotEquals(first, second)
        assertTrue(first.contains("YouTube"))
        assertTrue(first.contains("close", ignoreCase = true) || first.contains("band", ignoreCase = true))
        assertTrue(first.contains("diya", ignoreCase = true) || first.contains("gaye", ignoreCase = true))
    }

    @Test fun assistantCompletedCloseResponseStaysNeutral() {
        assertEquals(
            "YouTube close kar diya. Aur kuch karun?",
            VoiceResponseFormatter.closeCompleted("YouTube", "Assistant")
        )
    }
}
