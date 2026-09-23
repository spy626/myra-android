package com.myra.assistant.ui.workspace

/**
 * Current-turn execution authority for Workspace.
 *
 * Conversation text is not an action. A coding lane is entered only when this exact
 * user turn contains an affirmative, unblocked execution instruction. Planning,
 * questions, prompt-writing, deferral and negated actions stay in Chat.
 *
 * This is deliberately local/deterministic: no provider output, prior assistant reply,
 * saved memory or old task can grant authority for the current turn.
 */
internal object WorkspaceExecutionAuthority {
    private enum class State { NONE, EXECUTE, WITHHOLD }
    private data class Scan(val state: State, val type: WorkspaceProjectType? = null)

    private val separators = Regex(
        """(?:[.!?;,\n]+|\b(?:but|however|lekin|lakin|magar|instead|rather|and\s+then|aur\s+phir|then|phir|and|aur)\b)"""
    )

    private val website = Regex(
        """\b(?:website|web\s*site|web\s*app|webapp|landing\s*page|site)\b|वेबसाइट"""
    )
    private val android = Regex(
        """\b(?:android\s*app|android\s*application|mobile\s*app|apk)\b|एंड्रॉयड\s*ऐप"""
    )

    private val createAction = Regex(
        """\b(?:make|build|create|generate|develop|design|code|implement|banao|bana\s*do|banado|banaye|banaiye)\b|बनाओ|बना\s*दो|बनाइए"""
    )
    private val editAction = Regex(
        """\b(?:change|update|fix|modify|add|remove|replace|implement|redesign|code|edit|rewrite|rename|move|delete|create|make|build|badlo|hatao|jodo)\b|बदलो|हटाओ|जोड़ो"""
    )

    /** These ask for information, not execution, when they govern the same clause as an action. */
    private val informational = Regex(
        """\b(?:how\s+to|how\s+do|how\s+can|what\s+is|what\s+are|what\s+would|why|which\s+would|should\s+i|should\s+we|tutorial|explain|tell\s+me\s+about|show\s+me\s+how|difference|teach\s+me|review|analy[sz]e|suggest|recommend)\b|कैसे|क्या\s+है"""
    )

    /** Writing a prompt/plan/spec about coding is not permission to execute that coding. */
    private val promptOrPlanning = Regex(
        """\b(?:prompts?|outline|roadmap|ideas?|suggestions?|questions?|steps|approach|strategy)\b|प्रॉम्प्ट|پرومپٹ"""
    )
    private val planningObject = Regex(
        """\b(?:make|create|generate|write|draft|give)\b.{0,48}\b(?:plan|prompt|outline|roadmap|ideas?|questions?|steps|approach|strategy)\b"""
    )

    private val negation = Regex(
        """\b(?:don't|dont|do\s+not|not|never|avoid|stop|mat|nahi|nahin|nehi)\b|(?:नहीं|मत)"""
    )
    private val deferred = Regex(
        """\b(?:not\s+yet|later|afterwards|for\s+later|baad\s+me|baad\s+mein|baadme|filhal|filhaal)\b"""
    )

    /**
     * A later "only/for now/first ask or explain" clause can narrow an earlier build
     * mention. It is a generic scope rule, not a phrase-specific patch.
     */
    private val restrictedConversation = Regex(
        """(?:\b(?:just|only|sirf|bas|for\s+now|abhi|first|pehle|filhal|filhaal)\b.{0,64}\b(?:ask|pucho|poochho|question|plan|explain|suggest|discuss|tell|review|compare)\b)|(?:\b(?:ask|pucho|poochho)\b.{0,48}\b(?:first|pehle)\b)"""
    )
    private val generalStop = Regex(
        """\b(?:don't\s+do|dont\s+do|do\s+not\s+do|nothing\s+yet|no\s+changes?|change\s+nothing|kuch\s+mat|abhi\s+nahi)\b"""
    )

    private fun normalize(raw: String): String = raw.lowercase()
        .replace('’', '\'')
        .replace(Regex("""[\s\p{Z}]+"""), " ")
        .trim()

    private fun typeIn(text: String): WorkspaceProjectType? {
        val hasWebsite = website.containsMatchIn(text)
        val hasAndroid = android.containsMatchIn(text)
        return when {
            hasWebsite && !hasAndroid -> WorkspaceProjectType.WEBSITE
            hasAndroid && !hasWebsite -> WorkspaceProjectType.ANDROID_APP
            else -> null
        }
    }

    private fun split(raw: String): List<String> {
        val text = normalize(raw)
        if (text.isBlank()) return emptyList()
        return text.split(separators).map(String::trim).filter(String::isNotBlank)
    }

    /**
     * Scan left-to-right. Later explicit clauses can narrow/correct earlier ones:
     * "build it, but don't code yet" withholds; "don't use React, build in HTML" executes.
     */
    private fun scan(raw: String, actions: Regex, needsProjectType: Boolean): Scan {
        val text = normalize(raw)
        if (text.isBlank()) return Scan(State.NONE)
        val globalType = typeIn(text)
        var state = State.NONE
        var chosenType: WorkspaceProjectType? = null

        split(text).forEach { clause ->
            val actionFound = actions.containsMatchIn(clause)
            val clauseType = typeIn(clause) ?: globalType
            val governedByInformation = informational.containsMatchIn(clause)
            val metaRequest = planningObject.containsMatchIn(clause) ||
                (promptOrPlanning.containsMatchIn(clause) && actionFound)
            val blockedAction = actionFound &&
                (negation.containsMatchIn(clause) || deferred.containsMatchIn(clause))
            val explicitConversationOnly = restrictedConversation.containsMatchIn(clause)
            val explicitStop = generalStop.containsMatchIn(clause)

            when {
                explicitConversationOnly || explicitStop -> {
                    state = State.WITHHOLD
                    chosenType = null
                }
                actionFound && (governedByInformation || metaRequest || blockedAction) -> {
                    state = State.WITHHOLD
                    chosenType = null
                }
                actionFound && (!needsProjectType || clauseType != null) -> {
                    state = State.EXECUTE
                    chosenType = clauseType
                }
                state == State.NONE &&
                    (governedByInformation || promptOrPlanning.containsMatchIn(clause)) -> {
                    state = State.WITHHOLD
                }
            }
        }
        return Scan(state, chosenType)
    }

    fun requestedProjectType(message: String): WorkspaceProjectType? {
        val result = scan(message, createAction, needsProjectType = true)
        return result.type.takeIf { result.state == State.EXECUTE }
    }

    fun allowsCodingMutation(message: String): Boolean =
        scan(message, editAction, needsProjectType = false).state == State.EXECUTE
}
