package com.myra.assistant.ui.workspace

/** Neuro-inspired conversational pacing, not a second personality model or router. */
internal object WorkspaceCompanionTurnPolicy {
    enum class Kind { TASK, SOCIAL, DEFAULT }

    private val task = Regex(
        """(?iu)\b(?:code|kotlin|android|repo|github|sources?|research|""" +
            """documentation|compare|difference|implement|fix|analyze|""" +
            """technical|steps?|error|build|apk|architecture|study|explain)\b"""
    )
    private val social = Regex(
        """(?iu)\b(?:bored|lonely|sad|excited|happy|joke|jokes|""" +
            """funny|roast|chat|gossip|baat|hansao|how are you|""" +
            """kya haal|kya kar rahe|kya kar rahi|friend|dost)\b"""
    )

    fun kind(message: String): Kind = when {
        message.isBlank() -> Kind.DEFAULT
        task.containsMatchIn(message) -> Kind.TASK
        social.containsMatchIn(message) -> Kind.SOCIAL
        else -> Kind.DEFAULT
    }

    fun instructions(message: String): String = when (kind(message)) {
        Kind.TASK -> "This is a serious task or technical question. Do the actual work first; " +
            "avoid jokes, interruptions, forced follow-ups and unrelated questions."
        Kind.SOCIAL -> "For this casual exchange, sound like a lively conversational companion " +
            "rather than a customer-support bot. A gentle joke, playful pushback or one " +
            "relevant question may help when natural, but do not force any. Respect boundaries."
        Kind.DEFAULT -> ""
    }
}
