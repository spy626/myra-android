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
        if (!eligible(updated)) return null
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
        return repository.saveGrounded(MemoryCandidate(category, fact, key, MemorySensitivity.LOW,
            confidence = .86, source = "behavior_aggregate", provenance = MemoryProvenance.BEHAVIOR_PATTERN,
            observationMetadata = "observations=${updated.observationCount};sessions=${updated.sessionCount};days=${updated.dayCount}"))
            .also { safeLog("BEHAVIOR_PATTERN_PROMOTED kind=${signal.kind} key=$key status=${it::class.simpleName}") }
    }

    suspend fun decay(now: Long) {
        repository.recentBehavior().filter { now - it.lastObservedAt >= DECAY_MS }.forEach { observation ->
            repository.behavior(observation.stableKey) ?: return@forEach
            // Raw aggregates are retained for bounded historical evidence. The linked
            // durable memory is made inactive through its stable key.
            repository.forgetStableKey(observation.stableKey)
            safeLog("BEHAVIOR_PATTERN_DECAYED kind=${observation.kind} key=${observation.stableKey}")
        }
    }

    private fun eligible(value: BehaviorObservationEntity) =
        value.observationCount >= 5 && value.sessionCount >= 3 && value.dayCount >= 2

    private fun token(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(64)

    private fun safeLog(message: String) {
        runCatching { Log.d("LyraMemoryBrainV2", message) }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val DECAY_MS = 45L * DAY_MS
    }
}

/** Piggybacks on already-coalesced ActivityContext publications; no watcher or screenshot. */
object PassiveMemoryObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastObserved = mutableMapOf<String, Long>()
    private var lastDecayDay = -1L

    fun onActivityContext(context: Context, activity: CurrentActivityContext) {
        if (activity.packageName == context.packageName) return
        val labels = activity.visibleElements.map { it.label }.filter {
            it.length in 3..100 && ScreenPrivacyPolicy.sensitiveCategory(it) == null &&
                !ScreenPrivacyPolicy.blocksLongTermMemory(it)
        }
        // A bounded activity window represents a usage session. This avoids treating
        // repeated refreshes as sessions while still allowing later visits to count.
        val sessionId = "${activity.packageName}:${activity.timestamp / SESSION_WINDOW_MS}"
        val signals = mutableListOf(BehaviorSignal(BehaviorObservationKind.APP_USAGE,
            activity.appLabel ?: activity.packageName.substringAfterLast('.'), sessionId, activity.timestamp))
        if (activity.packageName.contains("youtube", true)) {
            labels.firstOrNull { label -> activity.visibleElements.any { it.label == label && it.role == SemanticRole.CHANNEL_NAME } }
                ?.let { signals += BehaviorSignal(BehaviorObservationKind.YOUTUBE_CHANNEL, it, sessionId, activity.timestamp) }
            listOf("AI", "Gemini", "Android", "gaming").firstOrNull { topic -> labels.any { it.contains(topic, true) } }
                ?.let { signals += BehaviorSignal(BehaviorObservationKind.CONTENT_TOPIC, it, sessionId, activity.timestamp) }
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

    private const val OBSERVATION_COOLDOWN_MS = 120_000L
    private const val SESSION_WINDOW_MS = 30L * 60_000L
}
