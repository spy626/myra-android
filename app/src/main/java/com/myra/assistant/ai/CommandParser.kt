package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import java.util.Locale

object CommandParser {
    private val openPatterns = listOf(
        Regex("^(?:please\\s+)?open\\s+(.+?)(?:\\s+app)?$"),
        Regex("^(.+?)\\s+(?:kholo|khol do|open karo|open kar do|chalao|start karo)$")
    )
    private val closePatterns = listOf(
        Regex("^(?:please\\s+)?close\\s+(.+?)(?:\\s+app)?$"),
        Regex("^(.+?)\\s+(?:band karo|band kar do|close karo)$")
    )

    fun parse(raw: String): AppCommand? {
        val text = raw.lowercase(Locale.ROOT).replace(Regex("[!?.,]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return null
        openPatterns.firstNotNullOfOrNull { it.matchEntire(text) }?.let {
            return AppCommand.OpenApp(cleanName(it.groupValues[1]))
        }
        closePatterns.firstNotNullOfOrNull { it.matchEntire(text) }?.let {
            val name = cleanName(it.groupValues[1]).takeUnless { value -> value in setOf("app", "application", "current app", "this app") }
            return AppCommand.CloseCurrentApp(name)
        }
        return null
    }

    private fun cleanName(value: String) = value.replace(Regex("^(?:the|my|mera|meri)\\s+"), "")
        .replace(Regex("\\s+(?:app|application)$"), "").trim()
}
