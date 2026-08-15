package com.myra.assistant.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    @Test fun parsesHinglishDeviceCommands() {
        assertEquals(CommandType.CURRENT_TIME, CommandParser.parse("Time kya hai?").type)
        assertEquals(CommandType.BATTERY_LEVEL, CommandParser.parse("Battery kitni hai").type)
        assertEquals(CommandType.FLASHLIGHT_ON, CommandParser.parse("Torch chalu karo").type)
        assertEquals(CommandType.GO_BACK, CommandParser.parse("peeche jao").type)
    }
    @Test fun preservesExistingYouTubeCommand() {
        val command = CommandParser.parse("YouTube mein Lols Gaming search karo")
        assertEquals(CommandType.SEARCH_YOUTUBE, command.type)
        assertEquals("Lols Gaming", command.content)
    }
}
