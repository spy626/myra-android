package com.myra.assistant.ui.workspace

/**
 * Presentation-only status labels for assistant list/table cells. Keep the actual
 * stored model reply, app logos, voice/memory and all non-status emoji unchanged.
 */
internal object WorkspaceRichStatusText {
    private val labels = listOf(
        "✅" to "Ho gaya",
        "☑️" to "Ho gaya",
        "✔️" to "Ho gaya",
        "✔" to "Ho gaya",
        "🟢" to "Ho gaya",
        "❌" to "Nahi hua",
        "✖️" to "Nahi hua",
        "🔴" to "Nahi hua",
        "❓" to "Check karna hai",
        "🟡" to "Baaki",
        "🟠" to "Baaki",
        "⚠️" to "Dhyan dein",
    )

    fun neutralize(value: String): String {
        var text = value
        labels.forEach { (emoji, name) ->
            // Don't duplicate an already written text status: "✅ Ho gaya".
            text = text.replace(
                Regex(Regex.escape(emoji) + """(?=\s*""" + Regex.escape(name) + """\b)"""),
                "",
            )
            text = text.replace(emoji, name)
        }
        return text.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }
}
