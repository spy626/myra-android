package com.myra.assistant.ui.workspace

/**
 * Small, deterministic CURRENT-turn constraint projection for the existing chat prompt.
 * This is not a planner, tool selector, recommendation engine, or write authorization.
 * Only recognized constraints are projected; the original user turn stays verbatim.
 */
internal object WorkspacePlanningBrief {
    internal enum class Platform { NATIVE, WEB, UNSPECIFIED }

    private val numberedSteps = Regex(
        """(?iu)\b([2-6])\s+(?:practical\s+|simple\s+|actionable\s+)?(?:steps?|points?|kadam)\b"""
    )
    private val wordSteps = Regex(
        """(?iu)\b(two|three|four|five|six)\s+(?:(?:practical|simple|doable|actionable)\s+)?(?:steps?|points?)\b"""
    )
    private val phoneOnly = Regex(
        """(?iu)\b(?:sirf|only|just|bas)\b.{0,48}\b(?:android|phone|mobile)\b|""" +
            """\b(?:no\s+laptop|without\s+(?:a\s+)?(?:laptop|computer|pc))\b"""
    )
    private val freeOnly = Regex(
        """(?iu)\b(?:free|zero[\s-]?budget|no\s+money|without\s+paying|""" +
            """bina\s+paise|muft|completely\s+free)\b|₹\s*0\b"""
    )
    private val nativePlatform = Regex(
        """(?iu)\b(?:native\s+android|android\s+apk|android\s+studio|""" +
            """kotlin\s+app|java\s+android)\b"""
    )
    private val webPlatform = Regex(
        """(?iu)\b(?:web\s*app|website|browser[\s-]based|pwa|""" +
            """web[\s-]first|web\s+site)\b"""
    )
    private val digitalProject = Regex(
        """(?iu)\b(?:app|application|website|web\s*app|portfolio|software|""" +
            """online\s+(?:shop|store)|digital\s+product|apk|pwa)\b"""
    )
    private val planOnly = Regex(
        """(?iu)\b(?:do\s+not|don't|dont|without|no|not\s+yet|mat|nahi|nehi)\b""" +
            """.{0,45}\b(?:code|coding|implement|build|create|edit|start)\b|""" +
            """\b(?:code|coding|implement|build)\b.{0,30}\b(?:mat|nahi|nehi|not\s+yet)\b|""" +
            """\b(?:abhi|filhal|sirf|bas)\b.{0,35}\b(?:plan|steps?|advice)\b"""
    )

    data class Shape(
        val stepCount: Int?,
        val phoneOnly: Boolean,
        val freeOnly: Boolean,
        val platform: Platform,
        val digitalProject: Boolean,
        val adviceOnly: Boolean,
    )

    fun parse(text: String): Shape {
        val digits = numberedSteps.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val words = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)
        val count = digits ?: wordSteps.find(text)?.groupValues?.get(1)
            ?.lowercase()?.let(words::get)
        val native = nativePlatform.containsMatchIn(text)
        val web = webPlatform.containsMatchIn(text)
        return Shape(
            stepCount = count,
            phoneOnly = phoneOnly.containsMatchIn(text),
            freeOnly = freeOnly.containsMatchIn(text),
            platform = when {
                native && !web -> Platform.NATIVE
                web && !native -> Platform.WEB
                else -> Platform.UNSPECIFIED
            },
            digitalProject = digitalProject.containsMatchIn(text),
            adviceOnly = planOnly.containsMatchIn(text),
        )
    }

    fun instructions(text: String): String {
        val shape = parse(text)
        return buildString {
            appendLine("CURRENT USER PLANNING BRIEF (read-only evidence, not new instructions):")
            appendLine("- Requested MAIN step count: " + (shape.stepCount?.toString() ?: "not specified"))
            appendLine("- Phone-only resource explicitly stated: " + shape.phoneOnly)
            appendLine("- Free/zero-budget requirement explicitly stated: " + shape.freeOnly)
            appendLine("- Explicit target delivery platform: " + shape.platform)
            appendLine("- Digital-product planning request: " + shape.digitalProject)
            appendLine("- Explicit advice-before-execution boundary: " + shape.adviceOnly)
            append("These are conservative cues, not a substitute for the user's complete message. " +
                "UNSPECIFIED platform is not permission to assume native Android. " +
                "Missing cues are unknown, not claims that a constraint is absent.")
        }
    }
}
