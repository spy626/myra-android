package com.myra.assistant.ui.workspace

/**
 * Recognize a revision of the immediately preceding reusable prompt, not an arbitrary
 * coding request. The selected chat transcript is the only source; no project files,
 * personal memory, other chats or hidden provider calls are read here.
 */
internal object WorkspacePromptFollowUp {
    private val reference = Regex(
        "(?iu)\\b(?:isme|is mein|usme|us mein|iske|iska|isko|iss|usi|wohi|wahi|same|" +
            "previous|pichle|pichla|upar|this|that|it|its)\\b|इसमें|उसमें|इसी|उसी|वही"
    )
    private val revision = Regex(
        "(?iu)\\b(?:chahiye|chaiye|hona|hoga|add|include|update|change|modify|remove|" +
            "replace|edit|revise|extend|also|bhi|jodo|hatao|karo|karna|kar|do)\\b|" +
            "चाहिए|जोड़|हटा|बदल|شامل|چاہیے"
    )
    private val promptLabel = Regex("(?im)^[ \\t]*(?:#{1,3}[ \\t]*)?(?:\\*\\*)?PROMPT(?:\\*\\*)?[ \\t]*:")
    private val newTopic = Regex("(?iu)\\b(?:company|companies|startup|business|revenue|registration)\\b|कंपनी|कारोबार")

    fun looksLikeRevision(text: String): Boolean = text.length in 1..800 &&
        reference.containsMatchIn(text) && revision.containsMatchIn(text) &&
        !newTopic.containsMatchIn(text)

    fun hasPromptLabel(reply: String): Boolean = promptLabel.containsMatchIn(reply)

    /** Only the immediately preceding assistant turn can be the draft being revised. */
    fun kind(messages: List<WorkspaceConversationStore.Message>): WorkspacePromptWriting.Kind? {
        if (messages.size < 3 || messages.last().role != "user") return null
        val latest = messages.last().text
        if (!looksLikeRevision(latest) || WorkspacePromptWriting.kind(latest) != null) return null
        val priorAssistant = messages[messages.lastIndex - 1].takeIf { it.role == "assistant" }
            ?: return null
        val priorUser = messages[messages.lastIndex - 2].takeIf { it.role == "user" }
            ?: return null
        val immediateKind = WorkspacePromptWriting.kind(priorUser.text)
        // A formatted prompt can continue through successive edits; do not treat an
        // unrelated assistant reply as a prompt just because an old chat once had one.
        if (immediateKind == null && !hasPromptLabel(priorAssistant.text)) return null
        return immediateKind ?: messages.dropLast(2).asReversed().asSequence()
            .filter { it.role == "user" }.take(12)
            .mapNotNull { WorkspacePromptWriting.kind(it.text) }.firstOrNull()
    }

    fun instructions(kind: WorkspacePromptWriting.Kind): String =
        "The latest user turn is a REVISION of the directly preceding copyable " +
            (if (kind == WorkspacePromptWriting.Kind.BUILD) "development" else "personality") +
            " prompt, not a request to build the app now or start a new unrelated plan. " +
            "Use the previous assistant's prompt as the draft, but treat earlier USER requests " +
            "as the authority for requirements; previous assistant speculation is not a new " +
            "user requirement. Produce ONE complete updated, reusable prompt with the requested " +
            "change incorporated; retain other user-requested features. Keep the revision focused " +
            "and approximately the draft's length unless more is explicitly requested. " +
            "Do not silently add always-listening, wake-word detection, calls, SMS, broad device " +
            "permissions or unrelated features just because hands-free interaction was requested. " +
            "If Android is already specified, use it directly; do not reconfirm the " +
            "platform or introduce an alternative. " +
            "Describe capabilities to implement, not features already working. If the previous " +
            "prompt is unavailable or insufficient, ask for it rather than inventing it. " +
            "Use plain-text sections on separate lines, WITHOUT code fences: " +
            "INTRO: one natural sentence identifying the requested update. " +
            "TITLE: a concise title for the updated prompt. " +
            "PROMPT: the FULL revised paste-ready prompt starts on the next line. " +
            "NEXT STEP: one short instruction outside the copyable prompt. " +
            "No unrelated feature wishlist or extra offers outside these sections."
}
