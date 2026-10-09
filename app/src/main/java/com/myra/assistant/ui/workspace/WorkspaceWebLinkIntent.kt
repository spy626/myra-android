package com.myra.assistant.ui.workspace

/**
 * Generic read-only link discovery intent.
 *
 * It activates only for explicit link/URL requests and never grants coding or browser-action
 * authority. The search query comes only from the current user turn.
 */
internal object WorkspaceWebLinkIntent {
    data class Request(
        val query: String,
        val preferredHost: String? = null,
    )

    private val directUrl = Regex("""(?i)https?://""")
    private val linkAsk = Regex(
        """(?iu)(?:\b(?:link|url)\b.{0,72}\b(?:do|de|dena|bhejo|bhej|send|share|show|give|chahiye|chaiye|find|dhundo|dhoondo|khojo)\b)|""" +
            """(?:\b(?:do|de|dena|bhejo|bhej|send|share|show|give|find|dhundo|dhoondo|khojo)\b.{0,72}\b(?:link|url)\b)"""
    )
    private val mutation = Regex(
        """(?iu)\b(?:add|change|fix|edit|modify|implement|create|remove|replace|redesign|code|update)\b"""
    )
    private val protectedInput = Regex(
        """(?iu)\b(?:password|passcode|otp|pin|cvv|api[ -]?key|access[ -]?token|""" +
            """private[ -]?key|secret|bank account|card number|recovery code|seed phrase)\b"""
    )
    private val token = Regex("""[\p{L}\p{N}_@.+-]+""")
    private val filler = setOf(
        "link", "url", "ka", "ki", "ke", "ko", "mujhe", "please", "bro",
        "do", "de", "dena", "bhejo", "bhej", "send", "share", "show", "give",
        "chahiye", "chaiye", "wala", "wali", "wale", "the", "a", "an",
        "find", "dhundo", "dhoondo", "khojo", "exact", "direct", "official",
    )
    private val genericOnly = setOf(
        "website", "site", "page", "article", "product", "docs", "documentation",
        "repo", "repository", "video", "channel",
    )

    private data class Hint(
        val cue: Regex,
        val host: String,
        val aliases: Set<String>,
    )

    private val hints = listOf(
        Hint(Regex("""(?iu)\b(?:youtube|yt)\b"""), "youtube.com", setOf("youtube", "yt")),
        Hint(Regex("""(?iu)\bgithub\b"""), "github.com", setOf("github")),
        Hint(Regex("""(?iu)\breddit\b"""), "reddit.com", setOf("reddit")),
        Hint(Regex("""(?iu)\b(?:twitter|x)\b"""), "x.com", setOf("twitter", "x")),
        Hint(Regex("""(?iu)\bchatgpt\b"""), "chatgpt.com", setOf("chatgpt")),
        Hint(Regex("""(?iu)\bopenai\b"""), "openai.com", setOf("openai")),
        Hint(
            Regex("""(?iu)\bandroid(?:\s+developers?)?\b"""),
            "developer.android.com",
            setOf("android", "developer", "developers"),
        ),
    )

    fun decide(message: String): Request? {
        val text = message.trim().replace(Regex("""[\s\p{Z}]+"""), " ")
        if (text.isBlank() || text.length > 300 || directUrl.containsMatchIn(text) ||
            !linkAsk.containsMatchIn(text) || mutation.containsMatchIn(text) ||
            protectedInput.containsMatchIn(text)) return null

        val hint = hints.firstOrNull { it.cue.containsMatchIn(text) }
        val words = token.findAll(text).map { it.value }.filterNot { raw ->
            val lower = raw.lowercase()
            lower in filler || lower in (hint?.aliases ?: emptySet())
        }.toList()
        val query = words.joinToString(" ").trim().take(180)
        if (query.length < 2) return null
        val meaningful = words.map { it.lowercase() }.filterNot { it in genericOnly }
        if (meaningful.isEmpty()) return null
        return Request(query, hint?.host)
    }
}
