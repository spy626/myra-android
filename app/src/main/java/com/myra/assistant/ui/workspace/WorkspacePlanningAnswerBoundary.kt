package com.myra.assistant.ui.workspace

/**
 * Read-only, CURRENT-turn planning constraints. No second AI model, storage or router.
 * Never rewrites the user's original text or authorizes a tool.
 */
internal object WorkspacePlanningAnswerBoundary {
    private val desktopSetup = Regex(
        """(?i)\b(?:install|download|set\s*up|configure|open|launch|create|initialize)\b.{0,65}\b""" +
        """(?:android\s+studio|desktop\s+ide|android\s+sdk|emulator|gradle|empty\s+activity)\b|""" +
        """\b(?:android\s+studio|sdk|emulator)\b.{0,35}\b(?:install|setup|set\s*up)\b"""
    )
    private val implementing = Regex(
        """(?i)\b(?:create|write|edit|generate|implement|add|build|start)\b.{0,50}""" +
        """(?:\.xml\b|\.kt\b|\.java\b|(?:actual|working)\s+source\s+code\b|""" +
        """\b(?:login|checkout)\s+backend\b)"""
    )
    private val warning = Regex(
        """(?i)^\s*(?:[-*+]\s+|\d{1,2}[.)]\s+|#{1,3}\s+)?""" +
        """(?:(?:do\s+not|don't|dont|never|avoid|skip|no\s+need\s+to|""" +
        """not\s+yet|mat|nahi|nahin|without|instead\s+of|""" +
        """later(?:\s+only)?|optional\s+later)\b)"""
    )
    // "1. **Note:** Don't install Android Studio" is a warning, not an action.
    private val warnedAction = Regex(
        """(?i)\b(?:do\s+not|don't|dont|never|avoid|skip|mat|nahi|nahin)\b.{0,45}\b""" +
        """(?:install|download|open|start|create|write|set\s*up|configure|launch)\b"""
    )
    private val fencedCode = Regex("""(?m)^\s*```(?:kotlin|java|xml|html|javascript|js|python|bash|sql)\b""")

    fun instructions(latest: String): String {
        if (WorkspacePracticalPlanningGuide.instructions(latest).isBlank()) return ""
        val shape = WorkspacePlanningBrief.parse(latest)
        return buildString {
            appendLine("CURRENT-TURN ANSWER ACCEPTANCE (read-only, not execution permission):")
            if (shape.stepCount != null)
                appendLine("- Exactly " + shape.stepCount + " MAIN numbered actions. No extra pseudo-numbered steps.")
            if (shape.phoneOnly) {
                appendLine("- Phone-only access: do NOT prescribe Android Studio, desktop SDK/emulator or PC installation as a NOW step.")
                appendLine("- An Android phone does NOT imply a native APK request. Explicit target: " + shape.platform + ".")
            }
            if (shape.freeOnly)
                appendLine("- Stay within zero budget; never presume a card, subscription or paid trial.")
            if (shape.adviceOnly) {
                appendLine("- PLANNING-ONLY HARD STOP: output a written plan/sketch/checklist. NO coding, code fence, new project, signup, installations, XML, Gradle, backend or running tools.")
                appendLine("- No implementation as step 2 or 3. A future development route is context ONLY, not a TODAY instruction.")
            }
            appendLine("- Start from the end user's first useful journey. Give one coherent route and a SHORT reason.")
            appendLine("- Natural Roman Hinglish. Short intro, contextual headings and distinct numbered actions/bullets. No rigid Kahan/Kya/Result, invented icons or screenshots.")
            append("- Cross-check each action against the user's exact device, budget and prohibition.")
        }
    }

    /**
     * Reject only obvious implementation instructions that directly contradict
     * an explicit planning-only turn. No silent rewriting or automatic second call.
     */
    fun violation(latest: String, completedReply: String): String? {
        if (WorkspacePracticalPlanningGuide.instructions(latest).isBlank()) return null
        val shape = WorkspacePlanningBrief.parse(latest)
        if (!shape.adviceOnly) return null
        if (fencedCode.containsMatchIn(completedReply))
            return "LYRA gave code although you asked for planning only. Reply not saved; tap Retry if useful."
        for (raw in completedReply.lineSequence().take(160)) {
            val line = raw.trim()
            if (line.isBlank() || warning.containsMatchIn(line) ||
                warnedAction.containsMatchIn(line)) continue
            if (implementing.containsMatchIn(line))
                return "LYRA started implementation despite your planning-only request. Reply not saved; no automatic or paid retry."
            if (shape.phoneOnly && desktopSetup.containsMatchIn(line))
                return "LYRA prescribed desktop setup despite your phone-only, planning-only request. Reply not saved; no paid retry."
        }
        return null
    }

    fun requireAcceptable(latest: String, completedReply: String): String {
        violation(latest, completedReply)?.let { throw IllegalArgumentException(it) }
        return completedReply
    }
}
