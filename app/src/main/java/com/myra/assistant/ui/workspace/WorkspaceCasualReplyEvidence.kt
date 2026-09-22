package com.myra.assistant.ui.workspace

/**
 * Conservative trust boundary for small-model *casual* replies. This is not a language
 * model or a spell checker: it catches only conspicuous new proper names and uninvited
 * meeting commitments, never guesses an alternative sentence or silently retries.
 * The user's actual words, not earlier assistant guesses, define what was mentioned.
 */
internal object WorkspaceCasualReplyEvidence {
    // Android's regex engine already uses Unicode character classes and does not support
    // Java's (?U) / UNICODE_CHARACTER_CLASS flag. That flag caused class initialization to
    // fail on a real phone before any reply could be displayed, despite JVM tests passing.
    private val properName = Regex("""\b\p{Lu}[\p{Ll}]{3,}\b""")
    private val meeting = Regex("""(?iu)\b(?:milte|milenge|milna|meet|meetup|hang\s+out)\b""")
    private val ordinaryWords = setOf(
        "main", "maine", "mujhe", "mera", "meri", "mere", "tum", "tumne", "tumhara",
        "haan", "nahi", "nahin", "achha", "acha", "accha", "theek", "thik", "sahi",
        "kal", "aaj", "ab", "aur", "par", "lekin", "magar", "kya", "kaise", "kyun",
        "ye", "yeh", "woh", "waise", "dost", "bhai", "bro", "yaar", "samajh", "bilkul",
        "okay", "nice", "sure", "great", "wow", "sounds", "that", "this", "your", "you",
        "sorry", "actually", "right", "well", "yes", "no", "good", "let", "please"
    )
    private val acknowledgement = Regex("""(?iu)^\s*(?:ok(?:ay)?|theek|thik|sahi|haan|han|yes|achha|accha|got it|sounds good)(?:\s+(?:hai|he|h|bro|yaar|great))?\s*[!?., 🙂😄]*$""")
    private val clarification = Regex("""(?iu)^\s*(?:kya|kia|kya hai|kya hei|kya he|what do you mean|what was that|matlab kya|samajh nahi aaya)\s*[?!. ]*$""")
    private val shortRequest = Regex("""(?iu)\b(?:short|brief|concise|chhote|chote|chhota|chota)\b.{0,28}\b(?:reply|replies|answer|answers|jawab|response)\b""")

    fun verify(messages: List<WorkspaceConversationStore.Message>, speech: String): String {
        val latest = messages.lastOrNull()?.takeIf { it.role == "user" }?.text?.trim() ?: return speech
        if (!WorkspaceChatTurnFrame.isCasual(messages)) return speech
        val userText = messages.asSequence().filter { it.role == "user" }
            .map { it.text }.joinToString("\n").takeLast(4_000)
        val lowInformation = acknowledgement.matches(latest) || clarification.matches(latest)
        val personalShort = messages.filter { it.role == "user" }.takeLast(4)
            .any { shortRequest.containsMatchIn(it.text) }
        if (!lowInformation && !personalShort) return speech

        // Capitalised words at sentence starts are ordinary grammar, not evidence of a
        // person. Never reject names that the user actually wrote in this same chat.
        val unmentioned = properName.findAll(speech).firstOrNull { candidate ->
            val previous = speech.substring(0, candidate.range.first).trimEnd().lastOrNull()
            val startsSentence = previous == null || previous in listOf('.', '!', '?', '\n', '।')
            !startsSentence && candidate.value.lowercase() !in ordinaryWords &&
                !userText.contains(candidate.value, ignoreCase = true)
        }
        if (unmentioned != null) throw IllegalArgumentException(
            "LYRA's reply introduced an unverified name. It was not saved; tap Retry if needed. No automatic resend."
        )
        if (meeting.containsMatchIn(speech) && !meeting.containsMatchIn(userText))
            throw IllegalArgumentException(
                "LYRA proposed meeting without an invitation in your messages. Reply not saved; tap Retry if needed."
            )
        return speech
    }
}
