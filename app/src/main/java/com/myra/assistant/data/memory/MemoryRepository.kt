package com.myra.assistant.data.memory

import java.util.Locale
import java.util.UUID

class MemoryRepository(private val dao: MemoryDao) {
    suspend fun save(candidate: MemoryCandidate, permissionGranted: Boolean = false): MemoryWriteResult {
        val canonical = MemoryRelationshipPolicy.canonicalize(candidate)
        // Even an otherwise auto-saveable proposal cannot silently replace a unique
        // relationship held by another person.
        if (!permissionGranted && uniqueRelationshipConflict(canonical) != null) {
            return MemoryWriteResult.NeedsPermission
        }
        return when (MemorySafetyPolicy.decide(canonical)) {
            MemorySaveDecision.REJECT -> MemoryWriteResult.Rejected("This information is unsafe, empty, or too uncertain to remember.")
            MemorySaveDecision.ASK_PERMISSION -> if (!permissionGranted) {
                MemoryWriteResult.NeedsPermission
            } else {
                persist(canonical)
            }
            MemorySaveDecision.AUTO_SAVE -> persist(canonical)
        }
    }

    suspend fun relevant(query: String, limit: Int = 5): List<MemoryEntity> {
        val normalized = normalize(query)
        if (normalized.length < 2) return dao.recent(limit.coerceIn(1, 10))
        return dao.search(normalized, limit.coerceIn(1, 10))
    }

    suspend fun isAlreadySaved(candidate: MemoryCandidate): Boolean {
        val canonical = MemoryRelationshipPolicy.canonicalize(candidate)
        return MemoryFactMatcher.isSameActiveFact(dao.findByStableKey(canonical.stableKey), canonical)
    }

    suspend fun uniqueRelationshipConflict(candidate: MemoryCandidate): MemoryEntity? {
        val canonical = MemoryRelationshipPolicy.canonicalize(candidate)
        if (!MemoryRelationshipPolicy.isBestFriend(canonical)) return null
        val candidateName = MemoryRelationshipPolicy.personName(canonical.fact)
        return dao.recent(50).firstOrNull {
            MemoryRelationshipPolicy.isBestFriend(it) &&
                MemoryRelationshipPolicy.personName(it.fact)
                    ?.equals(candidateName, ignoreCase = true) != true
        }
    }

    /** Repairs legacy semantic/deterministic duplicates, keeping the newest fact. */
    suspend fun reconcileUniqueRelationships() {
        val duplicates = dao.recent(50).filter(MemoryRelationshipPolicy::isBestFriend)
        duplicates.drop(1).forEach { dao.deactivate(it.id, System.currentTimeMillis()) }
    }

    suspend fun forget(id: String): Boolean =
        dao.deactivate(id, System.currentTimeMillis()) > 0

    suspend fun forgetStableKey(stableKey: String): Boolean =
        dao.deactivateByStableKey(stableKey.trim(), System.currentTimeMillis()) > 0

    suspend fun forgetMatching(query: String): Boolean {
        val activeMemories = dao.recent(50)
        val match = MemoryForgetMatcher.find(query, activeMemories) ?: return false
        return forget(match.id)
    }

    suspend fun clearAll() = dao.deleteAll()

    private suspend fun persist(candidate: MemoryCandidate): MemoryWriteResult {
        val canonical = MemoryRelationshipPolicy.canonicalize(candidate)
        val now = System.currentTimeMillis()
        if (MemoryRelationshipPolicy.isBestFriend(canonical)) {
            // A confirmed replacement must deactivate old semantic keys as well as the
            // canonical key, otherwise both people leak into recall context.
            dao.recent(50)
                .filter { MemoryRelationshipPolicy.isBestFriend(it) }
                .forEach { dao.deactivate(it.id, now) }
        }
        val existing = dao.findByStableKey(canonical.stableKey)
        val id = existing?.id ?: UUID.randomUUID().toString()
        dao.upsert(
            MemoryEntity(
                id = id,
                stableKey = canonical.stableKey.trim(),
                category = canonical.category.name,
                fact = canonical.fact.trim(),
                normalizedFact = normalize(canonical.fact),
                sensitivity = canonical.sensitivity.name,
                confidence = canonical.confidence.coerceIn(0.0, 1.0),
                source = canonical.source,
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
