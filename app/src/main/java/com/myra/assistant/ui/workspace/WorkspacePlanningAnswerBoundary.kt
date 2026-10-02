package com.myra.assistant.ui.workspace

/**
 * Read-only CURRENT-turn acceptance contract. One existing Chat pipeline:
 * no second AI, memory, execution authority or hidden provider request.
 * Setup and visual-builder implementation are not equivalent to planning.
 */
internal object WorkspacePlanningAnswerBoundary {
    enum class Stage { IDEATION, PLANNING, DESIGN, SETUP, IMPLEMENTATION, TESTING, DELIVERY }

    private val fence = Regex("""(?m)^\s*(?:```|~~~)""")
    private val heading = Regex("""^\s*#{1,4}\s+.+$""")
    private val step = Regex("""^\s{0,3}\d{1,2}[.)]\s+""")
    private val future = Regex(
        """(?i)^(?:later|future|next\s+phase|phase\s*[2-9]|baad\s+mein|""" +
            """jab\s+coding\s+shuru|optional\s+later|not\s+now)\b"""
    )
    private val now = Regex("""(?i)^(?:now|today|abhi|first|pehle)\b""")
    private val warning = Regex(
        """(?i)^(?:(?:note|warning|yaad\s+rakho)\s*:\s*)?""" +
            """(?:do\s+not|don't|dont|never|avoid|skip|no\s+need\s+to|""" +
            """not\s+yet|mat|nahi|nahin|without|instead\s+of)\b"""
    )
    private val negatedVerb = Regex(
        """(?i)\b(?:do\s+not|don't|dont|never|avoid|skip|mat|nahi|nahin|no)\s+""" +
            """(?:immediately\s+|abhi\s+)?(?:install|download|open|start|create|""" +
            """write|set\s*up|configure|launch|connect|add|make|build)\b"""
    )
    private val setupVerb = Regex(
        """(?i)\b(?:install|download|sign\s*up|signup|register|""" +
            """set\s*up|setup|configure|initialize|activate)\b"""
    )
    private val openBuildTool = Regex(
        """(?i)\b(?:open|launch|start|use|select|choose|tap|click)\b.{0,45}""" +
            """\b(?:builder|ide|code\s+editor|development\s+platform|""" +
            """new\s+project|project\s+dashboard|app\s+creator)\b"""
    )
    private val projectAction = Regex(
        """(?i)(?:\b(?:new|naya|nayi)\s+project\b.{0,44}""" +
            """\b(?:banao|banalo|create|start|shuru|karo|open|select|choose|tap|click)\b|""" +
            """\b(?:banao|banalo|create|start|shuru|open|select|choose|tap|click)\b""" +
            """.{0,44}\b(?:new|naya|nayi)\s+project\b)"""
    )
    private val actionVerb = Regex(
        """(?i)\b(?:create|banao|banado|banalo|build|implement|add|connect|""" +
            """jodo|link|wire|drag|drop|configure|generate|code|run|preview|publish|deploy|""" +
            """execute|test|make|start|shuru)\b"""
    )
    private val implementationTarget = Regex(
        """(?i)\b(?:screens?|pages?|layouts?|components?|widgets?|blocks?|""" +
            """event\s+handlers?|variables?|databases?|backends?|apis?|""" +
            """working\s+app|actual\s+app|source\s+code)\b|""" +
            """\.(?:xml|kt|java|html|css|js|py)\b"""
    )
    private val sketchArtifact = Regex(
        """(?i)\b(?:sketch|rough|outline|on\s+paper|wireframe|drawing|""" +
            """diagram|feature\s+list|screen\s+list|list\s+of\s+screens|planning\s+only)\b"""
    )
    private val actualArtifact = Regex(
        """(?i)\b(?:actual\s+screens?|working\s+app|in\s+(?:a\s+)?builder|""" +
            """visual\s+blocks?|ui\s+components?)\b"""
    )
    private val buildProduct = Regex(
        """(?i)\b(?:create|banao|build|start|shuru|implement|banado)\b.{0,35}""" +
            """\b(?:working\s+)?(?:app|website|apk|project)\b"""
    )
    private val comparison = Regex(
        """(?i)\b(?:vs\.?|versus|compare|comparison|difference|farq|""" +
            """which\s+is\s+better|konsa\s+better)\b"""
    )

    private fun cleanLine(raw: String): String =
        raw.trim().replace(Regex("""^(?:#{1,4}\s+|\d{1,2}[.)]\s+|[-*+]\s+)"""), "")
            .replace(Regex("""[*_`]+"""), "").trim()

    /**
     * General action categories, not a Sketchware/Android-Studio blacklist.
     * A rough screen sketch is design. A real screen + connected blocks is build.
     */
    internal fun directiveStage(raw: String): Stage? {
        val line = cleanLine(raw)
        if (line.isBlank() || warning.containsMatchIn(line) ||
            future.containsMatchIn(line) || Regex("""(?i)^no\s+(?:setup|installation|coding|implementation|new\s+project)\b""")
                .containsMatchIn(line)) return null
        // Negation only cancels the FIRST directive, not an earlier install
        // followed by "don't code yet" on the same physical line.
        val firstAction = listOfNotNull(setupVerb.find(line)?.range?.first,
            openBuildTool.find(line)?.range?.first,
            projectAction.find(line)?.range?.first,
            actionVerb.find(line)?.range?.first).minOrNull()
        val firstNegation = negatedVerb.find(line)?.range?.first
        if (firstNegation != null &&
            (firstAction == null || firstNegation < firstAction)) return null
        if (projectAction.containsMatchIn(line)) return Stage.SETUP
        if (setupVerb.containsMatchIn(line) || openBuildTool.containsMatchIn(line))
            return Stage.SETUP
        val sketchOnly = sketchArtifact.containsMatchIn(line) &&
            !actualArtifact.containsMatchIn(line) &&
            !Regex("""(?i)\b(?:then|phir|uske\s+baad|and\s+then)\b.{0,55}\b(?:create|connect|build|implement|install|add|run)\b""")
                .containsMatchIn(line)
        if (implementationTarget.containsMatchIn(line) && actionVerb.containsMatchIn(line) &&
            !sketchOnly) return Stage.IMPLEMENTATION
        if ((buildProduct.containsMatchIn(line) ||
                Regex("""(?i)\b(?:app|website|apk|project)\b.{0,30}\b(?:banao|banado|build|create|shuru|start)\b""")
                    .containsMatchIn(line)) && !sketchOnly) return Stage.IMPLEMENTATION
        return null
    }

