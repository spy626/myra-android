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
    @Test fun listsOnlyImplementedFeatures() {
        listOf(
            "kon kon se features hai",
            "kaun sa kaun sa feature hai",
            "kauna kauna si phicara hai abhi",
            "kauna si phicara hai",
            "कौन सी फीचर है",
            "अभी कौन कौन से फीचर्स हैं",
            "phicara kauna kauna se haim",
            "features konsi hai",
            "tum kya kya kar sakti ho",
            "tum kya kya kar sakte ho",
            "तुम क्या-क्या कर सकते हो",
            "apne saare features batao",
            "what can you do"
        ).forEach { phrase ->
            assertEquals(CommandType.LIST_FEATURES, CommandParser.parse(phrase).type)
        }
    }

    @Test fun parsesContextualYouTubeSearchAndScrolling() {
        listOf(
            "search karo new song" to "new song",
            "Jonathan Gaming search karo" to "jonathan gaming"
        ).forEach { (phrase, expectedQuery) ->
            val command = CommandParser.parse(phrase)
            assertEquals(CommandType.SEARCH_YOUTUBE, command.type)
            assertEquals(expectedQuery, command.content)
        }

        val parser = com.myra.assistant.ai.CommandParser
        assertEquals(
            com.myra.assistant.model.AppCommand.ScrollDirection.DOWN,
            (parser.parseDirectMediaControl("niche scroll karo") as com.myra.assistant.model.AppCommand.ScrollYouTube).direction
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.ScrollDirection.UP,
            (parser.parseDirectMediaControl("upper scroll karo") as com.myra.assistant.model.AppCommand.ScrollYouTube).direction
        )
        assertEquals(
            null,
            (parser.parseDirectMediaControl("scroll karo") as com.myra.assistant.model.AppCommand.ScrollYouTube).direction
        )
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
        listOf(
            "next video chalao",
            "next video chala",
            "next video chalo",
            "next video play karo",
            "next video open karo",
            "next video khol do",
            "agla video play kar do"
        ).forEach { phrase ->
            assertEquals(
                com.myra.assistant.model.AppCommand.MediaAction.NEXT,
                (parser.parseDirectMediaControl(phrase) as com.myra.assistant.model.AppCommand.ControlMedia).action
            )
            assertEquals(
                com.myra.assistant.model.AppCommand.MediaAction.NEXT,
                (parser.parse(phrase) as com.myra.assistant.model.AppCommand.ControlMedia).action
            )
        }
        listOf(
            "pichhla video lagao",
            "pichle video open karo",
            "previous video kholo",
            "peeche wala video play karo"
        ).forEach { phrase ->
            assertEquals(
                com.myra.assistant.model.AppCommand.MediaAction.PREVIOUS,
                (parser.parseDirectMediaControl(phrase) as com.myra.assistant.model.AppCommand.ControlMedia).action
            )
            assertEquals(
                com.myra.assistant.model.AppCommand.MediaAction.PREVIOUS,
                (parser.parse(phrase) as com.myra.assistant.model.AppCommand.ControlMedia).action
            )
        }
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.FIRST,
            (parser.parseDirectMediaControl("first video chalao") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.FIRST,
            (parser.parse("pehla video lagao") as com.myra.assistant.model.AppCommand.ControlMedia).action
        )
        assertEquals(
            com.myra.assistant.model.AppCommand.MediaAction.FIRST,
            (parser.parse("first video kholo") as com.myra.assistant.model.AppCommand.ControlMedia).action
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
