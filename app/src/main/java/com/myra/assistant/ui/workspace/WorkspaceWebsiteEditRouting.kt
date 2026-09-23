package com.myra.assistant.ui.workspace

/**
 * Pure, local routing for edits to an already-built three-file website.
 * A narrow request may select exactly one canonical website file; ambiguous,
 * multi-file or build/redesign requests stay on the full website pipeline.
 */
internal object WorkspaceWebsiteEditRouting {
    data class Decision(val path: String?) {
        val isSingleFile: Boolean get() = path != null
    }

    private val buildOrRedesign = Regex(
        """(?ix)
        (?:\b(?:build|create|generate|rebuild|redesign|recreate|make)\b.{0,48}
            \b(?:website|site|landing\s+page|web\s*page)\b)
        |
        (?:\b(?:website|site|landing\s+page|web\s*page)\b.{0,48}
            \b(?:banao|bana\s+do|banado|banana|rebuild|redesign|recreate)\b)
        |
        (?:\b(?:poora|pura|puri|whole|entire)\b.{0,32}
            \b(?:website|site|page)\b)
        """.trimIndent()
    )

    private val styleSignal = Regex(
        """(?i)\b(?:css|style|styling|color|colour|background|font|typography|spacing|margin|padding|border|shadow|gradient|radius|opacity|theme|alignment|gap)\b"""
    )
    private val scriptSignal = Regex(
        """(?i)\b(?:javascript|script|onclick|click\s+behaviou?r|interaction|toggle|submit\s+behaviou?r|validation|scroll\s+behaviou?r|event\s+listener|modal\s+(?:open|close)|menu\s+(?:open|close))\b"""
    )
    private val htmlSignal = Regex(
        """(?i)\b(?:html|heading|headline|title|paragraph|copy|wording|content|section|placeholder|label|link\s+text|alt\s+text)\b"""
    )

    fun decide(instruction: String, existingPaths: Set<String>): Decision {
        val canonical = WorkspaceWebsiteGeneration.PATHS.toSet()
        if (!existingPaths.containsAll(canonical)) return Decision(null)

        val clean = instruction.trim()
        if (clean.isEmpty() || buildOrRedesign.containsMatchIn(clean)) return Decision(null)

        val explicit = WorkspaceWebsiteGeneration.PATHS.filter { path ->
            Regex("""(?i)(?:^|\s|[("'`])${Regex.escape(path)}(?:$|\s|[)"'`,.;:])""")
                .containsMatchIn(clean)
        }
        if (explicit.size > 1) return Decision(null)
        if (explicit.size == 1) return Decision(explicit.single())

        val style = styleSignal.containsMatchIn(clean)
        val script = scriptSignal.containsMatchIn(clean)
        val html = htmlSignal.containsMatchIn(clean)
        val matches = listOf(style, script, html).count { it }
        if (matches != 1) return Decision(null)

        return Decision(when {
            style -> "style.css"
            script -> "script.js"
            else -> "index.html"
        })
    }
}
