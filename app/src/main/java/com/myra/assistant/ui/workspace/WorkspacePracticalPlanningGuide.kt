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

    fun instructions(latest: String): String {
        val text = latest.trim()
        if (text.length !in 12..3_000 ||
            !planningCue.containsMatchIn(text) ||
            !adviceCue.containsMatchIn(text)) return ""
        // Do not replace a clear execution request with a planning response. The current-turn
        // execution authority remains outside this read-only prompt guidance.
        if (explicitExecution.containsMatchIn(text) &&
            !informationalBoundary.containsMatchIn(text)) return ""
        val brief = WorkspacePlanningBrief.instructions(text)
        return """
            PRACTICAL PLANNING RESPONSE GUIDANCE — current-turn, read-only:
            $brief

            - Follow the newest USER text. Resolve the intended outcome, device, budget, experience and explicit do-not-do boundaries; invent none. The separate current-turn gate alone controls execution.
            - Distinguish the device the user HAS from the target delivery platform and development method. Phone-only does NOT itself mean native Android APK. Respect explicitly specified native/web/no-code choices; otherwise choose ONE coherent feasible starting route with a brief reason, not a menu of unrelated builders.
            - Work stages: IDEATION (idea) -> PLANNING (feature list/customer flow) -> DESIGN (rough sketches) -> SETUP (install/open builder or initialize project) -> IMPLEMENTATION (make screens/connect blocks/code) -> TESTING -> DELIVERY. Do not collapse setup or visual no-code implementation into planning.
            - Start with the intended END-USER journey and minimum viable FIRST version. Separate customer-facing actions from owner/admin management. Explain NOW versus **Later:** without putting optional database/auth/payments first.
            - If user requests N numbered steps, give exactly that many MAIN steps: short **action** titles, what to DO and what small concrete result to expect. Each step produces a checkable planning artifact, not vague advice to watch tutorials.
            - For explicit advice-only: TODAY may be Notes/paper feature list, customer flow, rough screen sketches, sample content or route decision. Do not ask for sign-up, installation, builder launch, New Project, creating actual screens, connecting visual blocks, backend, executable source or code fences. Future tools can be mentioned descriptively under **Later:**, never as a requested step.
            - For other planning questions, put dependencies in useful order. Do not claim tools are free, secure or available without evidence, and never assume a paid service or automatic subscription.
            - PRESENTATION CONTRACT (native-friendly Markdown): Roman Hinglish in Latin letters, normal familiar words. Open with one helpful route/rationale sentence. Distinct information can become brief contextual ## sections, numbered steps, compact bullet groups or a small 2-4-column comparison table ONLY when useful. Never cram everything into one paragraph, repeat Kahan/Kya/Result labels, fabricate icons/images/links, or promise visual components not available.
            - FINAL SILENT CLARITY CHECK: step count, dependency order, phone/budget/target consistency, planning-vs-implementation stage, real checkable outcome, clean narrow-screen formatting. Preserve the user's original instruction and do NOT claim to have coded, signed up, created files, installed tools, run a build or completed work unless actually verified.
        """.trimIndent()
    }

    /** Concise version of the SAME current-turn guide for Groq's strict Free budget. */
    fun compactInstructions(latest: String): String {
        if (instructions(latest).isBlank()) return ""
        val shape = WorkspacePlanningBrief.parse(latest)
        return buildString {
            appendLine("PRACTICAL PLANNING (compact Groq Free; read-only):")
            appendLine("Every explanatory section, heading and bullet is natural Roman Hinglish, not Hindi script.")
            appendLine("Requested MAIN steps: " + (shape.stepCount ?: "unspecified"))
            appendLine("Phone-only: ${shape.phoneOnly}; free-only: ${shape.freeOnly}.")
            appendLine("Target platform: ${shape.platform}; planning-before-code: ${shape.adviceOnly}.")
            appendLine("The full USER turn below has highest authority; do not invent requirements.")
            appendLine("- Give ONE feasible route with a brief reason, not a menu of unrelated builders.")
            appendLine("- Begin with end-user journey and minimum useful feature/screen outline.")
            appendLine("- Use short contextual headings, bullets and numbered rows only when helpful; no dense wall or rigid Kahan/Kya/Result.")
            appendLine("- If the user requests N steps, give exactly N main actions with a concrete outcome each.")
            appendLine("- Name one NOW tool if necessary and separate any LATER development route. Respect phone, cost and target platform.")
            appendLine("- Planning-only means NO coding, signup, builder launch, new project, spreadsheet, backend, installation or external changes today.")
            if (shape.phoneOnly && shape.adviceOnly) appendLine("- Phone-only + advice-only: zero desktop IDE, Android Studio, SDK/emulator or source files as today's action.")
            append("- Never claim work was already done. Execution permission stays with the existing current-turn authority gate.")
        }
    }

}
