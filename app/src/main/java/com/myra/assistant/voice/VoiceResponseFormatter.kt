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
            CommandType.OPEN_YOUTUBE_SHORTS -> "YouTube Shorts open kar diya."
            CommandType.REQUEST_INSTAGRAM_REELS -> "Instagram open kar dun tumhare liye?"
            CommandType.OPEN_INSTAGRAM_REELS -> "Instagram Reels open kar diya tumhare liye."
            CommandType.TAKE_SCREENSHOT -> "Screenshot le liya."
            CommandType.PLAY_YOUTUBE -> {
                val query = command.content?.let(::humanize)
                if (query.isNullOrBlank()) "Haan, bore mat ho. Tumhare liye ek video chala rahi hoon."
                else "Haan, YouTube par $query dhoondhkar chala rahi hoon."
            }
            CommandType.SEARCH_YOUTUBE, CommandType.REPEAT_YOUTUBE_SEARCH -> {
                val query = humanize(command.content ?: command.target.orEmpty())
                "Done $name, YouTube par $query search kar diya. Aur kuch karun?"
            }
            CommandType.REPLY_WHATSAPP -> result.spokenMessage
            CommandType.GO_HOME -> "Home screen par aa gayi, $name."
            CommandType.GO_BACK -> "Peechhe aa gayi, $name."
            CommandType.FLASHLIGHT_ON -> if (gfMode) next(
                "flashlight_on",
                listOf(
                    "Haan, flashlight on kar diya. Ab sab clearly dikh raha hai?",
                    "Lo, flashlight jala diya tumhare liye. Aur kuch chahiye?",
                    "Of course, flashlight on ho gaya. Ab bolo, aur kya karun?",
                    "Done Zopy, flashlight on kar diya. Main yahin hoon.",
                    "Haanji, roshni kar di. Aur kuch karun?",
                    "Bas tumne kaha aur flashlight on. Ab batao?"
                )
            ) else "Flashlight on kar diya. Aur kuch karun?"
            CommandType.FLASHLIGHT_OFF -> if (gfMode) next(
                "flashlight_off",
                listOf(
                    "Haan, flashlight off kar diya. Aur kuch chahiye?",
                    "Lo, flashlight band kar diya. Ab bolo?",
                    "Of course, roshni band kar di. Main sun rahi hoon.",
                    "Done Zopy, flashlight off kar diya. Aur kuch karun?",
                    "Haanji, flashlight band ho gaya. Ab kya karna hai?",
                    "Tumne kaha aur flashlight off. Aur kuch?"
                )
            ) else "Flashlight off kar diya. Aur kuch chahiye aapko?"
            CommandType.MEDIA_PAUSE -> mediaResponse("pause", gfMode)
            CommandType.MEDIA_PLAY -> mediaResponse("play", gfMode)
            CommandType.MEDIA_NEXT -> mediaResponse("next", gfMode)
            CommandType.MEDIA_PREVIOUS -> mediaResponse("previous", gfMode)
            CommandType.MEDIA_FIRST -> mediaResponse("first", gfMode)
            CommandType.YOUTUBE_SCROLL_DOWN -> if (gfMode) "Neeche scroll kar diya." else "Neeche scroll kar diya."
            CommandType.YOUTUBE_SCROLL_UP -> if (gfMode) "Upar scroll kar diya." else "Upar scroll kar diya."
            CommandType.YOUTUBE_SCROLL_REPEAT -> result.spokenMessage
            else -> result.spokenMessage
        }
    }

    private fun mediaResponse(action: String, gfMode: Boolean): String {
        if (!gfMode) {
            return when (action) {
                "pause" -> "Video pause command bhej diya."
                "play" -> "Video play command bhej diya."
                "next" -> "Next video par tap kar diya."
                "previous" -> "Previous video command bhej diya."
                else -> "First video par tap kar diya."
            }
        }
        val options = when (action) {
            "pause" -> listOf(
                "Haan, video pause kar diya. Jab bolo phir chala dungi.",
                "Lo, video rok diya. Aaram se batao, ab kya karna hai?",
                "Pause kar diya yaar. Main sun rahi hoon.",
                "Video ruk gaya. Jab chaho resume kar denge."
            )
            "play" -> listOf(
                "Lo, video phir se chala diya.",
                "Haan, video play kar diya. Enjoy karo.",
                "Video chala diya yaar. Aur kuch chahiye?",
                "Resume kar diya. Main yahin hoon."
            )
            "next" -> listOf(
                "Agla video chala diya. Ye dekhte hain?",
                "Lo, next video laga diya.",
                "Next kar diya yaar. Batao, ye pasand hai?",
                "Agla video aa gaya. Aur kuch karun?"
            )
            "previous" -> listOf(
                "Pichhla video command bhej diya tumhare liye.",
                "Lo, previous video command bhej diya.",
                "Pehle wale video ka command bhej diya, yaar.",
                "Previous karne ko bol diya. Ab check karo."
            )
            else -> listOf(
                "First video par tap kar diya.",
                "Lo, sabse pehla video select kar diya.",
                "Pehla video open karne ke liye tap kar diya, yaar.",
                "First video choose kar diya. Ab dekhte hain."
            )
        }
        return next("media_$action", options)
    }

    fun openCompleted(target: String?, name: String = "Zopy"): String {
        val app = target?.trim().takeUnless { it.isNullOrBlank() } ?: "App"
        return next(
            "open_app",
            listOf(
                "$app open kar diya tumhare liye, yaar. Aur kuch chahiye?",
                "Lo, $app khol diya tumhare liye.",
                "Haanji, $app open kar diya. Ab bolo?",
                "Of course, $app khol diya. Aur kya karun?",
                "Tumne kaha aur $app open. Ab batao?",
                "Done $name, $app khol diya tumhare liye.",
                "Bilkul, $app open kar diya. Main sun rahi hoon.",
                "Ye lo, $app khol diya. Aur kuch dekhna hai?"
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
                "Aapke liye $app close kar diya. Ab theek hai? Aur kuch karun?",
                "$app close kar diya tumhare liye. Ab bolo?",
                "Lo, $app band kar diya. Aur kuch chahiye?",
                "Done yaar, $app se bahar aa gaye. Ab theek hai?",
                "Bilkul, $app close kar diya. Main sun rahi hoon.",
                "Ho gaya, $app band kar diya tumhare liye. Aur kuch?",
                "Tumne kaha aur $app close. Ab batao?",
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
                "Haan, $app close kar deti hoon. Aur kuch chahiye?",
                "Okay, $app band kar deti hoon tumhare liye.",
                "Bas ek second, $app close kar rahi hoon.",
                "Bilkul, $app band kar deti hoon. Ab bolo, aur kya karun?",
                "Theek hai, $app se bahar aa jaate hain.",
                "Kar deti hoon. Aur kuch dekhna hai?",
                "Of course, $app close kar deti hoon tumhare liye.",
                "Okay $name, $app band kar rahi hoon. Main sun rahi hoon.",
                "Haanji, $app close kar deti hoon. Ab batao?",
                "Done samjho, $app band kar rahi hoon."
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
