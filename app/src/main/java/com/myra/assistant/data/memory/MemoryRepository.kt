package com.myra.assistant.data.memory

import java.util.Locale
import java.util.UUID

class MemoryRepository(private val dao: MemoryDao) {
    suspend fun save(candidate: MemoryCandidate, permissionGranted: Boolean = false): MemoryWriteResult {
        return when (MemorySafetyPolicy.decide(candidate)) {
            MemorySaveDecision.REJECT -> MemoryWriteResult.Rejected("This information is unsafe, empty, or too uncertain to remember.")
            MemorySaveDecision.ASK_PERMISSION -> if (!permissionGranted) {
                MemoryWriteResult.NeedsPermission
            } else {
                persist(candidate)
            }
            MemorySaveDecision.AUTO_SAVE -> persist(candidate)
        }
    }

    suspend fun relevant(query: String, limit: Int = 5): List<MemoryEntity> {
        val normalized = normalize(query)
        if (normalized.length < 2) return dao.recent(limit.coerceIn(1, 10))
        return dao.search(normalized, limit.coerceIn(1, 10))
    }

    suspend fun forget(id: String): Boolean =
        dao.deactivate(id, System.currentTimeMillis()) > 0

    suspend fun forgetStableKey(stableKey: String): Boolean =
        dao.deactivateByStableKey(stableKey.trim(), System.currentTimeMillis()) > 0

    suspend fun forgetMatching(query: String): Boolean {
        val match = relevant(query, 10).firstOrNull() ?: return false
        return forget(match.id)
    }

    suspend fun clearAll() = dao.deleteAll()

    private suspend fun persist(candidate: MemoryCandidate): MemoryWriteResult {
        val now = System.currentTimeMillis()
        val existing = dao.findByStableKey(candidate.stableKey)
        val id = existing?.id ?: UUID.randomUUID().toString()
        dao.upsert(
            MemoryEntity(
                id = id,
                stableKey = candidate.stableKey.trim(),
                category = candidate.category.name,
                fact = candidate.fact.trim(),
                normalizedFact = normalize(candidate.fact),
                sensitivity = candidate.sensitivity.name,
                confidence = candidate.confidence.coerceIn(0.0, 1.0),
                source = candidate.source,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastConfirmedAt = now,
                active = true
            )
        )
        return MemoryWriteResult.Saved(id)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
