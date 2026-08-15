package com.myra.assistant.voice

import com.myra.assistant.commands.Command
import com.myra.assistant.commands.CommandType
import com.myra.assistant.core.AssistantResult

object VoiceResponseFormatter {
    fun format(command: Command, result: AssistantResult, name: String = "Zopy"): String {
        if (!result.success) return result.spokenMessage
        return when (command.type) {
            CommandType.OPEN_APP -> "${command.target} khol diya, $name."
            CommandType.SEARCH_YOUTUBE, CommandType.REPEAT_YOUTUBE_SEARCH -> "YouTube par ${command.content ?: command.target.orEmpty()} search khol diya, $name."
            CommandType.REPLY_WHATSAPP -> result.spokenMessage
            CommandType.GO_HOME -> "Home screen par aa gayi, $name."
            CommandType.GO_BACK -> "Peechhe aa gayi, $name."
            CommandType.FLASHLIGHT_ON -> "Flashlight on kar di, $name."
            CommandType.FLASHLIGHT_OFF -> "Flashlight off kar di, $name."
            else -> result.spokenMessage
        }
    }
}
