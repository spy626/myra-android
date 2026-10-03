package com.myra.assistant.ui.workspace

/**
 * Local, bounded intent evidence for informal Hinglish. This is NOT a second memory store:
 * inspect only user turns in the selected chat, project no raw old turns or source, and
 * clarify when evidence cannot distinguish an AI companion from an AI company.
 */
internal object WorkspacePromptContext {
    enum class Decision { BUILD_COMPANION, BUSINESS_COMPANY, CLARIFY }

    private val token = Regex("[\\p{L}]+")
    private val ai = Regex("(?iu)\\bai\\b|artificial intelligence|एआई|اے[ -]?آئی")
    private val prompt = Regex("(?iu)\\bprompts?\\b|प्रॉम्प्ट|پرومپٹ")
    private val build = Regex("(?iu)\\b(?:banane|banana|banwana|banao|banani|build|develop|create|make|likho|do|de)\\b|बनाने|बनाना|बनाओ")
    private val companion = Regex("(?iu)\\b(?:companion|companions)\\b|कम्पैनियन|کمپینین")
    private val company = Regex("(?iu)\\b(?:company|companies)\\b|कंपनी|کمپنی")
    private val companionCue = Regex("(?iu)\\b(?:android|mobile app|chatbot|assistant|virtual friend|voice|memory|character|personality)\\b|ऐप|आवाज़|याददाश्त")
    private val businessCue = Regex("(?iu)\\b(?:business|startup|revenue|registration|customers|investors|profit|market|firm)\\b|व्यापार|कारोबार")
    private val reference = Regex("(?iu)\\b(?:woh|wo|uska|usi|same|that|previous|pichla|pichhle|iske|iska)\\b|वही|उसका")

    /** Capped distance for near-spellings, not a blanket correction of every user word. */
    private fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            if (current.minOrNull()!! > limit) return limit + 1
            previous = current
        }
        return previous[b.length]
    }

    private fun ambiguous(text: String): Boolean = token.findAll(text.lowercase()).any { match ->
        val word = match.value
        word.startsWith("compa") && word.length in 7..13 &&
            word !in setOf("companion", "companions", "company", "companies") &&
            distance(word, "companion", 3) <= 3 && distance(word, "company", 4) <= 4
    }

    /** Most recent explicit USER topic wins; never infer facts from assistant guesses. */
    private fun precedingTopic(messages: List<WorkspaceConversationStore.Message>): Decision? =
        messages.dropLast(1).asReversed().asSequence().filter { it.role == "user" }
            .take(40).mapNotNull { message ->
                when {
                    companion.containsMatchIn(message.text) -> Decision.BUILD_COMPANION
                    company.containsMatchIn(message.text) -> Decision.BUSINESS_COMPANY
                    else -> null
                }
            }.firstOrNull()

    fun resolve(messages: List<WorkspaceConversationStore.Message>): Decision? {
        val current = messages.lastOrNull()?.takeIf { it.role == "user" }?.text?.trim()?.take(600)
            ?: return null
        // Explicit wording always wins over old context. Ordinary questions are untouched.
        if (companion.containsMatchIn(current) || company.containsMatchIn(current)) return null
        val context = precedingTopic(messages)
        val unclear = ambiguous(current) && ai.containsMatchIn(current) &&
            (prompt.containsMatchIn(current) || build.containsMatchIn(current))
        if (unclear) return when {
            businessCue.containsMatchIn(current) -> Decision.BUSINESS_COMPANY
            companionCue.containsMatchIn(current) -> Decision.BUILD_COMPANION
            else -> context ?: Decision.CLARIFY
        }
        // A short follow-up can refer to earlier explicit user intent in THIS chat only.
        if (prompt.containsMatchIn(current) && reference.containsMatchIn(current) &&
            current.length <= 100) return context
        return null
    }

    fun instructions(decision: Decision): String = when (decision) {
        Decision.BUILD_COMPANION -> WorkspacePromptWriting.instructions(
            "Mujhe ek AI companion app banane ke liye development prompt do") +
            " The current informal spelling is ambiguous. Earlier USER wording or explicit app " +
            "cues in this same conversation establish an AI COMPANION SOFTWARE product, not a " +
            "business company. Keep the user's original request and any stated constraints; " +
            "do not invent past chats, existing features, platforms or project source."
        Decision.BUSINESS_COMPANY -> "The user's informal wording refers to an AI business " +
            "company based on explicit business cues or earlier USER wording in this chat. " +
            "Answer that request, not a companion chatbot. Do not invent private project details."
        Decision.CLARIFY -> "The user's wording could mean either an AI companion app or an AI " +
            "company/business. Do not choose one, draft a business plan or write a development " +
            "prompt yet. Ask only one concise clarification in the user's language: " +
            "'AI companion app banana hai ya AI company/business?'"
    }
}
