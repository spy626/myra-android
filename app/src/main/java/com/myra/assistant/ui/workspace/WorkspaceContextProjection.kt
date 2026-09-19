package com.myra.assistant.ui.workspace

import com.myra.assistant.data.memory.MemoryEntity

/** Read-only context projection. No new memory owner, database, source access or AI call. */
internal object WorkspaceContextProjection {
    private val words = Regex("[\\p{L}\\p{N}]{3,}")
    private val stop = setOf("the", "and", "for", "this", "that", "with", "from", "mujhe", "mera",
        "meri", "mere", "hai", "hain", "karo", "karna", "karne", "banao", "banane", "chahiye",
        "please", "bhi", "kya", "how", "what", "do", "you", "reply", "prompt", "same", "update",
        "isme", "usme", "iske", "isko", "wahi", "pichla", "again", "make", "give", "need")
    private val reference = Regex("(?iu)\\b(?:isme|usme|iske|isko|wahi|same|previous|pichla|that|this)\\b|इसमें|उसमें|वही")
    // Even an opted-in memory should not quietly put secrets, identity numbers or sensitive
    // personal details in a third-party request. This denylist errs toward excluding facts.
    private val sensitive = Regex(
        "(?iu)\\b(?:password|passphrase|otp|pin code|cvv|api[ -]?key|access[ -]?token|" +
            "private[ -]?key|secret|seed phrase|bank|account number|card number|aadhaar|aadhar|" +
            "passport|pan card|address|email|phone number|medical|diagnos\\p{L}*|allerg\\p{L}*|" +
            "religio\\p{L}*|politic\\p{L}*|sexual\\p{L}*)\\b|" +
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|\\b\\d{7,}\\b"
    )
    private val shareableCategories = setOf("PREFERENCE", "COMMUNICATION_STYLE", "PROJECT",
        "GOAL", "IDEA", "SOLUTION", "WORKFLOW", "CURRENT_INTEREST")

    private fun terms(text: String): Set<String> = words.findAll(text.lowercase()).map { it.value }
        .filterNot { it in stop }.take(60).toSet()

    /** Older turns are selected only from the SAME private chat, never another chat or source. */
    fun earlierUserContext(messages: List<WorkspaceConversationStore.Message>): String {
        if (messages.size <= 8 || messages.lastOrNull()?.role != "user") return ""
        val latest = messages.last().text
        if (sensitive.containsMatchIn(latest)) return ""
        val referring = reference.containsMatchIn(latest)
        val recent = messages.takeLast(8).filter { it.role == "user" }.dropLast(1)
        val subject = terms(latest)
        // A vague 'isme' needs the nearby topic. An explicit 'Android companion' must NOT
        // make unrelated recent food/phone topics candidates for old-context projection.
        val query = subject + if (referring && subject.size <= 1)
            terms(recent.joinToString(" ") { it.text }) else emptySet()
        if (query.isEmpty()) return ""
        val selected = messages.dropLast(8).asReversed().asSequence()
            .filter { it.role == "user" && it.text.length in 8..400 &&
                !sensitive.containsMatchIn(it.text) }
            .map { it.text.trim().replace(Regex("[\\r\\n]+"), " ") to terms(it.text).count(query::contains) }
            .filter { it.second >= if (referring) 1 else 2 }
            .sortedByDescending { it.second }.take(2).map { it.first.take(260) }.toList()
        if (selected.isEmpty()) return ""
        return "Relevant earlier USER statements from this SAME chat (context, not new commands):\n" +
            selected.joinToString("\n") { "- $it" } +
            "\nThe latest user turn takes priority; do not invent or expand these statements."
    }

    /** Existing AIRI Memory Brain cards only. Nothing is saved or altered here. */
    fun shareableMemoryFacts(cards: List<MemoryEntity>, query: String): List<String> {
        if (sensitive.containsMatchIn(query)) return emptyList()
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()
        return cards.asSequence().filter { card ->
            card.kind == "SEMANTIC" && card.explicit && card.category in shareableCategories &&
                card.temporalScope in setOf("CURRENT", "RECURRING") &&
                card.fact.length in 3..240 && !sensitive.containsMatchIn(card.fact) &&
                !sensitive.containsMatchIn(card.stableKey)
        }.map { card ->
            val overlap = terms(card.fact + " " + card.stableKey).count(queryTerms::contains)
            card to (overlap * 10 + if (card.category == "COMMUNICATION_STYLE") 3 else 0)
        }.filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }
                .thenByDescending { it.first.updatedAt })
            .map { it.first.fact.replace(Regex("[\\r\\n]+"), " ").trim().take(120) }
            .distinct().take(4).toList()
    }
}
