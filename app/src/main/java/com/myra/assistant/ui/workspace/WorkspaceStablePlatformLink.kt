package com.myra.assistant.ui.workspace

/** Deterministic homepage links for a small set of stable public platforms. */
internal object WorkspaceStablePlatformLink {
    data class Match(val title: String, val url: String)

    private data class Entry(
        val title: String,
        val url: String,
        val cue: Regex,
        val aliases: Set<String>,
    )

    private val entries = listOf(
        Entry("YouTube", "https://www.youtube.com/", Regex("""(?iu)\b(?:youtube|yt)\b"""),
            setOf("youtube", "yt")),
        Entry("GitHub", "https://github.com/", Regex("""(?iu)\bgithub\b"""),
            setOf("github")),
        Entry("OpenAI", "https://openai.com/", Regex("""(?iu)\bopenai\b"""),
            setOf("openai")),
        Entry("ChatGPT", "https://chatgpt.com/", Regex("""(?iu)\bchatgpt\b"""),
            setOf("chatgpt")),
        Entry("Google", "https://www.google.com/", Regex("""(?iu)\bgoogle\b"""),
            setOf("google")),
        Entry("Reddit", "https://www.reddit.com/", Regex("""(?iu)\breddit\b"""),
            setOf("reddit")),
        Entry("X", "https://x.com/", Regex("""(?iu)\b(?:twitter|x)\b"""),
            setOf("twitter", "x")),
        Entry(
            "Android Developers",
            "https://developer.android.com/",
            Regex("""(?iu)\bandroid\s+developers?\b"""),
            setOf("android", "developer", "developers"),
        ),
    )

    private val linkAsk = Regex(
        """(?iu)(?:\b(?:link|url)\b.{0,56}\b(?:do|de|dena|bhejo|bhej|send|share|show|give|chahiye|chaiye)\b)|""" +
            """(?:\b(?:do|de|dena|bhejo|bhej|send|share|show|give)\b.{0,56}\b(?:link|url)\b)"""
    )
    private val mutation = Regex(
        """(?iu)\b(?:add|change|fix|edit|modify|implement|create|remove|replace|redesign|code|update)\b"""
    )
    private val directUrl = Regex("""(?i)https?://""")
    private val token = Regex("""[\p{L}\p{N}_@.-]+""")
    private val filler = setOf(
        "link", "url", "website", "site", "homepage", "home", "platform", "official",
        "ka", "ki", "ke", "ko", "mujhe", "please", "bro",
        "do", "de", "dena", "bhejo", "bhej", "send", "share", "show", "give",
        "chahiye", "chaiye", "wala", "wali", "wale", "the", "a", "an",
    )

    fun decide(message: String): Match? {
        val text = message.trim()
        if (!linkAsk.containsMatchIn(text) || mutation.containsMatchIn(text) ||
            directUrl.containsMatchIn(text)) return null
        val matched = entries.filter { it.cue.containsMatchIn(text) }
        if (matched.size != 1) return null
        val entry = matched.single()
        val meaningful = token.findAll(text)
            .map { it.value.lowercase() }
            .filterNot { it in filler || it in entry.aliases }
            .toList()
        if (meaningful.isNotEmpty()) return null
        val target = runCatching { WorkspaceAgentReachPolicy.parse(entry.url) }.getOrNull()
            ?: return null
        return Match(entry.title, target.canonicalUrl)
    }

    fun receipt(match: Match): String = WorkspaceVerifiedLinkReply.format(
        title = match.title + " — Official website",
        url = match.url,
        summary = "Known homepage link. Is request ke liye live web search nahi kiya.",
        fallback = "Platform homepage.",
    )
}
