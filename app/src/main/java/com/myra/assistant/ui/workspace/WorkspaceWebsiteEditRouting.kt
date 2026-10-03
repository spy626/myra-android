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

    private val clauseSeparator = Regex(
        """(?i)[!?;,\n]+|\.(?=\s|$)|\b(?:but|however|lekin|lakin|magar|instead|rather|while)\b"""
    )
    private val negation = Regex(
        """(?i)\b(?:don't|dont|do\s+not|never|avoid|not|mat|nahi|nahin|nehi)\b|(?:नहीं|मत)"""
    )
    private val negativeTailStart = Regex(
        """(?ix)
        \bwithout\b
        |
        \b(?:and|aur)\b(?=[^!?;,\n]{0,64}
            \b(?:don't|dont|do\s+not|never|avoid|mat|nahi|nahin|nehi)\b)
        """.trimIndent()
    )
    private const val NEGATIVE_TAIL = "__LYRA_NEGATIVE_TAIL__"

    /**
     * Route only from affirmative mutation scope. A preservation clause can name another file
     * without authorizing or requiring an edit to it.
     * The word without is marked before generic clause splitting so only its governed tail is
     * removed; the affirmative text before it remains routable.
     */
    private fun positiveMutationScope(instruction: String): String {
        val marked = instruction.trim()
            .replace(negativeTailStart, "\n$NEGATIVE_TAIL\n")
            .replace(clauseSeparator, "\n")
        var skipNext = false
        val positive = mutableListOf<String>()
        marked.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { clause ->
            if (clause == NEGATIVE_TAIL) {
                skipNext = true
            } else if (skipNext) {
                skipNext = false
            } else if (!negation.containsMatchIn(clause)) {
                positive += clause
            }
        }
        return positive.joinToString("\n")
    }
    fun decide(instruction: String, existingPaths: Set<String>): Decision {
        val canonical = WorkspaceWebsiteGeneration.PATHS.toSet()
        if (!existingPaths.containsAll(canonical)) return Decision(null)

        val clean = instruction.trim()
        if (clean.isEmpty()) return Decision(null)
        val positive = positiveMutationScope(clean)
        if (positive.isEmpty() || buildOrRedesign.containsMatchIn(positive)) return Decision(null)

        val explicit = WorkspaceWebsiteGeneration.PATHS.filter { path ->
            Regex("""(?i)(?:^|\s|[("'`])${Regex.escape(path)}(?:$|\s|[)"'`,.;:])""")
                .containsMatchIn(positive)
        }
        if (explicit.size > 1) return Decision(null)
        if (explicit.size == 1) return Decision(explicit.single())

        val style = styleSignal.containsMatchIn(positive)
        val script = scriptSignal.containsMatchIn(positive)
        val html = htmlSignal.containsMatchIn(positive)
        val matches = listOf(style, script, html).count { it }
        if (matches != 1) return Decision(null)

        return Decision(when {
            style -> "style.css"
            script -> "script.js"
            else -> "index.html"
        })
    }
}
