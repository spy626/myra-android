package com.myra.assistant.data.memory

import com.myra.assistant.agent.GeneralVerificationStatus
import com.myra.assistant.agent.ToolCapability
import com.myra.assistant.agent.VerifiedSearchStrategyFeedback

/**
 * Codec for the existing single AIRI behavior table. These records are execution
 * telemetry, not user facts or a second memory database. No source/user text is retained.
 */
internal object AiriSearchStrategyRetention {
    const val KIND = "VERIFIED_SEARCH_STRATEGY"
    const val MAX_RECORDS = 32
    const val MAX_AGE_MS = 60L * 86_400_000L
    private const val PREFIX = "search-strategy:v1:"

    fun key(value: VerifiedSearchStrategyFeedback.Record): String =
        PREFIX + value.taskKey + ":" + value.capability.name.lowercase()

    fun encode(value: VerifiedSearchStrategyFeedback.Record, at: Long): BehaviorObservationEntity {
        require(VerifiedSearchStrategyFeedback().isValid(value))
        require(at >= 0L)
        val stableKey = key(value)
        return BehaviorObservationEntity(
            patternId = stableKey, stableKey = stableKey, kind = KIND,
            label = value.capability.name, observationCount = 1, sessionCount = 1,
            dayCount = 1, firstObservedAt = at, lastObservedAt = at,
            lastSessionId = value.taskKey.take(32), lastDayBucket = at / 86_400_000L,
            state = value.status.name, confidence = 1.0, importance = 1,
            metadata = value.scope
        )
    }

    fun decode(row: BehaviorObservationEntity, at: Long): VerifiedSearchStrategyFeedback.Record? {
        if (row.kind != KIND || row.lastObservedAt < 0 || row.lastObservedAt > at ||
            at - row.lastObservedAt > MAX_AGE_MS || row.observationCount != 1
        ) return null
        val capability = runCatching { ToolCapability.valueOf(row.label) }.getOrNull() ?: return null
        val status = runCatching { GeneralVerificationStatus.valueOf(row.state) }.getOrNull()
            ?: return null
        val scope = row.metadata ?: return null
        val suffix = ":" + capability.name.lowercase()
        if (!row.stableKey.startsWith(PREFIX) || !row.stableKey.endsWith(suffix)) return null
        val taskKey = row.stableKey.removePrefix(PREFIX).removeSuffix(suffix)
        val value = VerifiedSearchStrategyFeedback.Record(taskKey, capability, scope, status)
        return value.takeIf { VerifiedSearchStrategyFeedback().isValid(it) &&
            row.patternId == row.stableKey && row.lastSessionId == taskKey.take(32) }
    }

    fun retained(rows: Collection<BehaviorObservationEntity>, at: Long):
        List<VerifiedSearchStrategyFeedback.Record> =
        rows.sortedBy { it.lastObservedAt }.mapNotNull { decode(it, at) }
            .distinctBy { it.taskKey to it.capability }.takeLast(MAX_RECORDS)
}
