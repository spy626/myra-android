package com.myra.assistant.data.memory

import java.text.Normalizer
import java.util.Locale

data class LocalRecallIntent(val type: MemoryRecallType, val query: String, val confidence: Double)
data class LocalRecallExecution(val intent: LocalRecallIntent, val outcome: MemoryBrainOutcome.Recalled,
    val intentDurationMs: Long, val retrievalStartDelayMs: Long)

/**
 * Bounded read-only classifier for indexed memory dimensions. It cannot create a frame or mutate
 * memory; uncertain/compound language returns null to the existing semantic route.
 */
object LocalMemoryRecallRouter {
    private val question = setOf("who", "what", "which", "how", "tell", "show", "remember", "kaun", "kya", "kis", "kaise", "batao", "yaad", "कौन", "क्या", "किस", "कैसे", "बताओ", "याद")
    private val possessive = setOf("my", "mine", "i", "mera", "mere", "meri", "mujhe", "main", "maine", "मेरा", "मेरे", "मेरी", "मुझे", "मैं", "मैंने")
    private val friend = setOf("friend", "friends", "dost", "दोस्त", "mitr", "मित्र")
    private val best = setOf("best", "closest", "sabse", "बेस्ट", "सबसे")
    private val preference = setOf("prefer", "preference", "preferences", "pasand", "पसंद", "answers", "answer", "replies", "reply", "jawab", "जवाब")
    private val goal = setOf("goal", "goals", "aim", "target", "lakshya", "लक्ष्य")
    private val project = setOf("project", "projects", "परियोजना")
    private val episode = setOf("episode", "event", "happened", "did", "kiya", "khela", "last", "recent", "yesterday", "kal", "kab", "घटना", "किया", "खेला", "कल", "कब")
    private val transaction = setOf("saved", "save", "updated", "update", "deleted", "delete", "failed", "succeeded", "transaction", "operation", "सहेजा", "बदला", "हटाया")
    private val memory = setOf("memory", "remember", "yaad", "मेमोरी", "याद")
    private val mutation = setOf("save", "remember", "add", "change", "update", "delete", "remove", "forget", "rename", "rakh", "jodo", "badlo", "hata", "bhool", "सहेज", "जोड़", "बदल", "हटा", "भूल")
    private val previewQuestion = question - setOf("remember", "yaad", "याद")
    private val previewHardMutation = mutation - setOf("remember", "yaad", "याद")
    private val previewSafeTypes = setOf(
        MemoryRecallType.PREFERENCES, MemoryRecallType.FRIENDS, MemoryRecallType.BEST_FRIEND,
        MemoryRecallType.GOALS, MemoryRecallType.PROJECTS, MemoryRecallType.LAST_TRANSACTION
    )

    fun classify(evidence: AuthoritativeMemoryTurnEvidence): LocalRecallIntent? {
        val text = normalize(evidence.canonicalText + " " + evidence.displayText)
        val tokens = text.split(' ').filter(String::isNotBlank).toSet()
        val asks = evidence.variants.any { it.trim().endsWith('?') } || tokens.any(question::contains)
        if (!asks || tokens.none(possessive::contains)) return null
        if (tokens.any(mutation::contains) && tokens.none(question::contains)) return null
        val type = classifyType(tokens) ?: return null
        return LocalRecallIntent(type, evidence.sourceText, .95)
    }

    /**
     * Conservative read-only preview used only for response ownership and Room prefetch.
     * It never creates a semantic frame and cannot authorize a durable write.
     */
    fun classifyPreview(value: String): LocalRecallIntent? {
        val clean = normalize(value)
        if (clean.isBlank()) return null
        val tokens = clean.split(' ').filter(String::isNotBlank).toSet()
        val asks = value.trim().endsWith('?') || tokens.any(previewQuestion::contains)
        if (!asks || tokens.none(possessive::contains)) return null
        if (tokens.any(previewHardMutation::contains)) return null
        val type = classifyType(tokens) ?: return null
        if (type !in previewSafeTypes) return null
        return LocalRecallIntent(type, value.trim(), .99)
    }

