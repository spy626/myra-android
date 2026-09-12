package com.myra.assistant.data.memory

import java.text.Normalizer
import java.util.Locale

data class LocalRecallIntent(val type: MemoryRecallType, val query: String, val confidence: Double)
data class LocalRecallExecution(val intent: LocalRecallIntent, val outcome: MemoryBrainOutcome.Recalled,
    val intentDurationMs: Long, val retrievalStartDelayMs: Long)

/**
 * Bounded read-only JARVIS fast-lane classifier. Common personal-memory questions resolve locally
 * before the model can decide whether to call a memory tool.
 */
object LocalMemoryRecallRouter {
    private val question = setOf(
        "who", "what", "which", "where", "when", "how", "tell", "show", "remember",
        "kaun", "kya", "kis", "kab", "kaha", "kahan", "kahaan", "kaise", "kaisa", "kaisi", "batao", "yaad",
        "कौन", "क्या", "किस", "कब", "कहाँ", "कैसे", "बताओ", "याद"
    )
    private val possessive = setOf("my", "mine", "i", "me", "mera", "mere", "meri", "mujhe", "main", "maine", "मेरा", "मेरे", "मेरी", "मुझे", "मैं", "मैंने")
    private val friend = setOf("friend", "friends", "dost", "दोस्त", "mitr", "मित्र")
    private val best = setOf("best", "closest", "sabse", "बेस्ट", "सबसे")
    private val preference = setOf("prefer", "preference", "preferences", "pasand", "पसंद", "answers", "answer", "replies", "reply", "jawab", "जवाब")
    private val goal = setOf("goal", "goals", "aim", "target", "lakshya", "लक्ष्य")
    private val project = setOf("project", "projects", "परियोजना")
    private val episode = setOf(
        "episode", "event", "happened", "did", "kiya", "khela", "last", "recent", "kab",
        "went", "visited", "trip", "travel", "gaya", "gya", "gaye", "ghumne", "ghoomne", "gumne",
        "saath", "saat", "where", "kaha", "kahan", "kahaan", "घटना", "किया", "खेला", "कब", "कहाँ"
    )
    private val transaction = setOf("saved", "save", "updated", "update", "deleted", "delete", "failed", "succeeded", "transaction", "operation", "सहेजा", "बदला", "हटाया")
    private val memory = setOf(
        "memory", "memories", "remember", "yaad", "know", "knows", "known",
        "jaante", "jante", "jaanta", "janta", "malum", "maalum", "मेमोरी", "याद"
    )
    private val identity = setOf("name", "naam", "नाम")
    private val mutation = setOf("save", "add", "change", "update", "delete", "remove", "forget", "rename", "rakh", "jodo", "badlo", "hata", "bhool", "सहेज", "जोड़", "बदल", "हटा", "भूल")

    fun classify(evidence: AuthoritativeMemoryTurnEvidence): LocalRecallIntent? {
        val text = normalize(evidence.canonicalText + " " + evidence.displayText)
        val tokens = text.split(' ').filter(String::isNotBlank).toSet()
        val asks = evidence.variants.any { it.trim().endsWith('?') } || tokens.any(question::contains)
        val personal = tokens.any(possessive::contains)
        val eventRecall = isEventRecall(tokens)
        if (!asks || (!personal && !eventRecall)) return null
        if (tokens.any(mutation::contains) && tokens.none(question::contains)) return null
        val type = when {
            eventRecall -> MemoryRecallType.EPISODES
            tokens.any(friend::contains) && tokens.any(best::contains) -> MemoryRecallType.BEST_FRIEND
            tokens.any(friend::contains) -> MemoryRecallType.FRIENDS
            tokens.any(goal::contains) -> MemoryRecallType.GOALS
            tokens.any(project::contains) -> MemoryRecallType.PROJECTS
            tokens.any(transaction::contains) && tokens.any(memory::contains) -> MemoryRecallType.LAST_TRANSACTION
            tokens.any(episode::contains) -> MemoryRecallType.EPISODES
            tokens.any(preference::contains) -> MemoryRecallType.PREFERENCES
            tokens.any(identity::contains) -> MemoryRecallType.GENERAL
            tokens.any(memory::contains) -> MemoryRecallType.GENERAL
            else -> return null
        }
        return LocalRecallIntent(type, evidence.sourceText, .98)
    }

    private fun isEventRecall(tokens: Set<String>): Boolean {
        val companion = tokens.any { it in setOf("saath", "saat", "with") }
        val pastAction = tokens.any {
            it in setOf("went", "visited", "trip", "travel", "gaya", "gya", "gaye", "ghumne", "ghoomne", "gumne")
        }
        val asksWhereOrWhen = tokens.any {
            it in setOf("where", "when", "kaha", "kahan", "kahaan", "kab", "कहाँ", "कब")
        }
        return (companion && asksWhereOrWhen) || (pastAction && asksWhereOrWhen)
    }

    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(Regex("[^\\p{L}\\p{N}?]+"), " ").replace(Regex("\\s+"), " ").trim()
}

/** Service boundary for the no-network fast path. Its only dependency is the production JARVIS owner. */
class LocalFastMemoryLane(private val owner: MemoryBrainCoordinator) {
    suspend fun recall(evidence: AuthoritativeMemoryTurnEvidence): LocalRecallExecution? {
        val intentStarted = System.nanoTime()
        val intent = LocalMemoryRecallRouter.classify(evidence) ?: return null
        val intentDone = System.nanoTime()
        val outcome = owner.recall(intent.query, type = intent.type)
        return LocalRecallExecution(intent, outcome, (intentDone - intentStarted) / 1_000_000, 0)
    }
}
