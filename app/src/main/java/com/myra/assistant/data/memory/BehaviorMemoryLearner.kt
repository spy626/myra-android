package com.myra.assistant.data.memory

import android.content.Context
import android.util.Log
import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticRole
import com.myra.assistant.screen.ScreenPrivacyPolicy
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class BehaviorObservationKind { APP_USAGE, YOUTUBE_CHANNEL, CONTENT_TOPIC }

data class BehaviorSignal(
    val kind: BehaviorObservationKind,
    val label: String,
    val sessionId: String,
    val observedAt: Long,
    val safeMetadata: String? = null
)

/** Aggregates bounded evidence; a single observation is never durable memory. */
class BehaviorMemoryLearner(private val repository: MemoryRepository) {
    suspend fun observe(signal: BehaviorSignal): MemoryWriteResult? {
        val key = "behavior:${signal.kind.name.lowercase()}:${token(signal.label)}"
        val old = repository.behavior(key)
        val day = signal.observedAt / DAY_MS
        val updated = BehaviorObservationEntity(
            id = old?.id ?: UUID.randomUUID().toString(), stableKey = key,
            kind = signal.kind.name, label = signal.label.take(80),
            observationCount = (old?.observationCount ?: 0) + 1,
            sessionCount = (old?.sessionCount ?: 0) + if (old?.lastSessionId != signal.sessionId) 1 else 0,
            dayCount = (old?.dayCount ?: 0) + if (old?.lastDayBucket != day) 1 else 0,
            firstObservedAt = old?.firstObservedAt ?: signal.observedAt,
            lastObservedAt = signal.observedAt, lastSessionId = signal.sessionId,
            lastDayBucket = day, metadata = signal.safeMetadata, promotedMemoryId = old?.promotedMemoryId
        )
        repository.recordBehavior(updated)
        safeLog("BEHAVIOR_OBSERVATION kind=${signal.kind} key=$key observations=${updated.observationCount} sessions=${updated.sessionCount} days=${updated.dayCount}")

        var result: MemoryWriteResult? = null
        if (eligible(updated)) {
            val category = when (signal.kind) {
                BehaviorObservationKind.APP_USAGE -> MemoryCategory.APP_USAGE
                BehaviorObservationKind.YOUTUBE_CHANNEL -> MemoryCategory.CONTENT_INTEREST
                BehaviorObservationKind.CONTENT_TOPIC -> MemoryCategory.CURRENT_INTEREST
            }
            val fact = when (signal.kind) {
                BehaviorObservationKind.APP_USAGE -> "Uses ${signal.label} frequently"
                BehaviorObservationKind.YOUTUBE_CHANNEL -> "Frequently watches ${signal.label}"
                BehaviorObservationKind.CONTENT_TOPIC -> "Recently follows ${signal.label}-related content"
            }
            result = repository.saveGrounded(MemoryCandidate(category, fact, key, MemorySensitivity.LOW,
                confidence = .86, source = "behavior_aggregate", provenance = MemoryProvenance.BEHAVIOR_PATTERN,
                observationMetadata = "observations=${updated.observationCount};sessions=${updated.sessionCount};days=${updated.dayCount}"))
                .also { safeLog("BEHAVIOR_PATTERN_PROMOTED kind=${signal.kind} key=$key status=${it::class.simpleName}") }
        }

        if (signal.kind == BehaviorObservationKind.APP_USAGE) updateMostUsedApp(signal.observedAt)
        return result
    }

    /**
     * Most-used is a comparative inference, so it uses a stricter threshold than a
     * normal frequent-app memory and requires a clear lead over the runner-up. When
     * there is no longer a clear/recent leader, the summary is retired instead of
     * leaving a stale "most-used" claim active.
     */
    private suspend fun updateMostUsedApp(now: Long): MemoryWriteResult? {
        val ranked = repository.behaviorByKind(BehaviorObservationKind.APP_USAGE)
            .filter { (now - it.lastObservedAt).coerceAtLeast(0L) < HISTORICAL_MS }
        val top = ranked.firstOrNull()?.takeIf(::eligibleForMostUsed)
        if (top == null) {
            retireMostUsedSummary("no_recent_eligible_leader")
            return null
        }
        val second = ranked.drop(1).firstOrNull()
        val clearlyAhead = second == null ||
            (top.observationCount >= second.observationCount + 3 &&
                top.observationCount * 100 >= second.observationCount * 125)
        if (!clearlyAhead) {
            retireMostUsedSummary("lead_not_clear")
            return null
        }

        val candidate = MemoryCandidate(
            category = MemoryCategory.APP_USAGE,
            fact = "Usually uses ${top.label} the most",
            stableKey = MOST_USED_APP_KEY,
            sensitivity = MemorySensitivity.LOW,
            confidence = .91,
            source = "behavior_rank",
            provenance = MemoryProvenance.BEHAVIOR_PATTERN,
            observationMetadata = "observations=${top.observationCount};sessions=${top.sessionCount};days=${top.dayCount}"
        )
        return repository.saveGrounded(candidate).also {
            safeLog("BEHAVIOR_MOST_USED_APP label=${top.label.take(40)} status=${it::class.simpleName}")
        }
    }

