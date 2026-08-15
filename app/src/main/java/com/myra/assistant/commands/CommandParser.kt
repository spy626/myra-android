package com.myra.assistant.commands

import com.myra.assistant.model.AppCommand
import java.util.Locale

object CommandParser {
    fun parse(raw: String): Command {
        val text = raw.trim()
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("[?.!,]+"), " ").replace(Regex("\\s+"), " ").trim()
        val basic = when {
            Regex("^(?:go )?home(?: screen)?$|^home (?:jao|chalo|karo)$|^होम").containsMatchIn(normalized) -> AppCommand.GoHome
            Regex("^(?:go )?back$|^back (?:jao|karo)$|^peeche (?:jao|chalo)$|^पीछे").containsMatchIn(normalized) -> AppCommand.GoBack
            Regex("(?:what(?:'s| is) the time|time (?:kya|kitna|kitni|batao)|(?:kya|kitna|kitni) time|samay|kitne baje|टाइम|समय)").containsMatchIn(normalized) -> AppCommand.CurrentTime
            Regex("(?:battery|बैटरी).*(?:level|percent|percentage|kitna|kitni|batao|status|charge|कितना|कितनी|बताओ)|^(?:battery|बैटरी)$").containsMatchIn(normalized) -> AppCommand.BatteryLevel
            Regex("(?:flashlight|flash|torch|टॉर्च).*(?:on|open|chalu|jala|चालू|जलाओ)").containsMatchIn(normalized) -> AppCommand.SetFlashlight(true)
            Regex("(?:flashlight|flash|torch|टॉर्च).*(?:off|close|band|bujha|बंद|बुझाओ)").containsMatchIn(normalized) -> AppCommand.SetFlashlight(false)
            else -> com.myra.assistant.ai.CommandParser.parse(text)
        }
        return basic?.let { fromLegacy(it, text) } ?: Command(CommandType.UNKNOWN, sourceText = text)
    }

    fun fromLegacy(command: AppCommand, source: String): Command = when (command) {
        is AppCommand.OpenApp -> Command(CommandType.OPEN_APP, command.appName, sourceText = source, localCommand = command)
        is AppCommand.CloseCurrentApp -> Command(CommandType.CLOSE_APP, command.requestedName, sourceText = source, localCommand = command)
        is AppCommand.SearchYouTube -> Command(CommandType.SEARCH_YOUTUBE, "YouTube", command.query, source, command)
        AppCommand.RepeatYouTubeSearch -> Command(CommandType.REPEAT_YOUTUBE_SEARCH, "YouTube", sourceText = source, localCommand = command)
        is AppCommand.DeepResearch -> Command(CommandType.DEEP_RESEARCH, content = command.query, sourceText = source, localCommand = command)
        is AppCommand.ReplyWhatsApp -> Command(CommandType.REPLY_WHATSAPP, command.sender, command.message, source, command)
        AppCommand.QueryWhatsAppMessages -> Command(CommandType.QUERY_WHATSAPP, "WhatsApp", sourceText = source, localCommand = command)
        AppCommand.GoHome -> Command(CommandType.GO_HOME, sourceText = source, localCommand = command)
        AppCommand.GoBack -> Command(CommandType.GO_BACK, sourceText = source, localCommand = command)
        AppCommand.CurrentTime -> Command(CommandType.CURRENT_TIME, sourceText = source, localCommand = command)
        AppCommand.BatteryLevel -> Command(CommandType.BATTERY_LEVEL, sourceText = source, localCommand = command)
        is AppCommand.SetFlashlight -> Command(if (command.enabled) CommandType.FLASHLIGHT_ON else CommandType.FLASHLIGHT_OFF, sourceText = source, localCommand = command)
    }
}