    private fun classifyType(tokens: Set<String>): MemoryRecallType? = when {
        tokens.any(friend::contains) && tokens.any(best::contains) -> MemoryRecallType.BEST_FRIEND
        tokens.any(friend::contains) -> MemoryRecallType.FRIENDS
        tokens.any(goal::contains) -> MemoryRecallType.GOALS
        tokens.any(project::contains) -> MemoryRecallType.PROJECTS
        tokens.any(transaction::contains) && tokens.any(memory::contains) -> MemoryRecallType.LAST_TRANSACTION
        tokens.any(episode::contains) -> MemoryRecallType.EPISODES
        tokens.any(preference::contains) -> MemoryRecallType.PREFERENCES
        else -> null
    }

    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(Regex("[^\\p{L}\\p{N}?]+"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Strict semantic gate for speculative recall voice. Content words, names, numbers and
 * negation stay significant; only low-risk grammar/wrapper tokens are ignored.
 */
object VerifiedMemorySpeechEquivalence {
    private val wrappers = setOf(
        "you", "your", "yours", "user", "users", "the", "a", "an", "am", "are", "is", "was", "were",
        "be", "been", "being", "do", "does", "did", "have", "has", "had", "that", "this", "it", "its",
        "yes", "yeah", "yep", "okay", "ok", "right", "correct", "according", "to", "saved", "memory",
        "says", "said", "i", "me", "my", "mine", "haan", "han", "acha", "accha", "achha", "bilkul",
        "tum", "tumhe", "tumhara", "tumhari", "tumhare", "hai", "hain", "ho", "hoon", "main", "mujhe",
        "mera", "mere", "meri", "ab"
    )
    private val aliases = mapOf(
        "prefers" to "prefer", "preference" to "prefer", "preferences" to "prefer",
        "like" to "prefer", "likes" to "prefer", "liked" to "prefer", "pasand" to "prefer",
        "answers" to "answer", "reply" to "answer", "replies" to "answer", "jawab" to "answer",
        "friends" to "friend", "dost" to "friend", "doston" to "friend", "mitr" to "friend",
        "goals" to "goal", "aim" to "goal", "target" to "goal", "lakshya" to "goal",
        "projects" to "project", "pariyojana" to "project", "shorter" to "short"
    )

    fun matches(spoken: String, verified: String): Boolean {
        val spokenTokens = canonicalTokens(spoken)
        val verifiedTokens = canonicalTokens(verified)
        return spokenTokens.isNotEmpty() && spokenTokens == verifiedTokens
    }

    internal fun canonicalTokens(value: String): List<String> {
        val normalized = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return emptyList()
        return normalized.split(' ').asSequence()
            .filter(String::isNotBlank)
            .filterNot(wrappers::contains)
            .map { token -> aliases[token] ?: token }
            .filterNot(wrappers::contains)
            .toSet().sorted()
    }
}

/** Service boundary for the no-network fast path. Its only dependency is the single memory owner. */
class LocalFastMemoryLane(private val owner: MemoryBrainCoordinator) {
    suspend fun recall(evidence: AuthoritativeMemoryTurnEvidence): LocalRecallExecution? {
        val intentStarted = System.nanoTime()
        val intent = LocalMemoryRecallRouter.classify(evidence) ?: return null
        val intentDone = System.nanoTime()
        val outcome = owner.recall(intent.query, type = intent.type)
        return LocalRecallExecution(intent, outcome, (intentDone - intentStarted) / 1_000_000, 0)
    }

    suspend fun recall(intent: LocalRecallIntent): LocalRecallExecution {
        val started = System.nanoTime()
        val outcome = owner.recall(intent.query, type = intent.type)
        return LocalRecallExecution(intent, outcome, 0, (System.nanoTime() - started) / 1_000_000)
    }
}
