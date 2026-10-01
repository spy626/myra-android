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
        return """
            PRACTICAL PLANNING RESPONSE GUIDANCE — presentation only; never execution authority:
            - Resolve the user's actual outcome, available resources, experience level, cost/device/time restrictions and explicit do-not-do boundaries from their words. Do not invent them.
            - Distinguish the device the user HAS, the platform the finished result should RUN on, and the development method. A phone-only constraint does NOT by itself require a native Android/Java/Kotlin application. If the user explicitly specifies native/web/no-code, respect that. Otherwise consider feasible mobile-browser/web/PWA, native and no-code routes against the stated needs, learning effort, cost and device limitations. Recommend ONE coherent feasible starting route with a brief reason and any material limitation; do not assume native merely from Android ownership, pretend that one platform always wins, or dump disconnected incompatible frameworks. Give alternatives only when asked or when a trade-off materially changes the decision.
            - Start with the intended END-USER journey and a minimum viable FIRST version. Separate customer-facing actions from owner/admin management; prioritize the primary user's necessary flow instead of treating optional dashboards, inventory editing, databases, authentication or payments as automatic first features. Distinguish what to include NOW from what can wait until later.
            - If the user asks for a numbered count, give exactly that many MAIN steps. Each step must tell them what to DO and what small concrete result to expect. Put the steps in dependency order: minimum scope -> a few screens/flow sketches -> fitting free/accessible tool setup ONLY if useful at this stage. Defer IDE installation, Empty Activity/project initialization and backend complexity when the user asks for initial planning only.
            - For a beginner, explain unfamiliar terms briefly. Tools mentioned must have a purpose in the selected route, and must not quietly violate stated free/mobile/privacy constraints. Do not claim a tool is free or currently available unless that is established; qualify uncertainty where necessary.
            - Keep advice actionable rather than a generic list of tutorials; finish with the next immediate user action when useful. Follow their language and requested level of detail.
            - If they request a plan only, stay at planning depth, with no code blocks, executable snippets, installation commands, file creation or premature project initialization. Do NOT claim to have coded, created files, installed software, dispatched a build, or made external changes. Even if the user mentions making an app, advice is not execution permission; only the separate current-turn gate can authorize mutation.
        """.trimIndent()
    }
}
