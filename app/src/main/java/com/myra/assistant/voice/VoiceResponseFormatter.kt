package com.myra.assistant.voice

import com.myra.assistant.commands.Command
import com.myra.assistant.commands.CommandType
import com.myra.assistant.core.AssistantResult

object VoiceResponseFormatter {
    fun format(command: Command, result: AssistantResult, name: String = "Zopy"): String {
        if (!result.success) return result.spokenMessage
        return when (command.type) {
            CommandType.OPEN_APP -> if (result.verified) "${command.target} khol diya, $name." else "$name, ${command.target} khol rahi hoon."
            CommandType.CLOSE_APP -> if (result.verified) {
                "Done $name, ${command.target ?: "app"} se bahar aa gayi hoon. Aur kuch karun?"
            } else {
                "Theek hai $name, ${command.target ?: "app"} se bahar aa rahi hoon."
            }
            CommandType.SEARCH_YOUTUBE, CommandType.REPEAT_YOUTUBE_SEARCH -> {
                val query = humanize(command.content ?: command.target.orEmpty())
                "Done $name, YouTube par $query search kar diya. Aur kuch karun?"
            }
            CommandType.REPLY_WHATSAPP -> result.spokenMessage
            CommandType.GO_HOME -> "Home screen par aa gayi, $name."
            CommandType.GO_BACK -> "Peechhe aa gayi, $name."
            CommandType.FLASHLIGHT_ON -> "Flashlight on kar diya. Aur kuch karun?"
            CommandType.FLASHLIGHT_OFF -> "Flashlight off kar diya. Aur kuch chahiye aapko?"
            else -> result.spokenMessage
        }
    }

    private fun humanize(value: String): String = value.trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
