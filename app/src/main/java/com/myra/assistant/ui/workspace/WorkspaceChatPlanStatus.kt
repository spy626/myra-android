package com.myra.assistant.ui.workspace

/**
 * Narrow, read-only factual status for an unambiguous cancelled plan. Quotes the latest
 * USER update instead of asking the model to infer whether the person has decided what
 * to do next. It does not parse destinations, remember facts, or invent a new plan.
 * All ambiguous cases stay with the normal model and the current-turn context.
 */
internal object WorkspaceChatPlanStatus {
    private val topic = Regex("""(?iu)\b(?:plan|plans|schedule|agenda)\b|प्लान|योजना|منصوبہ""")
    private val ownership = Regex("""(?iu)\b(?:mera|meri|mere|my|our|hamara|humara)\b|मेरा|मेरी|میرا""")
    private val askingCurrent = Regex("""(?iu)\b(?:kya\s+(?:hai|he|hei)|what(?:'s|\s+is)|which\s+is)\b|क्या\s+है|کیا\s+ہے""")
    private val cancellation = Regex(
        """(?iu)\b(?:nahi|nahin|nehi|nhi|not|won't|wont)\b.{0,45}\b(?:jaa?unga|jaa?ungi|jaa?enge|going|go|attend|visit|karunga|karungi|karoge)\b""")
    private val englishQuestion = Regex("""(?iu)\b(?:what|which|my|our)\b""")

    /** Null: not clearly this narrow state, including any possible replacement in the same update. */
    fun answer(messages: List<WorkspaceConversationStore.Message>): String? {
        val latest = messages.lastOrNull()?.takeIf { it.role == "user" }?.text?.trim()
            ?: return null
        if (latest.length !in 8..200 || !topic.containsMatchIn(latest) ||
            !ownership.containsMatchIn(latest) || !askingCurrent.containsMatchIn(latest)) return null
        val source = messages.dropLast(1).asReversed().firstOrNull { it.role == "user" }?.text?.trim()
            ?: return null
        if (source.length !in 8..260 || source.any { it.isISOControl() }) return null
        val negation = cancellation.find(source) ?: return null
        // An update followed by more words may state a new plan. Never silently erase it.
        val remainder = source.substring(negation.range.last + 1)
            .trim().trimEnd('.', '!', '?', '।', ' ', '…')
        if (remainder.isNotBlank()) return null
        val quote = source.replace(Regex("""\s+"""), " ")
        return if (englishQuestion.containsMatchIn(latest))
            "Your latest update was: “$quote” You haven't shared a replacement plan in this chat yet."
        else "Tumhara latest update tha: “$quote” Is chat mein tumne uske baad koi naya plan nahi bataya."
    }
}
