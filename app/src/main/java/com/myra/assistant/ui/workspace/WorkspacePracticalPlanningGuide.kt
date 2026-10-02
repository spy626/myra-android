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
            PRACTICAL PLANNING RESPONSE GUIDANCE — presentation only; never execution authority:
            $brief

            - Resolve the user's actual outcome, available resources, experience level, cost/device/time restrictions and explicit do-not-do boundaries from their words. Do not invent them.
            - Distinguish the device the user HAS, the platform the finished result should RUN on, and the development method. A phone-only constraint does NOT by itself require a native Android/Java/Kotlin application. If the user explicitly specifies native/web/no-code, respect that. Otherwise consider feasible mobile-browser/web/PWA, native and no-code routes against the stated needs, learning effort, cost and device limitations. Recommend ONE coherent feasible starting route with a brief reason and any material limitation; do not assume native merely from Android ownership, pretend that one platform always wins, or dump disconnected incompatible frameworks. Give alternatives only when asked or when a trade-off materially changes the decision.
            - Start with the intended END-USER journey and a minimum viable FIRST version. Separate customer-facing actions from owner/admin management; prioritize the primary user's necessary flow instead of treating optional dashboards, inventory editing, databases, authentication or payments as automatic first features. Distinguish what to include NOW from what can wait until later.
            - If the user asks for a numbered count, give exactly that many MAIN steps. Each step must tell them what to DO and what small concrete result to expect. Put the steps in dependency order: minimum scope -> a few screens/flow sketches -> fitting free/accessible tool setup ONLY if useful at this stage. Defer IDE installation, Empty Activity/project initialization and backend complexity when the user asks for initial planning only.
            - For a beginner, explain unfamiliar terms briefly. Tools mentioned must have a purpose in the selected route, and must not quietly violate stated free/mobile/privacy constraints. Do not claim a tool is free or currently available unless that is established; qualify uncertainty where necessary.
            - DECISION, NOT MENU: When a beginner needs a practical starting route, select ONE grounded default and say WHY it fits; if important evidence is missing, explain the limitation briefly. Never transfer the choice back as an unexplained "choose A or B", then contradict it by naming A under Later. Give multiple options when the user actually requests comparison; label concrete trade-offs instead of making false free/availability claims. For early-stage digital projects, name ONE tool to open NOW ONLY when the user asks for setup/implementation; for advice-only, written Notes or paper is enough and development tools must not be opened or installed. Name a future development tool only when useful as context, not as today's step. Do not install software or create files merely for advice.
            - Choose the first step by dependency, not tool prestige: end-user flow -> minimal features/screens -> sample content or fitting setup. A customer-oriented shopping app must not accidentally become an owner inventory-editor first; other projects likewise start from their actual primary user's journey. Do not substitute "watch tutorials", "explore apps", or "choose a builder" for a real action. Each step must produce a small checkable artifact (list, 3-4 sketches, sample data, decision), without claiming that artifact already exists.
            - LANGUAGE: Write natural Roman Hinglish in Latin letters for every heading, numbered step, bullet and explanation. Avoid Hindi-script words, formal literary Hindi and machine-like literal translations. Ordinary English technical words are fine.
            - PRESENTATION CONTRACT (native-friendly Markdown, intent-specific rather than a fixed form): Respond directly in the user's language. Let the question determine the layout: short natural conversation stays prose; distinct facts can become brief contextual ## headings and small bulleted lists; an explicit comparison may use a compact 2–3-column Markdown table if useful. If a numbered count is requested, give exactly that many MAIN numbered steps with concise **action** titles, brief plain-language explanation and only useful examples/bullets. The specific WHERE, WHAT and checkable RESULT must be understandable from the substance, NOT printed as repetitive "Kahan / Kya / Result" labels. Don't repeat a recommendation as opening, Use now and Abhi karo. Put the immediate tool/action in step one where relevant; a separate **Later:** note is optional and must not contradict earlier advice. Use semantic section titles rather than "Step 1: Step One". Aim for easily scanned narrow-phone paragraphs, not one massive wall of text. No invented app icons, screenshots, cards or fake product links; Markdown structure is a signal to the native renderer, not an excuse for manufactured data.
            - FINAL SILENT CLARITY CHECK: Before sending, verify that the first step identifies a single usable tool and action (when appropriate), the later method is consistent with that recommendation, the outcome is explicit, the requested MAIN step count is correct, bullets/headings render on a narrow mobile screen, and no "A or B" decision is dumped on a beginner without reason. This is guidance for the existing model, not a claim that live output was automatically validated or phone-tested.
            - Keep advice actionable rather than a generic list of tutorials; follow their language and requested level of detail.
            - PRIORITY: Any explicitly stated phone-only, free-only and planning-only restrictions OVERRIDE generic recommendations above. A desktop IDE, Android Studio, emulator or generated XML/Gradle file must never become a TODAY step for a phone-only planning request. Do not mistake "Android phone" for "native Android APK".
            - If they request a plan only, stay strictly at planning depth. Do not ask for sign-up, installation, opening a builder, making a new project, creating a spreadsheet/backend, authentication, database setup, coding, no code blocks, executable snippets or premature initialization. An optional future implementation tool may be named as a later direction, never as a required TODAY action. The immediate outcome should be a tiny written feature list, customer journey, screen outline or other planning artifact. do NOT claim to have coded, created files, installed software, dispatched a build, or made external changes. Even if the user mentions making an app, advice is not execution permission; only the separate current-turn gate can authorize mutation.
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