    private suspend fun retireMostUsedSummary(reason: String) {
        if (repository.forgetStableKey(MOST_USED_APP_KEY)) {
            safeLog("BEHAVIOR_MOST_USED_APP_RETIRED reason=$reason")
        }
    }

    suspend fun decay(now: Long) {
        repository.recentBehavior().forEach { observation ->
            val age = now - observation.lastObservedAt
            when {
                age >= INACTIVE_MS -> {
                    repository.forgetStableKey(observation.stableKey)
                    safeLog("BEHAVIOR_PATTERN_DECAYED kind=${observation.kind} key=${observation.stableKey} state=INACTIVE")
                }
                age >= HISTORICAL_MS -> {
                    repository.setLifecycle(observation.stableKey, MemoryLifecycleStatus.HISTORICAL)
                    safeLog("BEHAVIOR_PATTERN_DECAYED kind=${observation.kind} key=${observation.stableKey} state=HISTORICAL")
                }
                age >= WEAKENING_MS -> {
                    repository.setLifecycle(observation.stableKey, MemoryLifecycleStatus.WEAKENING)
                    safeLog("BEHAVIOR_PATTERN_DECAYED kind=${observation.kind} key=${observation.stableKey} state=WEAKENING")
                }
            }
        }
        updateMostUsedApp(now)
    }

    private fun eligible(value: BehaviorObservationEntity) =
        value.observationCount >= 5 && value.sessionCount >= 3 && value.dayCount >= 2

    private fun eligibleForMostUsed(value: BehaviorObservationEntity) =
        value.observationCount >= 8 && value.sessionCount >= 4 && value.dayCount >= 3

    private fun token(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(64)

    private fun safeLog(message: String) {
        runCatching { Log.d("LyraMemoryBrainV2", message) }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val WEAKENING_MS = 30L * DAY_MS
        const val HISTORICAL_MS = 45L * DAY_MS
        const val INACTIVE_MS = 60L * DAY_MS
        const val MOST_USED_APP_KEY = "behavior:most_used_app"
    }
}

/** General topic extraction from safe visible YouTube text; no fixed four-topic list. */
object ContentTopicExtractor {
    private val stopWords = setOf(
        "the", "and", "for", "with", "from", "this", "that", "your", "you", "are", "was", "how",
        "why", "what", "when", "new", "latest", "today", "best", "video", "videos", "watch", "watching",
        "youtube", "shorts", "short", "home", "subscriptions", "subscription", "subscribe", "subscribed",
        "comments", "comment", "share", "like", "views", "view", "hours", "hour", "days", "day", "ago",
        "official", "channel", "live", "playlist", "music", "more", "less", "about", "into", "over", "under",
        "karo", "kaise", "kya", "hai", "mein", "me", "aur", "se", "par", "wala", "wali"
    )

    private val aliases = listOf(
        Regex("\\bartificial\\s+intelligence\\b", RegexOption.IGNORE_CASE) to "AI",
        Regex("\\bmachine\\s+learning\\b", RegexOption.IGNORE_CASE) to "Machine Learning",
        Regex("\\bdeep\\s+learning\\b", RegexOption.IGNORE_CASE) to "Deep Learning",
        Regex("\\bai\\s+agents?\\b", RegexOption.IGNORE_CASE) to "AI agents"
    )

    fun extract(labels: List<String>, maxTopics: Int = 4): List<String> {
        if (labels.isEmpty()) return emptyList()
        val safe = labels.asSequence().map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 3..120 }
            .toList()
        val joined = safe.joinToString(" ")
        val topics = linkedSetOf<String>()
        aliases.forEach { (pattern, label) -> if (pattern.containsMatchIn(joined)) topics += label }

