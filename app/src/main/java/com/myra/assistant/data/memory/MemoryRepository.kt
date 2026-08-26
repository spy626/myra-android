package com.myra.assistant.data.memory

import java.util.Locale
import java.util.UUID

class MemoryRepository(private val dao: MemoryDao) {
    suspend fun save(candidate: MemoryCandidate, permissionGranted: Boolean = false): MemoryWriteResult {
        val canonical = MemoryRelationshipPolicy.canonicalizeForSave(
            candidate,
            replaceExisting = permissionGranted
        )
        return when (MemorySafetyPolicy.decide(canonical)) {
            MemorySaveDecision.REJECT -> MemoryWriteResult.Rejected("This information is unsafe, empty, or too uncertain to remember.")
            MemorySaveDecision.ASK_PERMISSION -> if (!permissionGranted) {
                MemoryWriteResult.NeedsPermission
            } else {
                persist(canonical)
            }
            MemorySaveDecision.AUTO_SAVE -> persist(
                canonical,
                replaceBestFriends = permissionGranted && MemoryRelationshipPolicy.isBestFriend(canonical)
            )
        }
    }

    suspend fun relevant(query: String, limit: Int = 5): List<MemoryEntity> {
        val normalized = normalize(query)
        if (normalized.length < 2) return dao.recent(limit.coerceIn(1, 10))
        return dao.search(normalized, limit.coerceIn(1, 10))
    }

    suspend fun isAlreadySaved(candidate: MemoryCandidate): Boolean {
        val canonical = MemoryRelationshipPolicy.canonicalize(candidate)
        if (MemoryRelationshipPolicy.isBestFriend(canonical)) {
            val name = MemoryRelationshipPolicy.personName(canonical.fact)
            return dao.recent(50).any {
                MemoryRelationshipPolicy.isBestFriend(it) &&
                    MemoryRelationshipPolicy.personName(it.fact)?.equals(name, ignoreCase = true) == true
            }
        }
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

    /**
     * Repairs stale pre-canonical rows on reconnect. Before this migration, the first
     * ASR spelling was frozen into both fact and stableKey, so a later correction could
     * sound right in Gemini while persistent recall still returned "Now Pal".
     */
    suspend fun reconcileUniqueRelationships() {
        val now = System.currentTimeMillis()
        val canonicalRows = dao.recent(50)
            .filter(MemoryRelationshipPolicy::isBestFriend)
            .mapNotNull { memory ->
                val name = MemoryRelationshipPolicy.personName(memory.fact) ?: return@mapNotNull null
                val candidate = MemoryRelationshipPolicy.canonicalizeAdditional(
                    MemoryCandidate(
                        category = MemoryCategory.PERSON,
                        fact = "Zopy's best friend is $name",
                        stableKey = MemoryRelationshipPolicy.BEST_FRIEND_KEY,
                        sensitivity = MemorySensitivity.valueOf(memory.sensitivity),
                        confidence = memory.confidence,
                        source = memory.source
                    )
                )
                memory to candidate
            }
        canonicalRows.groupBy { it.second.stableKey }.values.forEach { samePerson ->
            val selected = samePerson.firstOrNull { (memory, canonical) ->
                memory.stableKey == canonical.stableKey
            } ?: samePerson.first()
            val (keeper, canonical) = selected
            samePerson.filter { it.first.id != keeper.id }
                .forEach { (duplicate, _) -> dao.deactivate(duplicate.id, now) }
            if (keeper.stableKey != canonical.stableKey || keeper.fact != canonical.fact) {
                val existingTarget = dao.findByStableKey(canonical.stableKey)
                if (existingTarget != null && existingTarget.id != keeper.id) {
                    persist(canonical, replaceBestFriends = false)
                    dao.deactivate(keeper.id, now)
                } else {
                    dao.rename(
                        keeper.id,
                        canonical.stableKey,
                        canonical.fact,
                        normalize(canonical.fact),
                        now
                    )
                }
            }
        }
    }

    suspend fun saveAdditionalBestFriend(candidate: MemoryCandidate): MemoryWriteResult {
        val additional = MemoryRelationshipPolicy.canonicalizeAdditional(candidate)
        if (!MemoryRelationshipPolicy.isBestFriend(additional)) return save(candidate, permissionGranted = true)
        return when (MemorySafetyPolicy.decide(additional)) {
            MemorySaveDecision.REJECT -> MemoryWriteResult.Rejected("This information is unsafe to remember.")
            else -> persist(additional, replaceBestFriends = false)
        }
    }

    suspend fun forget(id: String): Boolean =
        dao.deactivate(id, System.currentTimeMillis()) > 0

    suspend fun forgetStableKey(stableKey: String): Boolean =
        dao.deactivateByStableKey(stableKey.trim(), System.currentTimeMillis()) > 0

    suspend fun forgetMatching(query: String): Boolean {
        val activeMemories = dao.recent(50)
        val canonicalQuery = BestFriendNameCanonicalizer.canonicalize(query)
        val match = MemoryForgetMatcher.find(canonicalQuery, activeMemories) ?: return false
        return forget(match.id)
    }

    /** Renames the same persistent row so stale spelling and stable key cannot diverge. */
    suspend fun renameBestFriend(oldName: String, correctedName: String): Boolean {
        val memories = dao.recent(50).filter(MemoryRelationshipPolicy::isBestFriend)
        val old = MemoryForgetMatcher.find(oldName, memories) ?: return false
        val canonicalName = BestFriendNameCanonicalizer.canonicalize(correctedName)
        val replacement = MemoryRelationshipPolicy.canonicalizeAdditional(
            MemoryCandidate(
                category = MemoryCategory.PERSON,
                fact = "Zopy's best friend is $canonicalName",
                stableKey = MemoryRelationshipPolicy.BEST_FRIEND_KEY,
                sensitivity = MemorySensitivity.valueOf(old.sensitivity),
                confidence = old.confidence,
                source = old.source
            )
        )
        val existingTarget = dao.findByStableKey(replacement.stableKey)
        if (existingTarget != null && existingTarget.id != old.id) {
            persist(replacement, replaceBestFriends = false)
            return forget(old.id)
        }
        val now = System.currentTimeMillis()
        return dao.rename(old.id, replacement.stableKey, replacement.fact, normalize(replacement.fact), now) > 0
    }

    suspend fun clearAll() = dao.deleteAll()

    private suspend fun persist(
        candidate: MemoryCandidate,
        replaceBestFriends: Boolean = true
    ): MemoryWriteResult {
        val canonical = if (candidate.stableKey.startsWith("${MemoryRelationshipPolicy.BEST_FRIEND_KEY}:")) {
            candidate
        } else {
            MemoryRelationshipPolicy.canonicalize(candidate)
        }
        val now = System.currentTimeMillis()
        if (replaceBestFriends && MemoryRelationshipPolicy.isBestFriend(canonical)) {
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