    fun instructions(latest: String): String {
        if (WorkspacePracticalPlanningGuide.instructions(latest).isBlank()) return ""
        val shape = WorkspacePlanningBrief.parse(latest)
        return buildString {
            appendLine("CURRENT-TURN ANSWER ACCEPTANCE — latest USER request overrides generic advice:")
            if (shape.stepCount != null)
                appendLine("- Exactly " + shape.stepCount + " MAIN numbered actions; each yields a distinct practical planning result.")
            if (shape.phoneOnly) {
                appendLine("- Phone-only access: never prescribe desktop installation as today's action.")
                appendLine("- Android phone is NOT an explicit native APK request. Explicit target: " + shape.platform + ".")
            }
            if (shape.freeOnly) appendLine("- Stay within zero budget. No presumed subscription, payment or card.")
            if (shape.adviceOnly) {
                appendLine("- PLANNING-ONLY HARD STOP: TODAY actions are a feature list, customer journey, rough screen sketch, sample content or written decision only.")
                appendLine("- SETUP and IMPLEMENTATION are NOT planning: no installing ANY IDE/builder, signup, New Project, actual screens, connecting visual blocks, XML/code, database, execution or deployment.")
                appendLine("- Descriptive future tools belong in a separate **Later:** note, not requested MAIN steps. No execution authority granted.")
            }
            appendLine("- One coherent feasible direction and brief why; start with useful end-user flow, not optional admin/backend work.")
            appendLine("- Natural Roman Hinglish. Clear action headings, useful bullets/table, no rigid Kahan/Kya/Result template; casual chat stays conversational.")
            append("- Check original phone, budget, exact count and do-not-do instructions. Never invent media, citations or tool results.")
        }
    }

    /**
     * Inspects completed provider output before saving, preserving its original bytes.
     * Explicit comparisons and separate descriptive Future sections are allowed.
     */
    fun violation(latest: String, completedReply: String): String? {
        if (WorkspacePracticalPlanningGuide.instructions(latest).isBlank()) return null
        val shape = WorkspacePlanningBrief.parse(latest)
        if (!shape.adviceOnly) return null
        if (fence.containsMatchIn(completedReply))
            return "LYRA gave an implementation block although you asked for planning only. Reply not saved; no automatic paid retry."

        val blocks = WorkspaceRichAnswerBlocks.parse(completedReply)
        if (shape.stepCount != null) {
            val given = blocks.filterIsInstance<WorkspaceRichAnswerBlocks.Block.Numbered>()
                .sumOf { it.items.size }
            if (given != shape.stepCount)
                return "LYRA did not follow your requested " + shape.stepCount +
                    " numbered planning steps. Reply not saved; tap Retry if useful."
        }

        // Comparison prose is still checked for orders; descriptive table rows
        // remain data rather than interpreted as the user's next action.
        var laterSection = false
        for (raw in completedReply.lineSequence().take(160)) {
            val line = cleanLine(raw)
            if (line.isBlank()) continue
            if (comparison.containsMatchIn(latest) && raw.trimStart().startsWith("|")) continue
            val isHeading = heading.containsMatchIn(raw) ||
                (raw.trim().startsWith("**") && raw.trim().endsWith("**"))
            if (isHeading) {
                if (future.containsMatchIn(line)) { laterSection = true; continue }
                if (now.containsMatchIn(line)) laterSection = false
            }
            val isStep = step.containsMatchIn(raw)
            if (laterSection) continue
            if (warning.containsMatchIn(line)) continue
            if (future.containsMatchIn(line)) {
                if (!isStep) continue
                val futureBody = future.replaceFirst(line, "").trim()
                if (directiveStage(futureBody) != null)
                    return "LYRA used a requested planning step for later implementation. Reply not saved."
                continue
            }
            val stage = directiveStage(raw) ?: continue
            if (stage == Stage.SETUP || stage == Stage.IMPLEMENTATION) {
                val desktop = shape.phoneOnly &&
                    Regex("""(?i)\b(?:desktop|pc|laptop|android\s+studio|emulator|sdk)\b""")
                        .containsMatchIn(line)
                return if (desktop)
                    "LYRA prescribed desktop setup despite your phone-only planning request. Reply not saved; no paid retry."
                else "LYRA crossed your planning-only boundary into " +
                    stage.name.lowercase() + " (including no-code builder actions). Reply not saved; no automatic or paid retry."
            }
        }
        return null
    }

    fun requireAcceptable(latest: String, completedReply: String): String {
        violation(latest, completedReply)?.let { throw IllegalArgumentException(it) }
        return completedReply
    }
}
