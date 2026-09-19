package com.myra.assistant.ui.workspace

/** Conservative local intent boundary. Greetings and educational questions never create projects. */
internal object WorkspaceChatIntent {
    private val question = Regex("""\b(?:how to|how do|how can|what is|what are|why|kaise|kya hai|kya hota|tutorial|explain|tell me about|show me how|difference|teach me)\b|कैसे|क्या है""")
    private val action = Regex("""\b(?:make|build|create|generate|develop|design|code|implement|banao|bana\s*do|bana\s*ke|banado|banaye|banaiye|बनाओ|बना\s*दो|बनाइए)\b""")
    private val website = Regex("""\b(?:website|web\s*site|web\s*app|webapp|landing\s*page|site)\b|वेबसाइट""")
    private val android = Regex("""\b(?:android\s*app|android\s*application|mobile\s*app|apk)\b|एंड्रॉयड\s*ऐप""")
    private val promptRequest = Regex("""\bprompts?\b|प्रॉम्प्ट|پرومپٹ""")
    private val followUp = Regex("""\b(?:change|update|fix|modify|add|remove|replace|implement|redesign|code|badlo|hatao|jodo)\b|बदलो|हटाओ|जोड़ो""")

    fun requestedProjectType(message: String): WorkspaceProjectType? {
        val text = message.lowercase().replace(Regex("""[\s\p{Z}]+"""), " ").trim()
        // Asking for a prompt to paste into a coding AI is writing, not authority to
        // create or mutate an Android/website project in this app.
        if (text.isEmpty() || promptRequest.containsMatchIn(text) ||
            question.containsMatchIn(text) || !action.containsMatchIn(text)) return null
        return when {
            android.containsMatchIn(text) -> WorkspaceProjectType.ANDROID_APP
            website.containsMatchIn(text) -> WorkspaceProjectType.WEBSITE
            else -> null
        }
    }

    /** Only for an already selected coding project, never for ordinary/general chats. */
    fun isCodingFollowUp(message: String): Boolean {
        val text = message.lowercase().trim()
        // Writing a development prompt is not authority to edit project files.
        return !promptRequest.containsMatchIn(text) &&
            !question.containsMatchIn(text) && followUp.containsMatchIn(text)
    }
}
