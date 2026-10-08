package com.myra.assistant.ui.workspace

/**
 * Conservative typed interpretation of the newest Workspace turn.
 *
 * This layer is interpretation only. It owns no tools, permissions, connector state or execution
 * authority. WRITE is emitted only when the existing current-turn authority gate also agrees.
 */
internal object WorkspaceSemanticTurnIntent {
    enum class Kind {
        CAPABILITY_QUERY,
        FOLLOW_UP_REFERENCE,
        SOURCE_PROVENANCE_QUERY,
        READ_ONLY_VERIFICATION,
        ACTION_REQUEST,
        UNKNOWN,
    }

    enum class Effect { NONE, READ, WRITE, UNKNOWN }

    data class Proposal(
        val kind: Kind,
        val effect: Effect,
        val confidence: Double,
    )

    private val hypotheticalCapability = Regex(
        """(?iu)(?:\b(?:if|agar)\b.{0,80}\b(?:ask|tell|bolu|boloon|bolun|kahoon|kahu|request)\b.{0,80}\b(?:can|could|would|sakti|sakta|sakte|possible|able)\b)|""" +
            """(?:\b(?:would|could)\s+(?:you|lyra)\s+be\s+able\b)"""
    )
    private val accessCapability = Regex(
        """(?iu)(?:\b(?:do|does)\s+(?:you|lyra)\s+have\b.{0,45}\b(?:access|permission|capability)\b)|""" +
            """(?:\b(?:have|got)\b.{0,25}\b(?:access|permission)\b)|""" +
            """(?:\b(?:access|permission|capability)\b.{0,36}\b(?:hai|he|available|connected|enabled|na|kya)\b)"""
    )
    private val hinglishAbilityQuestion = Regex(
        """(?iu)\b(?:add|change|fix|edit|build|create|make|read|check|inspect|dekh\p{L}*|kar\p{L}*)\b""" +
            """.{0,64}\b(?:sakti|sakta|sakte|paogi|paoge|kar\s+sakti|kar\s+sakta|possible)\b""" +
            """.{0,24}(?:\?|\bkya\b|\bho\b)?\s*$"""
    )
    private val readCue = Regex(
        """(?iu)\b(?:check|fetch|show|get|list|read|inspect|review|verify|status|dekh\p{L}*|dekho|analyse|analyze|explain|summari[sz]e)\b"""
    )
    private val linkReadRequest = Regex(
        """(?iu)(?:\b(?:link|url)\b.{0,48}\b(?:do|de|dena|bhejo|bhej|send|share|show|dikhao|give)\b)|""" +
            """(?:\b(?:do|de|dena|bhejo|bhej|send|share|show|dikhao|give)\b.{0,48}\b(?:link|url)\b)"""
    )
    private val linkMutationCue = Regex(
        """(?iu)\b(?:add|change|fix|edit|modify|implement|create|make|build|remove|replace|redesign|code|update)\b"""
    )
    private val resultCue = Regex(
        """(?iu)\b(?:green|red|pass(?:ed)?|fail(?:ed)?|status|result|ci|build|workflow|run|commit|head|sha|branch|artifact|apk|release)\b|#\d+"""
    )
    private val explicitNoMutation = Regex(
        """(?iu)(?:\b(?:do\s+not|don't|dont|never)\b.{0,40}\b(?:change|edit|modify|write|update|push|commit|execute|run|start)\b)|""" +
            """(?:\b(?:change|edit|modify|write|update|push|commit|execute|run|start)\b.{0,28}\b(?:mat|nahi|nahin|nehi)\b)|""" +
            """(?:\bkuch\s+change\s+mat\b)|(?:\bno\s+.{0,20}\b(?:changes?|writes?|edits?|commits?|push(?:es)?|builds?)\b)|""" +
            """(?:\bread[ -]?only\b)|(?:\bwithout\s+(?:making\s+)?(?:changes?|edits?|writes?|pushing)\b)"""
    )
    private val questionCue = Regex(
        """(?iu)(?:\?|\b(?:kya|kia|what|which|is|are|did|has|have|can|could|would)\b)"""
    )
    private val sourceItemCue = Regex(
        """(?iu)\b(?:comment|line|symbol|method|function|class|code\s+line)\b"""
    )
    private val referenceCue = Regex(
        """(?iu)\b(?:same|last|previous|prior|pehle|pichl\p{L}*|uska|iska|usse|ise|vo|woh|that|it|this)\b"""
    )
    private val githubHistoryCue = Regex(
        """(?iu)\b(?:ci|build|run|workflow|commit|sha|file|files|task|goal|change|branch|repo(?:sitory)?|status|green|red|pass(?:ed)?|fail(?:ed)?|why|purpose|kis\s*liye|kyu|kyun)\b"""
    )

    private fun normalized(raw: String): String = raw.trim()
        .replace('’', '\'')
        .replace(Regex("""[\s\p{Z}]+"""), " ")
        .take(2_000)

