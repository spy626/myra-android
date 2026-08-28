package com.myra.assistant.data.memory

import android.util.Log
import java.util.Locale
import java.util.UUID

class MemoryRepository(private val dao: MemoryDao) {
    suspend fun allActive(limit: Int = 100): List<MemoryEntity> =
        dao.recent(limit.coerceIn(1, 200))

    suspend fun saveManualFact(fact: String, category: MemoryCategory): MemoryWriteResult {
        val clean = fact.trim().replace(Regex("\\s+"), " ")
        val linked = PersonLinkedMemoryExtractor.extractAll(clean)
        if (linked.isNotEmpty()) {
            var firstSaved: MemoryWriteResult.Saved? = null
            for (candidate in linked) {
                val result = if (MemoryRelationshipPolicy.isBestFriend(candidate)) {
                    saveAdditionalBestFriend(candidate.copy(explicitlyRequested = true, source = ManualMemoryPolicy.SOURCE))
                } else {
                    save(candidate.copy(explicitlyRequested = true, source = ManualMemoryPolicy.SOURCE), permissionGranted = true)
                }
                if (result is MemoryWriteResult.Rejected) return result
                if (result is MemoryWriteResult.Saved && firstSaved == null) firstSaved = result
            }
            return firstSaved ?: MemoryWriteResult.Rejected("Memory could not be saved.")
        }
        PersonalMemoryExtractor.extract(clean)?.let { extracted ->
            val explicit = extracted.copy(explicitlyRequested = true, source = ManualMemoryPolicy.SOURCE)
            return if (MemoryRelationshipPolicy.isBestFriend(explicit)) {
                saveAdditionalBestFriend(explicit)
            } else save(explicit, permissionGranted = true)
        }
        val candidate = ManualMemoryPolicy.candidate(clean, category)
            ?: return MemoryWriteResult.Rejected("Memory must contain 3 to 200 characters.")
        if (MemoryRelationshipPolicy.isBestFriend(candidate)) {
            return MemoryWriteResult.Rejected(
                "Use a clear format such as: Mera best friend Kareem hai."
            )
        }
        return save(candidate, permissionGranted = true)
    }

    suspend fun updateManualFact(
        id: String,
        fact: String,
        category: MemoryCategory
    ): MemoryWriteResult {
        val existing = dao.recent(200).firstOrNull { it.id == id && it.active }
            ?: return MemoryWriteResult.Rejected("Memory no longer exists.")
        if (existing.source != ManualMemoryPolicy.SOURCE || !existing.stableKey.startsWith("manual:")) {
            return MemoryWriteResult.Rejected(
                "Learned memories must be corrected through LYRA so linked facts stay consistent."
            )
        }
        val candidate = ManualMemoryPolicy.candidate(fact, category, existing.stableKey)
            ?: return MemoryWriteResult.Rejected("Memory must contain 3 to 200 characters.")
        if (MemoryRelationshipPolicy.isBestFriend(candidate)) {
            return MemoryWriteResult.Rejected(
                "Relationship memories must be corrected through LYRA."
            )
        }
        if (MemorySafetyPolicy.decide(candidate) == MemorySaveDecision.REJECT) {
            return MemoryWriteResult.Rejected("Passwords, OTPs and financial details cannot be saved.")
        }
        val now = System.currentTimeMillis()
        dao.upsert(
            existing.copy(
                category = category.name,
                fact = candidate.fact,
                normalizedFact = normalize(candidate.fact),
                sensitivity = candidate.sensitivity.name,
                confidence = candidate.confidence,
                updatedAt = now,
                lastConfirmedAt = now,
                active = true
            )
        )
        return MemoryWriteResult.Saved(existing.id)
    }

    suspend fun forgetFromSettings(memory: MemoryEntity): Boolean {
        val personName = memory.takeIf(MemoryRelationshipPolicy::isBestFriend)
            ?.let { MemoryRelationshipPolicy.personName(it.fact) }
        return if (personName != null) forgetMatching(personName) else forget(memory.id)
    }

    suspend fun save(candidate: MemoryCandidate, permissionGranted: Boolean = false): MemoryWriteResult {
        val canonical = if (!permissionGranted && MemoryRelationshipPolicy.isBestFriend(candidate)) {
            canonicalizeAgainstExistingBestFriends(candidate)
        } else MemoryRelationshipPolicy.canonicalizeForSave(candidate, replaceExisting = permissionGranted)
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
        val active = dao.recent(100)
        return MemoryRelevanceSelector.select(query, active, limit)
    }

