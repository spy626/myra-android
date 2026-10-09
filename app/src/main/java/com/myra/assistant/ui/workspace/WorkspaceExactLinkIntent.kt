package com.myra.assistant.ui.workspace

/**
 * Read-only exact-link intent resolver.
 *
 * It never treats a link request as coding authority. This first resolver handles YouTube channel
 * destinations from either a direct request or a short follow-up to a just-asked YouTube link.
 */
internal object WorkspaceExactLinkIntent {
    enum class Platform { YOUTUBE }

    data class Request(
        val platform: Platform,
        val query: String,
    )

    private val url = Regex("""https://""", RegexOption.IGNORE_CASE)
    private val youtube = Regex("""(?iu)\b(?:youtube|yt)\b""")
    private val linkAsk = Regex(
        """(?iu)(?:\b(?:link|url)\b.{0,48}\b(?:do|de|dena|bhejo|bhej|send|share|give|chahiye|chaiye)\b)|""" +
            """(?:\b(?:do|de|dena|bhejo|bhej|send|share|give)\b.{0,48}\b(?:link|url)\b)"""
    )
    private val mutation = Regex(
        """(?iu)\b(?:add|change|fix|edit|modify|implement|create|remove|replace|redesign|code|update)\b"""
    )
    private val videoRequest = Regex(
        """(?iu)\b(?:video|shorts?|watch|song|episode|trailer|latest\s+video|new\s+video)\b"""
    )
    private val token = Regex("""[\p{L}\p{N}_@.-]+""")
    private val filler = setOf(
        "youtube", "yt", "channel", "link", "url", "official",
        "ka", "ki", "ke", "ko", "mujhe", "please", "bro",
        "do", "de", "dena", "bhejo", "bhej", "send", "share", "give",
        "chahiye", "chaiye", "wala", "wali", "wale", "the", "a", "an"
    )
    private val nonSubject = setOf(
        "ok", "okay", "haan", "ha", "yes", "no", "nahi", "nahin", "nehi",
        "thanks", "thank", "thankyou", "shukriya"
    )

    private fun subject(text: String): String? {
        val words = token.findAll(text).map { it.value }
            .filterNot { it.lowercase() in filler }
            .toList()
        val cleaned = words.joinToString(" ").trim().take(100)
        if (cleaned.length < 2 || cleaned.lowercase().replace(" ", "") in nonSubject) return null
        return cleaned
    }

    private fun isYouTubeLinkAsk(text: String): Boolean =
        youtube.containsMatchIn(text) && linkAsk.containsMatchIn(text) &&
            !mutation.containsMatchIn(text) && !url.containsMatchIn(text)

    fun decide(
        current: String,
        prior: List<WorkspaceConversationStore.Message>,
    ): Request? {
        val text = current.trim().replace(Regex("""[\s\p{Z}]+"""), " ")
        if (text.isBlank() || url.containsMatchIn(text) || mutation.containsMatchIn(text) ||
            videoRequest.containsMatchIn(text)) return null

        if (isYouTubeLinkAsk(text)) {
            return subject(text)?.let { Request(Platform.YOUTUBE, it) }
        }

        // A short answer such as "CarryMinati ka" can complete the immediately preceding
        // "YouTube ka link bhejo" request. Old conversations never grant action authority.
        if (text.length > 100 || linkAsk.containsMatchIn(text) || youtube.containsMatchIn(text)) {
            return null
        }
        val recent = prior.takeLast(4)
        val pendingIndex = recent.indexOfLast { message ->
            message.role == "user" && isYouTubeLinkAsk(message.text) && subject(message.text) == null
        }
        if (pendingIndex < 0 || recent.drop(pendingIndex + 1).none { it.role == "assistant" }) return null
        return subject(text)?.let { Request(Platform.YOUTUBE, it) }
    }
}
