package com.myra.assistant.ui.workspace

/** Conservative local intent boundary. A greeting or question must not create source files or a coding project. */
internal object WorkspaceChatIntent {
    private val question = Regex("""\b(?:how to|how do|how can|what is|what are|why|kaise|kya hota|tutorial|explain|difference|teach me)\b|कैसे|क्या है""")
    private val action = Regex("""\b(?:make|build|create|generate|develop|design|code|implement|banao|bana\s*do|bana\s*ke|banado|banaye|banaiye|बनाओ|बना\s*दो|बनाइए)\b""")
    private val website = Regex("""\b(?:website|web\s*site|web\s*app|webapp|landing\s*page|site)\b|वेबसाइट""")
    private val android = Regex("""\b(?:android\s*app|android\s*application|mobile\s*app|apk)\b|एंड्रॉयड\s*ऐप""")

    fun requestedProjectType(message: String): WorkspaceProjectType? {
        val text = message.lowercase().replace(Regex("""[\s\p{Z}]+"""), " ").trim()
        if (text.isEmpty() || question.containsMatchIn(text) || !action.containsMatchIn(text)) return null
        return when {
            android.containsMatchIn(text) -> WorkspaceProjectType.ANDROID_APP
            website.containsMatchIn(text) -> WorkspaceProjectType.WEBSITE
            else -> null
        }
    }
}
