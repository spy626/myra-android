package com.myra.assistant.data.memory

import android.content.Context
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
data class BehaviorSignal(val kind: BehaviorObservationKind, val label: String, val sessionId: String,
    val observedAt: Long, val safeMetadata: String? = null)

/** AIRI muscle memory: observations remain inferred and promote only after repeated evidence. */
class BehaviorMemoryLearner(private val store: AiriMemoryStore) {
    suspend fun observe(signal: BehaviorSignal): MemoryWriteResult? {
        val key = "behavior:${signal.kind.name.lowercase()}:${AiriText.semanticKey(signal.label)}"
        val old = store.behavior(key)
        val stale = old != null && signal.observedAt - old.lastObservedAt >= INACTIVE_MS
        if (stale) store.deleteBehavior(key)
        val prior = old?.takeUnless { stale }; val day = signal.observedAt / DAY_MS
        val observations = (prior?.observationCount ?: 0) + 1
        val sessions = (prior?.sessionCount ?: 0) + if (prior?.lastSessionId != signal.sessionId) 1 else 0
        val days = (prior?.dayCount ?: 0) + if (prior?.lastDayBucket != day) 1 else 0
        val active = observations >= 5 && sessions >= 3 && days >= 2
        val row = BehaviorObservationEntity(prior?.patternId ?: UUID.randomUUID().toString(), key,
            signal.kind.name, signal.label.take(80), observations, sessions, days,
            prior?.firstObservedAt ?: signal.observedAt, signal.observedAt, signal.sessionId, day,
            state = if (active) "ACTIVE" else "OBSERVED", confidence = if (active) .86 else .5,
            importance = if (active) 4 else 1, metadata = signal.safeMetadata)
        store.upsertBehavior(row)
        return if (active) MemoryWriteResult.Saved(row.patternId) else null
    }

    suspend fun decay(now: Long) {
        BehaviorObservationKind.entries.flatMap { store.behaviorByKind(it.name) }.distinctBy { it.patternId }.forEach { row ->
            val age = (now - row.lastObservedAt).coerceAtLeast(0)
            when {
                age >= INACTIVE_MS -> store.deleteBehavior(row.stableKey)
                age >= HISTORICAL_MS -> store.upsertBehavior(row.copy(state = "HISTORICAL"))
                age >= WEAKENING_MS -> store.upsertBehavior(row.copy(state = "WEAKENING"))
            }
        }
    }
    companion object { const val DAY_MS = 86_400_000L; const val WEAKENING_MS = 30L * DAY_MS; const val HISTORICAL_MS = 45L * DAY_MS; const val INACTIVE_MS = 60L * DAY_MS }
}

object ContentTopicExtractor {
    private val stopWords = setOf("the","and","for","with","from","this","that","your","you","are","was","how","what","video","youtube","shorts","watch","channel","live","karo","kaise","kya","hai","mein","aur")
    fun extract(labels: List<String>, maxTopics: Int = 4): List<String> = labels.asSequence()
        .flatMap { it.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}+#.]+"), " ").split(Regex("\\s+")).asSequence() }
        .map { it.trim('.', '#', '+') }.filter { it.length in 3..24 && it !in stopWords && it.any(Char::isLetter) }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        .map { it.key.replaceFirstChar(Char::titlecase) }.take(maxTopics)
}

object PassiveMemoryPrivacyPolicy {
    private val sensitive = setOf("bank","wallet","payment","finance","authenticator","password","vault","medical","health")
    fun blocksApp(packageName: String, appLabel: String?): Boolean = sensitive.any { it in "$packageName ${appLabel.orEmpty()}".lowercase() }
    fun privateContext(labels: List<String>) = labels.any { it.contains("incognito", true) || it.contains("private browsing", true) }
}

object PassiveMemoryObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastObserved = mutableMapOf<String, Long>(); private var lastDecayDay = -1L
    fun onActivityContext(context: Context, activity: CurrentActivityContext) {
        if (activity.packageName == context.packageName || PassiveMemoryPrivacyPolicy.blocksApp(activity.packageName, activity.appLabel)) return
        val prefs = MemoryPrivacyPreferences(context); if (!prefs.passiveAppLearningEnabled && !prefs.passiveContentLearningEnabled) return
        val safeLabels = activity.visibleElements.map { it.label }.filter { it.length in 2..120 && ScreenPrivacyPolicy.sensitiveCategory(it) == null && !ScreenPrivacyPolicy.blocksLongTermMemory(it) }
        if (PassiveMemoryPrivacyPolicy.privateContext(safeLabels)) return
        val session = "${activity.packageName}:${activity.timestamp / SESSION_WINDOW_MS}"
        val signals = mutableListOf<BehaviorSignal>()
        if (prefs.passiveAppLearningEnabled) signals += BehaviorSignal(BehaviorObservationKind.APP_USAGE, activity.appLabel ?: activity.packageName.substringAfterLast('.'), session, activity.timestamp)
        if (prefs.passiveContentLearningEnabled && activity.packageName.contains("youtube", true) && isActiveYouTubeVideoContext(activity, safeLabels)) {
            activity.visibleElements.firstOrNull { it.role == SemanticRole.CHANNEL_NAME && it.label in safeLabels }?.let { signals += BehaviorSignal(BehaviorObservationKind.YOUTUBE_CHANNEL, it.label, session, activity.timestamp) }
            ContentTopicExtractor.extract(currentVideoTopicLabels(activity, safeLabels)).forEach { signals += BehaviorSignal(BehaviorObservationKind.CONTENT_TOPIC, it, session, activity.timestamp) }
        }
        val accepted = synchronized(lastObserved) { signals.filter { val key = "${it.kind}:${it.label.lowercase()}"; val last = lastObserved[key] ?: 0; if (it.observedAt - last < OBSERVATION_COOLDOWN_MS) false else { lastObserved[key] = it.observedAt; true } } }
        if (accepted.isEmpty()) return
        scope.launch {
            val learner = BehaviorMemoryLearner(RoomAiriMemoryStore(LyraMemoryDatabase.get(context)))
            accepted.forEach { learner.observe(it) }
            val day = activity.timestamp / BehaviorMemoryLearner.DAY_MS
            if (day != lastDecayDay) { lastDecayDay = day; learner.decay(activity.timestamp) }
        }
    }
    internal fun isActiveYouTubeVideoContext(activity: CurrentActivityContext, safeLabels: List<String> = activity.visibleElements.map { it.label }): Boolean =
        activity.packageName.contains("youtube", true) && (activity.screenType.contains("VIDEO", true) || activity.visibleElements.any { it.role == SemanticRole.VIDEO || it.role == SemanticRole.VIDEO_CARD }) && safeLabels.isNotEmpty()
    internal fun currentVideoTopicLabels(activity: CurrentActivityContext, safeLabels: List<String> = activity.visibleElements.map { it.label }): List<String> =
        if (!isActiveYouTubeVideoContext(activity, safeLabels)) emptyList() else activity.visibleElements.asSequence().filter { it.role == SemanticRole.VIDEO }.map { it.label.trim() }.filter { it in safeLabels }.take(1).toList()
    private const val OBSERVATION_COOLDOWN_MS = 120_000L; private const val SESSION_WINDOW_MS = 1_800_000L
}
