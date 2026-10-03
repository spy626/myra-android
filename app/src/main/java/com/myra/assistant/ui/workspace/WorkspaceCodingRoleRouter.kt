package com.myra.assistant.ui.workspace

/** Provider-role routing only; never grants permission or writes files. */
internal object WorkspaceCodingRoleRouter {
    enum class Provider { XKIRO, GROQ }
    enum class TaskSize { QUICK, HEAVY }

    private val heavyIntent = Regex(
        """(?i)\b(?:architecture|architect|refactor|migration|database|room|oauth|auth|""" +
            """concurren|thread|async|memory|pipeline|multi[- ]?step|multi[- ]?file|""" +
            """repository|codebase|ci|github actions|build system|gradle|security|""" +
            """coordinator|orchestrator|state machine|fallback|checkpoint|resume)\b"""
    )

    fun classify(instruction: String, sourceChars: Int): TaskSize {
        require(sourceChars >= 0) { "Source size cannot be negative" }
        val request = instruction.trim()
        return if (sourceChars > 6_000 || request.length > 700 || heavyIntent.containsMatchIn(request))
            TaskSize.HEAVY else TaskSize.QUICK
    }

    fun select(instruction: String, sourceChars: Int, xKiroPermitted: Boolean, groqPermitted: Boolean): Provider? {
        val size = classify(instruction, sourceChars)
        return when {
            size == TaskSize.HEAVY && xKiroPermitted -> Provider.XKIRO
            size == TaskSize.QUICK && groqPermitted -> Provider.GROQ
            xKiroPermitted -> Provider.XKIRO
            groqPermitted -> Provider.GROQ
            else -> null
        }
    }
}
