package com.myra.assistant.ui.workspace

/**
 * Read-only planning guidance for the existing text-model prompt.
 *
 * This is NOT a planner/executor/router and never starts a tool or grants mutation permission.
 * It improves how the already chosen free Chat provider organizes an advice request, across
 * topics rather than matching any specific app, product, language or recommended tool.
 */
internal object WorkspacePracticalPlanningGuide {
    private val planningCue = Regex(
        """(?iu)\b(?:steps?|plan(?:ning)?|roadmap|start|begin|shuru|""" +
            """what\s+should|how\s+(?:to|should|can)|pehle\s+(?:kya|kaise|kar\p{L}*)|""" +
            """kaise\s+(?:start|shuru|ban\p{L}*|kar\p{L}*|seekh\p{L}*)|""" +
            """kya\s+kar(?:na|u|un)?|suggest|recommend|guide|approach|workflow|setup)\b|""" +
            """(?:सबसे\s+पहले|कैसे\s+शुरू|योजना|कदम)"""
    )
    private val adviceCue = Regex(
        """(?iu)\b(?:steps?|plan|roadmap|first|pehle|how|what\s+should|kya|""" +
            """bata\p{L}*|tell|suggest|recommend|guide|help|chahiye|should|""" +
            """starting|shuru|kaise)\b|\?|(?:बताओ|चाहिए|कैसे)"""
    )
    private val explicitExecution = Regex(
        """(?iu)^\s*(?:now\s+)?(?:build|create|make|implement|code|generate|""" +
            """fix|edit|write)\b.{0,70}\b(?:app|code|file|website|project|screen)\b"""
    )
    private val informationalBoundary = Regex(
        """(?iu)\b(?:do\s+not|don't|dont|without|no|not\s+yet|abhi|sirf|""" +
            """bas|mat|nahi|nehi)\b.{0,48}\b(?:code|coding|build|implement|""" +
            """create|edit|changes?|start)\b"""
    )

    /**
     * One shared current-turn contract for both full and budget-compact prompts.
     * PlanningBrief projects facts; this method alone owns behavioural planning rules.
     * Do not append a second AnswerBoundary prompt after this contract.
     */
    private fun sharedContract(latest: String): String {
        val shape = WorkspacePlanningBrief.parse(latest)
        return buildString {
            appendLine(WorkspacePlanningBrief.instructions(latest))
            if (shape.stepCount != null)
                appendLine("- EXACT MAIN STEP COUNT: give exactly " + shape.stepCount +
                    " primary actions. Use clear markers such as 1., 2., 3. OR Step 1:, Step 2:, Step 3:. Section headings/tables must not create additional action steps.")
            if (shape.phoneOnly) {
                appendLine("- Phone-only access: use the device the user HAS; no desktop-only setup today.")
                appendLine("- Phone-only does NOT itself mean native Android APK. Explicit target: " + shape.platform + ".")
            }
            if (shape.freeOnly)
                appendLine("- Zero-budget: no presumed paid trial, subscription, bank card or paid service.")
            if (shape.adviceOnly) {
                appendLine("- PLANNING-ONLY HARD STOP: today is written advice, feature/customer-flow list, rough Notes/paper screen sketches, sample content or a decision ONLY.")
                appendLine("- SETUP and IMPLEMENTATION are NOT planning: NO coding, signup, builder launch, new project, installation, actual screens, connecting visual blocks, backend, source files, external changes or execution today.")
                appendLine("- Mention future development tools only descriptively under Later, NEVER inside requested NOW steps. No tool or write permission is granted.")
            }
            append("- Original latest USER message remains authoritative; this projection cannot invent facts or override execution gates.")
        }
    }

    fun instructions(latest: String): String {
        val text = latest.trim()
        if (text.length !in 12..3_000 ||
            !planningCue.containsMatchIn(text) ||
            !adviceCue.containsMatchIn(text)) return ""
        if (explicitExecution.containsMatchIn(text) &&
            !informationalBoundary.containsMatchIn(text)) return ""
        val common = sharedContract(text)
        return """
            PRACTICAL PLANNING RESPONSE GUIDANCE — one CURRENT-turn contract:
            $common

            - Follow the latest user's goal. Distinguish the device the user HAS from their target platform. Give ONE coherent feasible starting route and a short reason; do not list unrelated builders.
            - Work stages: IDEATION -> PLANNING -> DESIGN -> SETUP -> IMPLEMENTATION -> TESTING -> DELIVERY. visual no-code implementation (actual screens and block wiring) is still implementation, not a planning sketch.
            - Begin with the END-USER journey and minimum viable FIRST version. Customer features come before optional admin/backend work.
            - Each requested main step needs a distinct action and a small concrete result. For a planning-only request produce planning artifacts, not installations, project creation or external actions.
            - PRESENTATION CONTRACT (native-friendly Markdown): Natural Roman Hinglish when the user writes that way. Short introductory direction, contextual ## headings and the requested numbered/Step-labeled actions. Other bullets and a 2-4-column comparison table ONLY when helpful. Do not force the same visual template on casual chat, invent screenshots/icons/links or cram multiple actions into one paragraph.
            - FINAL SILENT CLARITY CHECK: correct action count, explicit restrictions, phone/budget/target, planning vs build, concise mobile formatting. Never claim work was performed without actual evidence.
        """.trimIndent()
    }

    /** Same shared contract as normal Chat; only the optional editorial advice is shortened. */
    fun compactInstructions(latest: String): String {
        if (instructions(latest).isBlank()) return ""
        val common = sharedContract(latest.trim())
        return """
            PRACTICAL PLANNING (compact Groq Free; same current-turn contract):
            $common

            - Begin with end-user journey and a minimum useful first version; ONE coherent route.
            - Use natural Roman Hinglish if the user does. Requested numbered/Step-labeled actions are mandatory when N is specified; other formatting only when useful.
            - Short action headings and checkable outcomes, no invented results, media or performed work.
        """.trimIndent()
    }
}
