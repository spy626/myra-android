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
        assertEquals(CommandType.FLASHLIGHT_OFF, CommandParser.parse("Flash band karo").type)
        assertEquals(CommandType.BATTERY_LEVEL, CommandParser.parse("Mere phone ki battery percentage batao").type)
        assertEquals(CommandType.UNKNOWN, CommandParser.parse("Torch karo").type)
        assertEquals(true, com.myra.assistant.ai.CommandParser.isAmbiguousFlashlightCommand("Torch karo"))
        assertEquals(false, com.myra.assistant.ai.CommandParser.isExplicitOpenCommand("YouTube"))
        assertEquals(true, com.myra.assistant.ai.CommandParser.isExplicitOpenCommand("YouTube open karo"))
    }
    @Test fun preservesExistingYouTubeCommand() {
        val command = CommandParser.parse("YouTube mein Lols Gaming search karo")
        assertEquals(CommandType.SEARCH_YOUTUBE, command.type)
        assertEquals("lols gaming", command.content)
    }
    @Test fun parsesStrictMediaControlPhrasesWithoutWakeWord() {
        val parser = com.myra.assistant.ai.CommandParser
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.PAUSE,
            (parser.parseDirectMediaControl("video pause karo") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.PLAY,
            (parser.parseDirectMediaControl("video play karo") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.NEXT,
            (parser.parseDirectMediaControl("next video chalao") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.PREVIOUS,
            (parser.parseDirectMediaControl("pichhla video lagao") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.FIRST,
            (parser.parseDirectMediaControl("first video chalao") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.FIRST,
            (parser.parse("pehla video lagao") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(null, parser.parseDirectMediaControl("ruko"))
        assertEquals(null, parser.parseDirectMediaControl("video bahut achha hai"))
    }

    @Test fun acceptsLiveYouTubeCloseVariants() {
        val parser = com.myra.assistant.ai.CommandParser
        assertEquals(true, parser.parseDirectMediaControl("YouTube close karo") is com.myra.assistant.model.AppCommand.CloseCurrentApp)
        assertEquals(true, parser.parseDirectMediaControl("YouTube ko close kar do") is com.myra.assistant.model.AppCommand.CloseCurrentApp)
        assertEquals(true, parser.parseDirectMediaControl("go to close karo") is com.myra.assistant.model.AppCommand.CloseCurrentApp)
        assertEquals(true, parser.parseDirectMediaControl("some streamed words YouTube close karo") is com.myra.assistant.model.AppCommand.CloseCurrentApp)
    }
}