        val counts = linkedMapOf<String, Int>()
        safe.forEach { label ->
            val seenInLabel = mutableSetOf<String>()
            label.lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}+#.]+"), " ")
                .split(Regex("\\s+"))
                .asSequence()
                .map { it.trim('.', '#', '+') }
                .filter { token ->
                    token == "ai" ||
                        (token.length in 3..24 && token !in stopWords && token.any(Char::isLetter) &&
                            !token.all(Char::isDigit))
                }
                .forEach { token ->
                    val canonical = when (token) {
                        "ai" -> "AI"
                        "gemini" -> "Gemini"
                        "android" -> "Android"
                        "gaming", "games" -> "gaming"
                        else -> token.replaceFirstChar { it.uppercase() }
                    }
                    if (seenInLabel.add(canonical)) counts[canonical] = (counts[canonical] ?: 0) + 1
                }
        }
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { it.key }).forEach { topics += it.key }
        return topics.take(maxTopics)
    }
}

/** Piggybacks on already-coalesced ActivityContext publications; no watcher or screenshot. */
object PassiveMemoryObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastObserved = mutableMapOf<String, Long>()
    private var lastDecayDay = -1L

    fun onActivityContext(context: Context, activity: CurrentActivityContext) {
        if (activity.packageName == context.packageName || sensitivePackage(activity.packageName, activity.appLabel)) return
        val labels = activity.visibleElements.map { it.label }.filter {
            it.length in 3..100 && ScreenPrivacyPolicy.sensitiveCategory(it) == null &&
                !ScreenPrivacyPolicy.blocksLongTermMemory(it)
        }
        // A bounded activity window represents a usage session. This avoids treating
        // repeated refreshes as sessions while still allowing later visits to count.
        val sessionId = "${activity.packageName}:${activity.timestamp / SESSION_WINDOW_MS}"
        val signals = mutableListOf(
            BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                activity.appLabel ?: activity.packageName.substringAfterLast('.'),
                sessionId,
                activity.timestamp
            )
        )
        if (activity.packageName.contains("youtube", true)) {
            activity.visibleElements.asSequence()
                .filter { it.role == SemanticRole.CHANNEL_NAME }
                .map { it.label.trim() }
                .filter { it.length in 2..80 }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(2)
                .forEach { signals += BehaviorSignal(BehaviorObservationKind.YOUTUBE_CHANNEL, it, sessionId, activity.timestamp) }

            val topicLabels = activity.visibleElements.asSequence()
                .filter { it.role in setOf(SemanticRole.TEXT, SemanticRole.VIDEO, SemanticRole.VIDEO_CARD, SemanticRole.LIST_ITEM) }
                .map { it.label }
                .filter { it in labels }
                .take(20)
                .toList()
            ContentTopicExtractor.extract(topicLabels).forEach { topic ->
                signals += BehaviorSignal(BehaviorObservationKind.CONTENT_TOPIC, topic, sessionId, activity.timestamp)
            }
        }
        val accepted = synchronized(lastObserved) {
            signals.filter { signal ->
                val key = "${signal.kind}:${signal.label.lowercase()}"
                val last = lastObserved[key] ?: 0L
                if (signal.observedAt - last < OBSERVATION_COOLDOWN_MS) false
                else { lastObserved[key] = signal.observedAt; true }
            }
        }
        if (accepted.isEmpty()) return
        scope.launch {
            val learner = BehaviorMemoryLearner(MemoryRepository(LyraMemoryDatabase.get(context).memoryDao()))
            accepted.forEach { learner.observe(it) }
            val day = activity.timestamp / BehaviorMemoryLearner.DAY_MS
            if (day != lastDecayDay) {
                lastDecayDay = day
                learner.decay(activity.timestamp)
            }
        }
    }

    private fun sensitivePackage(packageName: String, appLabel: String?): Boolean {
        val value = "$packageName ${appLabel.orEmpty()}".lowercase(Locale.ROOT)
        return SENSITIVE_PACKAGE_TOKENS.any(value::contains)
    }

    private const val OBSERVATION_COOLDOWN_MS = 120_000L
    private const val SESSION_WINDOW_MS = 30L * 60_000L
    private val SENSITIVE_PACKAGE_TOKENS = setOf(
        "bank", "banking", "wallet", "paytm", "phonepe", "gpay", "googlepay",
        "authenticator", "password", "bitwarden", "keepass", "1password", "onepassword"
    )
}
