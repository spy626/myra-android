package com.myra.assistant.ui.workspace

/**
 * A narrow projection of the selected chat's USER statements. It does not invent
 * destinations, equate an undisclosed plan with an undecided one, or turn an
 * assistant's guesses into facts. Unclear and unrelated cases go to ordinary Chat.
 */
internal object WorkspaceChatPlanStatus {
    private val plan = Regex("""(?iu)\b(?:plan|plans|schedule|agenda)\b|प्लान|योजना|منصوبہ""")
    private val own = Regex("""(?iu)\b(?:mera|meri|mere|maine|mene|meine|my|our|i|we|hamara|humara)\b|मेरा|मेरी|मैंने|میرا|میں""")
    private val question = Regex("""(?iu)\b(?:kya|kia|what|which|did|have|batao|tell|remember)\b|क्या|بتاؤ|کیا|[?؟]""")
    private val decisionQuestion = Regex("""(?iu)\b(?:decid(?:e|ed)|finali[sz](?:e|ed)|tay)\b|तय|فیصلہ""")
    private val decision = Regex("""(?iu)\b(?:decid(?:e|ed)|finali[sz](?:e|ed)|tay)\b|तय|فیصلہ""")
    private val withholding = Regex(
        """(?iu)\b(?:nahi|nahin|nehi|nhi|not|haven't|hasn't|didn't)\b.{0,45}\b(?:bataya|batayi|bataye|told|shared|revealed?)\b|\b(?:bataya|batayi|bataye|told|shared|revealed?)\b.{0,30}\b(?:nahi|nahin|nehi|nhi|not)\b|नहीं.{0,24}बताया|بتایا.{0,24}نہیں""")
    private val cancelled = Regex("""(?iu)\b(?:cancel(?:led|ed)?|cancell?ation|radd)\b|रद्द|منسوخ""")
    private val negatedAction = Regex(
        """(?iu)\b(?:nahi|nahin|nehi|nhi|not|won't|wont)\b.{0,25}\b(?:jaa?unga|jaa?ungi|jaa?enge|going|go|attend|visit|karunga|karungi)\b|नहीं.{0,30}(?:जाऊँगा|जाऊंगी|जाऊँगी|जाना)""")
    private val affirmativeAction = Regex(
        """(?iu)\b(?:jaa?unga|jaa?ungi|jaa?enge|jaunga|jaungi|going|will|attend|visit|karunga|karungi)\b|जाऊँगा|जाऊँगी""")
    private val acknowledgement = Regex("""(?iu)^\s*(?:ok(?:ay)?|theek|thik|sahi|haan|han|achha|accha)(?:\s+(?:hai|he|bro|yaar))?\s*[!?.🙂😄 ]*$""")
    private val english = Regex("""(?iu)\b(?:what|which|did|have|my|our)\b""")

    private enum class State { CANCELLED_UNDISCLOSED, DECIDED_UNDISCLOSED }
    private data class Evidence(val state: State, val quote: String)

    private fun evidence(raw: String): Evidence? {
        val text = raw.trim()
        if (text.length !in 8..260 || text.any(Char::isISOControl) || '?' in text || '؟' in text) return null
        // A user-authored explicit decision is stronger and newer than a past cancellation.
        if (plan.containsMatchIn(text) && decision.containsMatchIn(text) &&
            withholding.containsMatchIn(text)) return Evidence(State.DECIDED_UNDISCLOSED, text)
        if (!cancelled.containsMatchIn(text) && !negatedAction.containsMatchIn(text)) return null
        // A proposed cancellation is not an accomplished cancellation.
        if (Regex("""(?iu)\b(?:should|shall|might|maybe|soch|chahiye|karun|karu)\b""")
                .containsMatchIn(text)) return null
        val negation = negatedAction.find(text)
        val remainder = if (negation != null) text.substring(negation.range.last + 1)
            else text.substring(cancelled.find(text)!!.range.last + 1)
        // 'Old place nahi jaunga, new place jaunga' states a replacement. Do not erase it.
        if (affirmativeAction.containsMatchIn(remainder)) return null
        // A new activity could be stated without a verb: leave ambiguity to Chat.
        if (Regex("""(?iu)\b(?:instead|rather|lekin|but|magar)\b""").containsMatchIn(remainder) &&
            !cancelled.containsMatchIn(remainder)) return null
        return Evidence(State.CANCELLED_UNDISCLOSED, text)
    }

    /** Null means ordinary Chat should handle it; no guessed plan is ever stored. */
    fun answer(messages: List<WorkspaceConversationStore.Message>): String? {
        val latest = messages.lastOrNull()?.takeIf { it.role == "user" }?.text?.trim() ?: return null
        if (latest.length !in 8..220 || !plan.containsMatchIn(latest) ||
            !own.containsMatchIn(latest) || !question.containsMatchIn(latest)) return null
        // Skip only simple acknowledgements, not unrelated tasks or another plan's update.
        val previous = messages.dropLast(1).asReversed().filter { it.role == "user" }
            .take(4).firstOrNull { !acknowledgement.matches(it.text.trim()) }?.text ?: return null
        val known = evidence(previous) ?: return null
        val askedWhetherDecided = decisionQuestion.containsMatchIn(latest)
        return when (known.state) {
            State.DECIDED_UNDISCLOSED -> if (english.containsMatchIn(latest))
                "Yes, you said you have decided on a plan, but you haven't told me what it is yet."
            else "Haan, tumne plan decide kar liya hai, par kaunsa plan hai woh abhi mujhe nahi bataya."
            State.CANCELLED_UNDISCLOSED -> when {
                askedWhetherDecided && english.containsMatchIn(latest) ->
                    "You said the earlier plan was cancelled. You haven't said whether you decided on a new one."
                askedWhetherDecided ->
                    "Tumne purana plan cancel kiya tha. Naya plan decide kiya hai ya nahi, woh abhi nahi bataya."
                english.containsMatchIn(latest) ->
                    "Your latest plan update was: “${known.quote}” You haven't shared a replacement yet."
                else -> "Tumne kaha tha: “${known.quote}” Naya plan abhi mujhe nahi bataya."
            }
        }
    }
}
