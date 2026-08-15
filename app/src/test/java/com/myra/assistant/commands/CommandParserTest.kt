package com.myra.assistant.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    @Test fun parsesHinglishDeviceCommands() {
        assertEquals(CommandType.CURRENT_TIME, CommandParser.parse("Time kya hai?").type)
        assertEquals(CommandType.BATTERY_LEVEL, CommandParser.parse("Battery kitni hai").type)
        assertEquals(CommandType.FLASHLIGHT_ON, CommandParser.parse("Torch chalu karo").type)
        assertEquals(CommandType.GO_BACK, CommandParser.parse("peeche jao").type)
        assertEquals(CommandType.CURRENT_TIME, CommandParser.parse("Time kitna hua?").type)
        assertEquals(CommandType.FLASHLIGHT_ON, CommandParser.parse("No, meri phone ka torch open on karo").type)
        assertEquals(CommandType.OPEN_APP, CommandParser.parse("Mmm, YouTube open karo").type)
        assertEquals(CommandType.OPEN_APP, CommandParser.parse("WhatsApp open karo, sun rahe ho kya?").type)
    }
    @Test fun preservesExistingYouTubeCommand() {
        val command = CommandParser.parse("YouTube mein Lols Gaming search karo")
        assertEquals(CommandType.SEARCH_YOUTUBE, command.type)
        assertEquals("lols gaming", command.content)
    }
}
