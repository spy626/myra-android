package com.myra.assistant.ui.workspace

import java.util.Locale

/** Explicit user command parser. It never auto-selects a skill. */
internal object WorkspaceSkillUserCommand {
    private const val PREFIX = "/skill"
    private const val MAX_TASK_CHARS = 8_000
    private val skillName = Regex("""[a-z0-9][a-z0-9-]{0,63}""")

    data class Parsed(
        val skillName: String,
        val task: String,
    )

    fun parse(message: String): Parsed? {
        val leadingTrimmed = message.trimStart()
        if (!leadingTrimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val suffix = leadingTrimmed.drop(PREFIX.length)
        if (suffix.isNotEmpty() && !suffix.first().isWhitespace()) return null

        val body = suffix.trim()
        require(body.isNotBlank()) {
            "Use /skill <skill-name> <task>. No skill was invoked."
        }
        val separator = body.indexOfFirst(Char::isWhitespace)
        require(separator > 0) {
            "Add a task after the skill name: /skill <skill-name> <task>."
        }
        val name = body.substring(0, separator).lowercase(Locale.US)
        val task = body.substring(separator).trim()
        require(skillName.matches(name)) {
            "Skill name must use lowercase letters, numbers and hyphens."
        }
        require(task.length in 1..MAX_TASK_CHARS && !task.contains('\u0000')) {
            "Skill task is empty or exceeds the bounded 8000-character command limit."
        }
        return Parsed(name, task)
    }
}
