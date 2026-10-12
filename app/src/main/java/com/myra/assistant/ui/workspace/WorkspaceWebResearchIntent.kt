package com.myra.assistant.ui.workspace

/**
 * Explicit source-grounded explanatory questions. Does not intercept ordinary chat,
 * direct link lookup, attached content, app downloads, or protected write requests.
 */
internal object WorkspaceWebResearchIntent {
    data class Request(val question: String, val search: WorkspaceWebLinkIntent.Request)

    private val citationCue = Regex(
        """(?iu)\b(?:sources?|citations?|references?|evidence|reliable\s+sources?|""" +
            """sources?\s+ke\s+saath|official\s+documentation)\b"""
    )
    private val explainCue = Regex(
        """(?iu)\b(?:explain|difference|compare|comparison|how|why|what|""" +
            """samjhao|samjha[o]?|batao|bataye|farq|kya|kaise|kyun)\b"""
    )
    private val deny = Regex(
        """(?iu)\b(?:password|otp|passcode|api[ -]?key|secret|token|""" +
            """delete|modify|edit|fix|implement|install|upload|commit|push)\b"""
    )
    private val url = Regex("""(?i)https?://""")
    private val words = Regex("""[\p{L}\p{N}+#._-]{2,}""")
    private val filler = setOf(
        "android", "mein", "me", "mai", "kya", "hai", "ka", "ki", "ke", "ko",
        "aur", "with", "the", "a", "an", "is", "are", "how", "what", "why",
        "between", "vs", "difference", "compare", "comparison", "explain",
        "reliable", "sources", "source", "citations", "references",
        "documentation", "official", "saath", "bro", "please", "batao",
        "samjhao", "about", "do", "kar", "karo", "mujhe", "can", "you",
    )

    fun decide(text: String): Request? {
        val message = text.trim().replace(Regex("""\s+"""), " ")
        if (message.length !in 12..300 || url.containsMatchIn(message) ||
            deny.containsMatchIn(message) || !citationCue.containsMatchIn(message) ||
            !explainCue.containsMatchIn(message)) return null
        val terms = words.findAll(message).map { it.value }.filter {
            it.lowercase() !in filler
        }.distinctBy { it.lowercase() }.take(9).toList()
        if (terms.size < 2) return null
        val query = terms.joinToString(" ").take(180)
        return Request(message, WorkspaceWebLinkIntent.Request(query, maxResults = 3))
    }
}