    suspend fun logActiveBestFriends(stage: String) {
        val groups = dao.recent(50).filter(MemoryRelationshipPolicy::isBestFriend).groupBy {
            MemoryRelationshipPolicy.personName(it.fact)
                ?.let(BestFriendNameCanonicalizer::canonicalize)
                ?: "unknown"
        }
        memoryLog("$stage activeBestFriends=" + groups.mapValues { it.value.size })
    }

    suspend fun logPersonIdentity(stage: String, vararg names: String): List<MemoryEntity> {
        val rows = dao.recent(50).filter {
            PersonLinkedMemoryIdentity.belongsTo(it, names.toList())
        }
        memoryLog(
            "$stage names=${names.toList()} rowCount=${rows.size} records=" +
                rows.joinToString(prefix = "[", postfix = "]") {
                    "{id=${it.id}, key=${it.stableKey}, fact=${it.fact}, active=${it.active}}"
                }
        )
        return rows
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
        val canonicalRows = mutableListOf<Pair<MemoryEntity, MemoryCandidate>>()
        for (memory in dao.recent(50).filter(MemoryRelationshipPolicy::isBestFriend)) {
            val name = MemoryRelationshipPolicy.personName(memory.fact) ?: continue
            val candidate = canonicalizeAgainstExistingBestFriends(
                    MemoryCandidate(
                        category = MemoryCategory.PERSON,
                        fact = "Zopy's best friend is $name",
                        stableKey = MemoryRelationshipPolicy.BEST_FRIEND_KEY,
                        sensitivity = MemorySensitivity.valueOf(memory.sensitivity),
                        confidence = memory.confidence,
                        source = memory.source
                    )
                )
            canonicalRows += memory to candidate
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
        val additional = canonicalizeAgainstExistingBestFriends(candidate)
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
        val matches = BestFriendDeleteMatcher.findAll(canonicalQuery, activeMemories)
        if (matches.isEmpty()) {
            memoryLog("after_delete query=$canonicalQuery matched=0 remaining=0")
            return false
        }
        val now = System.currentTimeMillis()
        var affected = 0
        val matchedNames = matches.mapNotNull { MemoryRelationshipPolicy.personName(it.fact) }
        // Delete the identity as one unit. Previously only the best-friend row was
        // removed, so a linked channel fact could keep the deleted person in recall.
        val identityRows = activeMemories.filter {
            PersonLinkedMemoryIdentity.belongsTo(it, matchedNames + canonicalQuery)
        }
        for (match in identityRows) affected += dao.deactivate(match.id, now)
        val remaining = dao.recent(50).count { memory ->
            val storedName = MemoryRelationshipPolicy.personName(memory.fact)
                ?.let(BestFriendNameCanonicalizer::canonicalize)
            storedName != null && (
                storedName.equals(canonicalQuery, ignoreCase = true) ||
                    BestFriendNameSimilarity.likelySame(storedName, canonicalQuery)
                )
        }
        memoryLog(
            "after_delete query=$canonicalQuery matched=${matches.size} affected=$affected remaining=$remaining"
        )
        return affected > 0 && remaining == 0
    }

    /**
     * Renames the canonical identity and every linked row in place. The old code only
     * renamed the best-friend row; person:<old-name>:gaming_channel therefore retained
     * the first ASR spelling and made conversation and persistent recall diverge.
     */
    suspend fun renameBestFriend(oldName: String, correctedName: String): Boolean {
        val allMemories = dao.recent(50)
        val memories = allMemories.filter(MemoryRelationshipPolicy::isBestFriend)
        val oldRows = BestFriendDeleteMatcher.findAll(oldName, memories)
        val old = oldRows.firstOrNull() ?: return false
        val canonicalName = BestFriendNameCanonicalizer.canonicalize(correctedName)
        // Include every row already resolving to the corrected identity. In the
        // failing phone path "Named Karim" and "Kareem" were separate stable keys;
        // renaming only Karima left that alias active and recall listed two people.
        val identityRows = (oldRows + BestFriendDeleteMatcher.findAll(canonicalName, memories))
            .distinctBy { it.id }
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
        val now = System.currentTimeMillis()
        val saved = persist(replacement, replaceBestFriends = false) as? MemoryWriteResult.Saved
            ?: return false
        identityRows.filter { it.id != saved.id }.forEach { dao.deactivate(it.id, now) }
        renameLinkedPersonRows(allMemories, identityRows, oldName, canonicalName, now)
        return verifyRenameCommitted(oldName, canonicalName)
    }

    /** Never report success until Room contains the target and no exact stale alias. */
    private suspend fun verifyRenameCommitted(oldName: String, canonicalName: String): Boolean {
        val active = dao.recent(50)
        val hasCanonicalPerson = active.any {
            MemoryRelationshipPolicy.isBestFriend(it) &&
                MemoryRelationshipPolicy.personName(it.fact)
                    ?.equals(canonicalName, ignoreCase = true) == true
        }
        val oldToken = PersonLinkedMemoryIdentity.stableToken(oldName)
        val staleOldIdentity = active.any { row ->
            MemoryRelationshipPolicy.personName(row.fact)
                ?.equals(oldName, ignoreCase = true) == true ||
                row.stableKey.startsWith("person:$oldToken:") ||
                Regex("^${Regex.escape(oldName)}\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(row.fact)
        }
        val staleTargetAlias = active.any { row ->
            val stored = MemoryRelationshipPolicy.personName(row.fact) ?: return@any false
            BestFriendNameCanonicalizer.canonicalize(stored)
                .equals(canonicalName, ignoreCase = true) &&
                !stored.equals(canonicalName, ignoreCase = true)
        }
        memoryLog(
            "verify_rename old=$oldName new=$canonicalName canonical=$hasCanonicalPerson " +
                "staleOld=$staleOldIdentity staleTargetAlias=$staleTargetAlias " +
                "active=${active.map { it.stableKey to it.fact }}"
        )
        return hasCanonicalPerson && !staleOldIdentity && !staleTargetAlias
    }

    private suspend fun renameLinkedPersonRows(
        allMemories: List<MemoryEntity>,
        bestFriendRows: List<MemoryEntity>,
        oldName: String,
        canonicalName: String,
        now: Long
    ) {
        val aliases = (bestFriendRows.mapNotNull { MemoryRelationshipPolicy.personName(it.fact) } + oldName)
            .distinctBy { it.lowercase(Locale.ROOT) }
        allMemories.filterNot(MemoryRelationshipPolicy::isBestFriend).forEach { row ->
            val renamed = PersonLinkedMemoryIdentity.rename(row, aliases, canonicalName) ?: return@forEach
            val target = dao.findByStableKey(renamed.stableKey)
            if (target != null && target.id != row.id) {
                dao.deactivate(row.id, now)
            } else if (renamed.stableKey != row.stableKey || renamed.fact != row.fact) {
                dao.rename(row.id, renamed.stableKey, renamed.fact, normalize(renamed.fact), now)
            }
        }
    }

    private fun memoryLog(message: String) {
        runCatching { Log.d(MEMORY_LOG_TAG, message) }
    }

    suspend fun clearAll() = dao.deleteAll()

    private suspend fun canonicalizeAgainstExistingBestFriends(candidate: MemoryCandidate): MemoryCandidate {
        val proposed = MemoryRelationshipPolicy.canonicalizeAdditional(candidate)
        if (!MemoryRelationshipPolicy.isBestFriend(proposed)) return proposed
        val proposedName = MemoryRelationshipPolicy.personName(proposed.fact) ?: return proposed
        if (BestFriendNameCanonicalizer.isPreferredCanonical(proposedName)) return proposed
        val equivalentNames = dao.recent(50).filter(MemoryRelationshipPolicy::isBestFriend)
            .mapNotNull { MemoryRelationshipPolicy.personName(it.fact) }
            .map(BestFriendNameCanonicalizer::canonicalize)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filter {
                !it.equals(proposedName, ignoreCase = true) &&
                    BestFriendNameCanonicalizer.isPreferredCanonical(it) &&
                    BestFriendNameSimilarity.likelySame(it, proposedName)
            }
        val existingName = equivalentNames.singleOrNull() ?: return proposed
        return MemoryRelationshipPolicy.canonicalizeAdditional(
            proposed.copy(fact = "Zopy's best friend is $existingName")
        )
    }

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

    private companion object {
        const val MEMORY_LOG_TAG = "LyraMemoryStore"
    }
}
