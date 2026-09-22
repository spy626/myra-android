package com.myra.assistant.ui.workspace

/**
 * Conservative trust boundary for small-model *casual* replies. This is not a language
 * model or a spell checker: it catches only conspicuous new proper names and uninvited
 * meeting commitments, never guesses an alternative sentence or silently retries.
 * The user's actual words, not earlier assistant guesses, define what was mentioned.
 */
internal object WorkspaceCasualReplyEvidence {
    // Android's regex engine already uses Unicode character classes and does not support
    // Java's (?U) / UNICODE_CHARACTER_CLASS flag.
    private val properName = Regex("""\b\p{Lu}[\p{Ll}]{3,}\b""")
    // A bare 'milna', 'meet', or 'milte' can describe a THIRD PERSON or ask a question.
    // Only an assistant-authored, sentence-level invitation is treated as a proposal.
    // This deliberately prefers missing an ambiguous proposal over blocking harmless chat.
    private val meetingProposal = Regex("""(?iu)(?:^|[.!?]\s+)\s*(?:let['’]?s\s+(?:meet|hang\s+out)|(?:kal|aaj|phir|chalo|chal)\b[^.!?\n]{0,50}\b(?:milte\s+hain|mil\s+lo|meet)|(?:main|mai|hum|i|we)\b[^.!?\n]{0,45}\b(?:tumse|aapse|you)\b[^.!?\n]{0,20}\b(?:milne|meet))\b""")
    // A mention of meeting somebody else is NOT permission for LYRA to meet the user.
    private val userInvitation = Regex("""(?iu)(?:let['’]?s\s+(?:meet|hang\s+out)|(?:lyra|tum|aap)\b[^.!?\n]{0,55}\b(?:milo|milna|milne|meet)|(?:^|[.!?]\s+)\s*(?:kal|aaj|phir|chalo|chal)\b[^.!?\n]{0,50}\b(?:milte\s+hain|mil\s+lo|meet))\b""")
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

        // The latest user turn was persisted before the request. A repeated answer to a
        // DIFFERENT acknowledgement/clarification is a stale echo, not a new reply.
        val previous = messages.getOrNull(messages.lastIndex - 1)?.takeIf { it.role == "assistant" }
        if (lowInformation && previous != null &&
            speech.trim().replace(Regex("\\s+"), " ").equals(
                previous.text.trim().replace(Regex("\\s+"), " "), ignoreCase = true)) {
            throw IllegalArgumentException(
                "LYRA repeated its previous reply. It was not saved; tap Retry if needed. No automatic resend."
            )
        }

        // Capitalised words at sentence starts are ordinary grammar, not evidence of a
        // person. Never reject names that the user actually wrote in this same chat.
        val unmentioned = properName.findAll(speech).firstOrNull { candidate ->
            val previousChar = speech.substring(0, candidate.range.first).trimEnd().lastOrNull()
            val startsSentence = previousChar == null || previousChar in listOf('.', '!', '?', '\n', '।')
            !startsSentence && candidate.value.lowercase() !in ordinaryWords &&
                !userText.contains(candidate.value, ignoreCase = true)
        }
        if (unmentioned != null) throw IllegalArgumentException(
            "LYRA's reply introduced an unverified name. It was not saved; tap Retry if needed. No automatic resend."
        )
        if (meetingProposal.containsMatchIn(speech) && !userInvitation.containsMatchIn(userText))
            throw IllegalArgumentException(
                "LYRA proposed meeting without an invitation in your messages. Reply not saved; tap Retry if needed."
            )
        return speech
    }
}