    fun propose(raw: String): Proposal {
        val text = normalized(raw)
        if (text.isBlank()) return Proposal(Kind.UNKNOWN, Effect.UNKNOWN, 0.0)

        val capabilityQuestion =
            hypotheticalCapability.containsMatchIn(text) ||
                accessCapability.containsMatchIn(text) ||
                hinglishAbilityQuestion.containsMatchIn(text)
        if (capabilityQuestion) {
            return Proposal(Kind.CAPABILITY_QUERY, Effect.NONE, 0.96)
        }

        // A request to receive/share a link is information retrieval, not mutation.
        // Keep this ahead of the write scan so Hinglish "link do" cannot be mistaken
        // for an execution verb. Explicit requests to add/change a link still remain writes.
        if (linkReadRequest.containsMatchIn(text) && !linkMutationCue.containsMatchIn(text)) {
            return Proposal(Kind.READ_ONLY_VERIFICATION, Effect.READ, 0.98)
        }

        // Read-focused requests with an explicit no-mutation boundary must be resolved
        // BEFORE scanning separated action words. For example, "do not modify files,
        // push commits, or start a build" is one prohibition, not permission to build.
        // This is interpretation only; actual reads still need a separate grounded route.
        if (readCue.containsMatchIn(text) &&
            resultCue.containsMatchIn(text) &&
            explicitNoMutation.containsMatchIn(text)
        ) {
            return Proposal(Kind.READ_ONLY_VERIFICATION, Effect.READ, 0.98)
        }

        val writeAuthorized =
            WorkspaceExecutionAuthority.requestedProjectType(text) != null ||
                WorkspaceExecutionAuthority.allowsCodingMutation(text)
        if (writeAuthorized) {
            return Proposal(Kind.ACTION_REQUEST, Effect.WRITE, 0.95)
        }

        val sourceProvenanceQuestion =
            sourceItemCue.containsMatchIn(text) &&
                questionCue.containsMatchIn(text) &&
                githubHistoryCue.containsMatchIn(text)
        if (sourceProvenanceQuestion) {
            return Proposal(Kind.SOURCE_PROVENANCE_QUERY, Effect.READ, 0.94)
        }

        val followUpReference =
            referenceCue.containsMatchIn(text) &&
                githubHistoryCue.containsMatchIn(text) &&
                (questionCue.containsMatchIn(text) ||
                    text.length <= 120)
        if (followUpReference) {
            return Proposal(Kind.FOLLOW_UP_REFERENCE, Effect.NONE, 0.92)
        }

        val readOnly =
            readCue.containsMatchIn(text) &&
            (resultCue.containsMatchIn(text) ||
                explicitNoMutation.containsMatchIn(text) ||
                questionCue.containsMatchIn(text))
        if (readOnly) {
            return Proposal(Kind.READ_ONLY_VERIFICATION, Effect.READ, 0.93)
        }

        return Proposal(Kind.UNKNOWN, Effect.UNKNOWN, 0.50)
    }

    fun instructions(proposal: Proposal): String = when (proposal.kind) {
        Kind.CAPABILITY_QUERY ->
            "TURN INTENT PROPOSAL — interpretation only, never execution authority:\n" +
                "- Kind: CAPABILITY_QUERY; requested effect: NONE.\n" +
                "- Answer what LYRA can/cannot do from authoritative runtime state. " +
                "Do not start, imply, or narrate an action merely because the user asked whether it is possible."
        Kind.FOLLOW_UP_REFERENCE ->
            "TURN INTENT PROPOSAL — interpretation only, never execution authority:\n" +
                "- Kind: FOLLOW_UP_REFERENCE; requested effect: NONE.\n" +
                "- Resolve the user's deictic reference only against authoritative candidates already present in runtime/context evidence. " +
                "RECENT_VERIFIED_GITHUB_ACTION may be used only when the wording/context points to LYRA's prior connected-repository action. " +
                "Do not treat it as the repository's globally newest external action. If the referent is ambiguous, say what is ambiguous instead of guessing."
        Kind.SOURCE_PROVENANCE_QUERY ->
            "TURN INTENT PROPOSAL — interpretation only, never execution authority:\n" +
                "- Kind: SOURCE_PROVENANCE_QUERY; requested effect: READ.\n" +
                "- A recent task/commit receipt is not proof of why a specific comment, line, symbol, method, function or class exists. " +
                "Only attribute that source item when verified source/history evidence explicitly links it; otherwise say the purpose is not established."
        Kind.READ_ONLY_VERIFICATION ->
            "TURN INTENT PROPOSAL — interpretation only, never execution authority:\n" +
                "- Kind: READ_ONLY_VERIFICATION; requested effect: READ.\n" +
                "- Preserve the no-mutation boundary. A live build/commit/run/status claim still requires " +
                "verified read evidence; do not invent a result."
        Kind.ACTION_REQUEST ->
            "TURN INTENT PROPOSAL — interpretation only, never execution authority:\n" +
                "- Kind: ACTION_REQUEST; proposed effect: WRITE.\n" +
                "- This text does not grant permission by itself. Existing current-turn execution gates " +
                "remain the only mutation authority."
        Kind.UNKNOWN -> ""
    }
}
