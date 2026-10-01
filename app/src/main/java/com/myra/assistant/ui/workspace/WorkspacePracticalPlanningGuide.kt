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
            - Recommend ONE coherent feasible starting route with a brief reason based on those constraints. Do not dump disconnected, incompatible frameworks or a menu of tools as the default answer. Alternatives are appropriate if the user asks to compare or the primary route has a material caveat; explain trade-offs without pretending there is one universal choice.
            - If the user asks for a numbered count, give exactly that many MAIN steps. Each step must tell them what to DO and what small concrete result to expect. Put the steps in dependency order, starting with the smallest viable scope; defer optional/advanced setup.
            - For a beginner, explain unfamiliar terms briefly. Tools mentioned must have a purpose in the selected route, and must not quietly violate stated free/mobile/privacy constraints. Do not claim a tool is free or currently available unless that is established; qualify uncertainty where necessary.
            - Keep advice actionable rather than a generic list of tutorials; finish with the next immediate user action when useful. Follow their language and requested level of detail.
            - If they request a plan only, do NOT claim to have coded, created files, installed software, dispatched a build, or made external changes. Even if the user mentions making an app, advice is not execution permission; only the separate current-turn gate can authorize mutation.
        """.trimIndent()
    }
}
