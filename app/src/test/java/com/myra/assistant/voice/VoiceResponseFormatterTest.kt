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
        assertEquals("YouTube khol diya, Zopy.", VoiceResponseFormatter.format(command, result))
    }
    @Test fun neverRewritesFailureAsSuccess() {
        val command = Command(CommandType.OPEN_APP, "YouTube", sourceText = "open")
        val result = AssistantResult(false, false, "OPEN_APP", "YouTube", "YouTube nahi mila.")
        assertEquals("YouTube nahi mila.", VoiceResponseFormatter.format(command, result))
    }
}
