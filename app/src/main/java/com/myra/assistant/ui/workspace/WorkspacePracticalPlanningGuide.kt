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
            - DECISION, NOT MENU: For an early-stage digital-product plan, clearly name ONE tool the user should open RIGHT NOW (for example the phone's built-in Notes app when the first job is outlining; pick a single concrete tool, not a slash-separated category). State exactly what to do in it and what will be ready afterward. If mentioning a later coding/no-code tool, name ONE coherent later route, say what it will be used for and WHEN it becomes relevant. Never dump "Tool A, B, or C—pick any" on a beginner. Do not demand signup/installation for a task that can first be planned using an already available phone app. Name alternatives only for a requested comparison or a material trade-off, and still identify a practical default if evidence permits.
            - Choose the first step by dependency, not tool prestige: end-user flow -> minimal features/screens -> sample content or fitting setup. A customer-oriented shopping app must not accidentally become an owner inventory-editor first; other projects likewise start from their actual primary user's journey. Do not substitute "watch tutorials", "explore apps", or "choose a builder" for a real action. Each step must produce a small checkable artifact (list, 3-4 sketches, sample data, decision), without claiming that artifact already exists.
            - PRESENTATION CONTRACT (human-friendly Markdown, especially for a beginner): Start with a one-sentence direct recommendation and WHY it fits their constraints. Then show the requested number of concise numbered main steps. Each step uses a short **bold verb/action** and 1-2 plain-language sentences specifying WHERE to act, WHAT to do, and WHAT result should exist. Visually separate steps with readable spacing; no dense wall of text, unexplained technical acronyms, or long nested tool lists. State **Use now:** exactly one clear tool and its immediate action, and **Later (not now):** the separate implementation tool only when relevant; avoid ambiguous tool menus. End with one concrete "Abhi karo" or equivalent next action that a complete beginner could do in two minutes. Do not add a fourth disguised step, repeat the prompt, or drown the answer in caveats. In a simple three-step request, aim for a focused roughly 100-180 word answer unless the user asks for more detail.
            - FINAL SILENT CLARITY CHECK before answering: Can a complete beginner immediately answer "Which ONE app/tool do I open NOW?", "What exact thing do I do there first?", "What small result will I have?", and "Which tool is for LATER rather than today?" If a relevant answer is missing, clarify it in the answer before sending. Check requested step count and no conflicting tool menus. This is model-side answer preparation only; never claim that a tool was inspected, installed, free-verified, used or that the final answer passed a physical test.
            - Keep advice actionable rather than a generic list of tutorials; follow their language and requested level of detail.
            - If they request a plan only, stay at planning depth, with no code blocks, executable snippets, installation commands, file creation or premature project initialization. do NOT claim to have coded, created files, installed software, dispatched a build, or made external changes. Even if the user mentions making an app, advice is not execution permission; only the separate current-turn gate can authorize mutation.
        """.trimIndent()
    }
}
