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
        """(?iu)\b(?:check|read|inspect|review|verify|status|dekh\p{L}*|dekho|analyse|analyze|explain|summari[sz]e)\b"""
    )
    private val resultCue = Regex(
        """(?iu)\b(?:green|red|pass(?:ed)?|fail(?:ed)?|status|result|ci|build|workflow|run|commit|artifact|apk|release)\b|#\d+"""
    )
    private val explicitNoMutation = Regex(
        """(?iu)(?:\b(?:do\s+not|don't|dont|never)\b.{0,28}\b(?:change|edit|modify|write|update|push|commit)\b)|""" +
            """(?:\b(?:change|edit|modify|write|update|push|commit)\b.{0,28}\b(?:mat|nahi|nahin|nehi)\b)|""" +
            """(?:\bkuch\s+change\s+mat\b)|(?:\bno\s+changes?\b)"""
    )
    private val questionCue = Regex(
        """(?iu)(?:\?|\b(?:kya|kia|what|which|is|are|did|has|have|can|could|would)\b)"""
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

        val writeAuthorized = WorkspaceExecutionAuthority.allowsCodingMutation(text)
        val readOnly = !writeAuthorized &&
            readCue.containsMatchIn(text) &&
            (resultCue.containsMatchIn(text) ||
                explicitNoMutation.containsMatchIn(text) ||
                questionCue.containsMatchIn(text))
        if (readOnly) {
            return Proposal(Kind.READ_ONLY_VERIFICATION, Effect.READ, 0.93)
        }

        if (writeAuthorized) {
            return Proposal(Kind.ACTION_REQUEST, Effect.WRITE, 0.95)
        }

        return Proposal(Kind.UNKNOWN, Effect.UNKNOWN, 0.50)
    }

    fun instructions(proposal: Proposal): String = when (proposal.kind) {
        Kind.CAPABILITY_QUERY ->
            "TURN INTENT PROPOSAL — interpretation only, never execution authority:\n" +
                "- Kind: CAPABILITY_QUERY; requested effect: NONE.\n" +
                "- Answer what LYRA can/cannot do from authoritative runtime state. " +
                "Do not start, imply, or narrate an action merely because the user asked whether it is possible."
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
