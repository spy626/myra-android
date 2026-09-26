package com.myra.assistant.ui.workspace

import java.util.Locale

/** Local, literal recall of what the user wrote in THIS private chat.
 * Never promotes assistant replies, other chats or guessed entities into user evidence.
 * If source selection is ambiguous, it asks rather than inventing an answer.
 * No model call, persistent memory, network access or transcript rewrite.
 */
internal object WorkspaceChatRecallGrounding {
    private const val MAX_QUESTION = 280
    private const val MAX_SOURCE = 600
    private const val MAX_QUOTE = 320
    private val word = Regex("""[\p{L}\p{N}]{2,}""")
    private val englishPast = Regex(
        """(?iu)\b(?:did\s+(?:i|we)\s+(?:say|tell|mention|write|share)|""" +
            """(?:i|we)\s+(?:said|told|mentioned|wrote|shared)|""" +
            """(?:what|where|when|which|who)\s+(?:have|had)\s+(?:i|we)\s+(?:said|told))\b""")
    private val romanPast = Regex(
        """(?iu)\b(?:maine|mene|meine|main\s+ne|humne)\b.{0,140}""" +
            """\b(?:bataya|batayi|bataye|bola|boli|kaha|kahaa|likha|likhi)\b""")
    private val hindiPast = Regex("""(?:मैंने|हमने).{0,140}(?:बताया|बोला|कहा|लिखा)""")
    private val urduPast = Regex("""(?:میں\s+نے|ہم\s+نے).{0,140}(?:بتایا|بولا|کہا|لکھا)""")
    private val question = Regex(
        """(?iu)[?؟]|\b(?:what|where|when|which|who|did|kya|kahan|kahaan|kab|kaun|kis|""" +
            """yaad|remember|remind)\b|क्या|कहाँ|कब|याद|کیا|کہاں|کب""")
    private val previousMessageReference = Regex(
        """(?iu)\\b(?:previous|last|pichla|pichli|pichhle)\\s+(?:user\\s+)?(?:message|msg|text|turn)\\b"""
    )
    private val ignored = setOf(
        "i", "we", "did", "you", "your", "my", "me", "what", "where", "when", "which", "who",
        "how", "say", "said", "tell", "told", "mention", "mentioned", "write", "wrote", "share",
        "shared", "about", "earlier", "before", "last", "time", "the", "this", "that", "a", "an",
        "maine", "mene", "meine", "main", "ne", "humne", "tumhe", "tumko", "mujhe", "mujhse",
        "kya", "kahan", "kahaan", "kab", "kaun", "kis", "bataya", "batayi", "bataye", "bola",
        "boli", "kaha", "kahaa", "likha", "likhi", "tha", "thi", "the", "hai", "hain",
        "ka", "ki", "ke", "ko", "mein", "me", "pehle", "yaad", "remember", "remind", "mujhko"
    )

    private fun terms(text: String): Set<String> = word.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }.filterNot { it in ignored }.take(70).toSet()

    private fun asksAboutOwnPastWords(questionText: String): Boolean =
        questionText.length in 1..MAX_QUESTION &&
            !questionText.contains('\n') &&
            question.containsMatchIn(questionText) &&
            (englishPast.containsMatchIn(questionText) || romanPast.containsMatchIn(questionText) ||
                hindiPast.containsMatchIn(questionText) || urduPast.containsMatchIn(questionText))

    private fun literalExcerpt(source: String, requested: Set<String>): String? {
        val cleaned = source.trim().replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex(""" {2,}"""), " ")
        if (cleaned.length <= MAX_QUOTE && requested.isEmpty() &&
            cleaned.none(Char::isISOControl)) return cleaned
        // Prefer the relevant literal sentence; never paraphrase or truncate an old turn.
        val sentences = cleaned.split(Regex("""(?<=[.!?।])\s+"""))
            .filter { it.length in 1..MAX_QUOTE && it.none(Char::isISOControl) }
        if (sentences.isEmpty()) return null
        val scored = sentences.map { it to terms(it).count(requested::contains) }
        val best = scored.maxOf { it.second }
        return if (requested.isNotEmpty() && best == 0) null
            else scored.first { it.second == best }.first
    }

    /** Null means an ordinary chat turn, not a recall request. */
    fun answer(messages: List<WorkspaceConversationStore.Message>): String? {
        val latest = messages.lastOrNull()?.takeIf { it.role == "user" }?.text ?: return null
        if (!asksAboutOwnPastWords(latest)) return null
        val candidates = messages.dropLast(1).asReversed().asSequence()
            .filter { it.role == "user" && it.text.length in 1..MAX_SOURCE &&
                it.text.none { char -> char.isISOControl() && char !in "\r\n\t" } }
            .map { it.text.trim() }.filter(String::isNotBlank).distinct().take(80).toList()
        val unknown = "Mujhe is chat mein us baat ka clear user message nahi mila, isliye guess nahi karungi."
        if (candidates.isEmpty()) return unknown
        if (previousMessageReference.containsMatchIn(latest)) {
            val excerpt = literalExcerpt(candidates.first(), emptySet()) ?: return unknown
            return if (Regex("""(?iu)\\b(?:what|where|when|which|who|did|remember)\\b""").containsMatchIn(latest))
                "You said: “$excerpt”"
            else "Tumne kaha tha: “$excerpt”"
        }
        val query = terms(latest)
        val ranked = candidates.map { it to terms(it).count(query::contains) }
        val best = ranked.maxOf { it.second }
        if (best == 0 && query.isNotEmpty()) return unknown
        val winners = ranked.filter { it.second == best }
        if (winners.size > 1 && query.isNotEmpty())
            return "Is chat mein is topic par ek se zyada baatein hain. Kis wali ki baat kar rahe ho?"
        val source = winners.first().first
        val excerpt = literalExcerpt(source, query) ?: return unknown
        return if (Regex("""(?iu)\b(?:what|where|when|which|who|did|remember)\b""").containsMatchIn(latest))
            "You said: “$excerpt”"
        else "Tumne kaha tha: “$excerpt”"
    }
}
