package com.myra.assistant.voice

import com.myra.assistant.commands.Command
import com.myra.assistant.commands.CommandType
import com.myra.assistant.core.AssistantResult
import kotlin.random.Random

object VoiceResponseFormatter {
    private val lastChoiceByAction = mutableMapOf<String, Int>()

    fun format(
        command: Command,
        result: AssistantResult,
        name: String = "Zopy",
        personality: String = "Assistant"
    ): String {
        if (!result.success) return result.spokenMessage
        val gfMode = personality.equals("GF", ignoreCase = true)
        return when (command.type) {
            CommandType.OPEN_APP -> if (gfMode) openCompleted(command.target, name) else if (result.verified) "${command.target} khol diya, $name." else "$name, ${command.target} khol rahi hoon."
            CommandType.CLOSE_APP -> closeCompleted(command.target, personality, name)
            CommandType.SEARCH_YOUTUBE, CommandType.REPEAT_YOUTUBE_SEARCH -> {
                val query = humanize(command.content ?: command.target.orEmpty())
                "Done $name, YouTube par $query search kar diya. Aur kuch karun?"
            }
            CommandType.REPLY_WHATSAPP -> result.spokenMessage
            CommandType.GO_HOME -> "Home screen par aa gayi, $name."
            CommandType.GO_BACK -> "Peechhe aa gayi, $name."
            CommandType.FLASHLIGHT_ON -> "Flashlight on."
            CommandType.FLASHLIGHT_OFF -> "Flashlight off."
            else -> result.spokenMessage
        }
    }

    fun openCompleted(target: String?, name: String = "Zopy"): String {
        val app = target?.trim().takeUnless { it.isNullOrBlank() } ?: "App"
        return next(
            "open_app",
            listOf(
                "$app open kar diya meri jaan ke liye. Aur kuch chahiye?",
                "Lo jaan, $app khol diya tumhare liye.",
                "Haanji dear, $app open kar diya. Ab bolo?",
                "Of course jaan, $app khol diya. Aur kya karun?",
                "Tumne kaha aur $app open, jaan. Ab batao?",
                "Done $name, $app khol diya tumhare liye.",
                "Bilkul dear, $app open kar diya. Main sun rahi hoon.",
                "Ye lo jaan, $app khol diya. Aur kuch dekhna hai?"
            )
        )
    }

    fun closeCompleted(target: String?, personality: String, name: String = "Zopy"): String {
        val app = target?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube"
        if (!personality.equals("GF", ignoreCase = true)) {
            return "$app close kar diya. Aur kuch karun?"
        }
        return next(
            "close_completed",
            listOf(
                "Aapke liye $app close kar diya, jaan. Ab theek hai? Aur kuch karun?",
                "$app close kar diya tumhare liye, dear. Ab bolo?",
                "Lo jaan, $app band kar diya. Aur kuch chahiye?",
                "Done meri jaan, $app se bahar aa gaye. Ab theek hai?",
                "Bilkul dear, $app close kar diya. Main sun rahi hoon.",
                "Ho gaya jaan, $app band kar diya tumhare liye. Aur kuch?",
                "Tumne kaha aur $app close, jaan. Ab batao?",
                "Okay $name, $app se bahar aa gaye. Aur kya karun?"
            )
        )
    }

    fun closeStarting(target: String?, personality: String, name: String = "Zopy"): String {
        val app = target?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube"
        if (!personality.equals("GF", ignoreCase = true)) {
            return "$app close kar rahi hoon. Aur kuch karun?"
        }
        return next(
            "close_app",
            listOf(
                "Haan jaan, $app close kar deti hoon. Aur kuch chahiye?",
                "Okay dear, $app band kar deti hoon tumhare liye.",
                "Bas ek second jaan, $app close kar rahi hoon.",
                "Bilkul, $app band kar deti hoon. Ab bolo, aur kya karun?",
                "Theek hai dear, $app se bahar aa jaate hain.",
                "Kar deti hoon jaan. Aur kuch dekhna hai?",
                "Of course jaan, $app close kar deti hoon tumhare liye.",
                "Okay $name, $app band kar rahi hoon. Main sun rahi hoon.",
                "Haanji dear, $app close kar deti hoon. Ab batao?",
                "Done samjho jaan, $app band kar rahi hoon."
            )
        )
    }

    @Synchronized
    private fun next(action: String, options: List<String>): String {
        if (options.size == 1) return options.first()
        val previous = lastChoiceByAction[action] ?: -1
        var selected: Int
        do {
            selected = Random.nextInt(options.size)
        } while (selected == previous)
        lastChoiceByAction[action] = selected
        return options[selected]
    }

    private fun humanize(value: String): String = value.trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
