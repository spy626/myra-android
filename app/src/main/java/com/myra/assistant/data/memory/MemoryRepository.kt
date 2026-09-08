package com.myra.assistant.data.memory

import android.util.Log
import java.util.Locale
import java.util.UUID

class MemoryRepository(private val dao: MemoryDao) {
    /** Full local source of truth for Memory Core. Recall remains bounded after local ranking. */
    suspend fun allActive(limit: Int = Int.MAX_VALUE): List<MemoryEntity> {
        reconcilePreferenceDimensions()
        val rows = dao.activeAll()
        return if (limit == Int.MAX_VALUE) rows else rows.take(limit.coerceAtLeast(1))
    }

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
        val existing = dao.findById(id)?.takeIf { it.active }
            ?: return MemoryWriteResult.Rejected("Memory no longer exists.")
        if (existing.entityId != null || existing.category == MemoryCategory.PERSON.name) {
            return MemoryWriteResult.Rejected(
                "Person names must be edited through the linked person rename flow."
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
                active = true,
                provenance = MemoryProvenance.MANUAL_UI_EDIT.name,
                lifecycleStatus = MemoryLifecycleStatus.ACTIVE.name
            )
        )
        MemorySessionIndex.invalidate()
        return MemoryWriteResult.Saved(existing.id)
    }

    suspend fun forgetFromSettings(memory: MemoryEntity): Boolean {
        if (memory.category == MemoryCategory.PERSON.name) {
            val personName = memory.entityName
                ?: MemoryRelationshipPolicy.personName(memory.fact)
                ?: memory.fact.substringBefore(" is Zopy's ").takeIf { it != memory.fact }
            if (!personName.isNullOrBlank()) return forgetMatching(personName)
            if (memory.entityId != null) {
                return (dao.deactivateEntity(memory.entityId, System.currentTimeMillis()) > 0)
                    .also { if (it) MemorySessionIndex.invalidate() }
            }
        }
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

    /** The single Memory Brain V2 persistence gate. It never creates a pending voice turn. */
    suspend fun saveGrounded(candidate: MemoryCandidate, explicit: Boolean = false): MemoryWriteResult {
        val grounded = resolveExistingPersonIdentity(
            candidate.copy(explicitlyRequested = explicit || candidate.explicitlyRequested)
        )
        return when (MemorySafetyPolicy.decide(grounded)) {
            MemorySaveDecision.REJECT -> MemoryWriteResult.Rejected("This information is unsafe or insufficiently grounded.")
            MemorySaveDecision.ASK_PERMISSION -> MemoryWriteResult.Rejected("Sensitive or uncertain information is not learned automatically.")
            MemorySaveDecision.AUTO_SAVE -> if (MemoryRelationshipPolicy.isBestFriend(grounded)) {
                saveAdditionalBestFriend(grounded)
            } else persist(grounded)
        }
    }

    /** Adds one property-scoped relationship. Other people with the same relation coexist. */
    suspend fun addPersonRelationship(
        personName: String,
        relationship: PersonRelationship,
        factOverride: String? = null
    ): MemoryWriteResult {
        val canonicalName = personName.trim()
        val existingRows = dao.activeAll().filter {
            it.entityId != null && PersonLinkedMemoryIdentity.belongsTo(it, listOf(canonicalName))
        }
        val entityId = existingRows.mapNotNull { it.entityId }.distinct().singleOrNull()
            ?: NaturalMemoryExtractor.stablePersonId(canonicalName)
        if (existingRows.none { it.stableKey.endsWith(":identity") }) {
            val profile = MemoryCandidate(
                MemoryCategory.PERSON,
                "$canonicalName is a person known to Zopy",
                "person:${MemorySemanticIdentity.token(canonicalName)}:identity",
                MemorySensitivity.PERSONAL,
                .96,
                source = "semantic_relationship",
                provenance = MemoryProvenance.USER_DIRECT_STATEMENT,
                entityId = entityId,
                entityName = canonicalName
            )
            val profileResult = saveGrounded(profile)
            if (profileResult is MemoryWriteResult.Rejected) return profileResult
        }
        val label = when (relationship) {
            PersonRelationship.FRIEND -> "friend"
            PersonRelationship.GOOD_FRIEND -> "good friend"
            PersonRelationship.BEST_FRIEND -> "best friend"
        }
        return saveGrounded(
            MemoryCandidate(
                MemoryCategory.PERSON,
                factOverride ?: "$canonicalName is Zopy's $label",
                "person:${MemorySemanticIdentity.token(canonicalName)}:relationship:${relationship.key}",
                MemorySensitivity.PERSONAL,
                .97,
                source = "semantic_relationship",
                provenance = MemoryProvenance.USER_DIRECT_STATEMENT,
                entityId = entityId,
                entityName = canonicalName
            )
        )
    }

    /** Ends only the named relationship; the person identity and unrelated facts remain. */
    suspend fun endPersonRelationship(personName: String, relationship: PersonRelationship): Boolean {
        val active = dao.activeAll()
        val personRows = active.filter { PersonLinkedMemoryIdentity.belongsTo(it, listOf(personName)) }
        if (personRows.isEmpty()) return false
        val entityId = personRows.mapNotNull { it.entityId }.distinct().singleOrNull()
            ?: NaturalMemoryExtractor.stablePersonId(personName)
        if (personRows.none { it.stableKey.endsWith(":identity") }) {
            val displayName = personRows.firstNotNullOfOrNull { it.entityName } ?: personName
            saveGrounded(
                MemoryCandidate(
                    MemoryCategory.PERSON,
                    "$displayName is a person known to Zopy",
                    "person:${MemorySemanticIdentity.token(displayName)}:identity",
                    MemorySensitivity.PERSONAL,
                    .96,
                    source = "semantic_relationship",
                    provenance = MemoryProvenance.USER_DIRECT_STATEMENT,
                    entityId = entityId,
                    entityName = displayName
                )
            )
        }
        val relationRows = personRows.filter { row ->
            row.stableKey.endsWith(":relationship:${relationship.key}") || when (relationship) {
                PersonRelationship.BEST_FRIEND -> MemoryRelationshipPolicy.isBestFriend(row)
                PersonRelationship.GOOD_FRIEND -> row.fact.contains("good friend", true)
                PersonRelationship.FRIEND ->
                    (row.fact.contains("friend", true) || row.fact.contains("dost", true)) &&
                        !row.fact.contains("best friend", true) && !row.fact.contains("good friend", true)
            }
        }
        val now = System.currentTimeMillis()
        val changed = relationRows.sumOf { dao.deactivate(it.id, now) } > 0
        if (changed) MemorySessionIndex.invalidate()
        return changed
    }

    /** Reuses an existing stable entity identity instead of minting one ID per producer. */
    private suspend fun resolveExistingPersonIdentity(candidate: MemoryCandidate): MemoryCandidate {
        val name = candidate.entityName?.trim()?.takeIf { it.isNotEmpty() } ?: return candidate
        val existing = dao.activeAll().filter {
            it.entityId != null && PersonLinkedMemoryIdentity.belongsTo(it, listOf(name))
        }.distinctBy { it.entityId }
        val identity = existing.singleOrNull() ?: return candidate
        return candidate.copy(entityId = identity.entityId, entityName = identity.entityName ?: name)
    }

    /**
     * Relevance is computed locally across the complete active store and only the bounded
     * selected rows leave the repository. This removes the old newest-100 recall ceiling
     * without dumping the whole database into Gemini.
     */
    suspend fun relevant(query: String, limit: Int = 5): List<MemoryEntity> {
        if (query == MemoryWorkingContext.LAST_TRANSACTION_QUERY) {
            val synthetic = workingTransactionMemory()
            val selected = synthetic?.let(::listOf).orEmpty()
            MemorySessionIndex.publish(selected)
            return selected
        }
        val active = dao.activeAll()
        val selected = MemoryRelevanceSelector.select(query, active, limit)
        if (query.isNotBlank()) {
            val now = System.currentTimeMillis()
            selected.forEach { dao.markUsed(it.id, now) }
        }
        MemorySessionIndex.publish(selected)
        return selected
    }

    private fun workingTransactionMemory(): MemoryEntity? {
        val transaction = MemoryWorkingContext.lastTransaction ?: return null
        val fact = MemoryWorkingContext.transactionFact() ?: return null
        return MemoryEntity(
            id = "working:last_memory_transaction",
            stableKey = MemoryWorkingContext.LAST_TRANSACTION_QUERY,
            category = if (transaction.type == MemoryDecision.UPDATE) MemoryCategory.PERSON.name else MemoryCategory.LIFE_EVENT.name,
            fact = fact,
            normalizedFact = normalize(fact),
            sensitivity = MemorySensitivity.LOW.name,
            confidence = 1.0,
            source = "working_memory",
            createdAt = transaction.timestamp,
            updatedAt = transaction.timestamp,
            lastConfirmedAt = transaction.timestamp,
            provenance = MemoryProvenance.VERIFIED_MEMORY_CORRECTION.name,
            lifecycleStatus = MemoryLifecycleStatus.ACTIVE.name
        )
    }

    suspend fun logActiveBestFriends(stage: String) {
        val groups = dao.activeAll().filter(MemoryRelationshipPolicy::isBestFriend).groupBy {
            MemoryRelationshipPolicy.personName(it.fact)
                ?.let(BestFriendNameCanonicalizer::canonicalize)
                ?: "unknown"
        }
        memoryLog("$stage activeBestFriends=" + groups.mapValues { it.value.size })
    }

    suspend fun logPersonIdentity(stage: String, vararg names: String): List<MemoryEntity> {
        val rows = dao.activeAll().filter {
            PersonLinkedMemoryIdentity.belongsTo(it, names.toList())
        }
        memoryLog(
            "$stage rowCount=${rows.size} records=" +
                rows.joinToString(prefix = "[", postfix = "]") {
                    "{id=${it.id}, key=${it.stableKey}, category=${it.category}, entityId=${it.entityId}, active=${it.active}}"
                }
        )
        return rows
    }

    suspend fun isAlreadySaved(candidate: MemoryCandidate): Boolean {
        val canonical = PreferenceMemoryIdentity.canonicalize(
            MemoryRelationshipPolicy.canonicalize(candidate)
        )
        if (MemoryRelationshipPolicy.isBestFriend(canonical)) {
            val name = MemoryRelationshipPolicy.personName(canonical.fact)
            return dao.activeAll().any {
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
        return dao.activeAll().firstOrNull {
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
        for (memory in dao.activeAll().filter(MemoryRelationshipPolicy::isBestFriend)) {
            val name = MemoryRelationshipPolicy.personName(memory.fact) ?: continue
            val rawCandidate = MemoryCandidate(
                    category = MemoryCategory.PERSON,
                    fact = "Zopy's best friend is $name",
                    stableKey = "${MemoryRelationshipPolicy.BEST_FRIEND_KEY}:${PersonLinkedMemoryIdentity.stableToken(name)}",
                    sensitivity = MemorySensitivity.valueOf(memory.sensitivity),
                    confidence = memory.confidence,
                    source = memory.source,
                    provenance = runCatching { MemoryProvenance.valueOf(memory.provenance) }.getOrDefault(MemoryProvenance.LEGACY),
                    entityId = memory.entityId ?: NaturalMemoryExtractor.stablePersonId(name),
                    entityName = memory.entityName ?: name
                )
            // A verified correction is canonical identity authority. Reconciliation may
            // normalize legacy/noisy rows, but must never send verified spelling back
            // through the ASR alias canonicalizer.
            val candidate = if (rawCandidate.provenance == MemoryProvenance.VERIFIED_MEMORY_CORRECTION) {
                rawCandidate
            } else canonicalizeAgainstExistingBestFriends(rawCandidate)
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

    /** Repairs category/key variants of the same mutually-exclusive preference slot. */
    suspend fun reconcilePreferenceDimensions() {
        val variants = dao.activeAll()
            .filter(PreferenceMemoryIdentity::isResponseVerbosity)
            .sortedByDescending { it.updatedAt }
        if (variants.isEmpty()) return
        val newest = variants.first()
        val canonicalExisting = dao.findByStableKey(PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY)
        val now = System.currentTimeMillis()
        val keeperId = canonicalExisting?.id ?: newest.id
        variants.filter { it.id != keeperId }.forEach { dao.deactivate(it.id, now) }
        dao.upsert(
            newest.copy(
                id = keeperId,
                stableKey = PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY,
                category = MemoryCategory.PREFERENCE.name,
                createdAt = canonicalExisting?.createdAt ?: newest.createdAt,
                updatedAt = newest.updatedAt,
                lastConfirmedAt = newest.lastConfirmedAt,
                active = true,
                useCount = maxOf(canonicalExisting?.useCount ?: 0, newest.useCount),
                lastUsedAt = maxOf(canonicalExisting?.lastUsedAt ?: 0, newest.lastUsedAt)
            )
        )
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
        (dao.deactivate(id, System.currentTimeMillis()) > 0).also { if (it) MemorySessionIndex.invalidate() }

    suspend fun forgetStableKey(stableKey: String): Boolean =
        (dao.deactivateByStableKey(stableKey.trim(), System.currentTimeMillis()) > 0)
            .also { if (it) MemorySessionIndex.invalidate() }

    suspend fun setLifecycle(stableKey: String, status: MemoryLifecycleStatus): Boolean =
        (dao.updateLifecycleByStableKey(stableKey.trim(), status.name, System.currentTimeMillis()) > 0)
            .also { if (it) MemorySessionIndex.invalidate() }

    suspend fun forgetMatching(query: String): Boolean {
        val activeMemories = dao.activeAll()
        val canonicalQuery = BestFriendNameCanonicalizer.canonicalize(query)
        val matches = BestFriendDeleteMatcher.findAll(canonicalQuery, activeMemories)
        if (matches.isEmpty()) {
            MemoryWorkingContext.transaction(
                LastMemoryTransaction(
                    type = MemoryDecision.DELETE,
                    oldValue = canonicalQuery,
                    status = MemoryTransactionStatus.FAILED,
                    failureReason = "target_not_found"
                )
            )
            memoryLog("after_delete matched=0 remaining=0")
            return false
        }
        val now = System.currentTimeMillis()
        var affected = 0
        val matchedNames = matches.mapNotNull { MemoryRelationshipPolicy.personName(it.fact) }
        val identityRows = activeMemories.filter {
            PersonLinkedMemoryIdentity.belongsTo(it, matchedNames + canonicalQuery)
        }
        for (match in identityRows.distinctBy { it.id }) affected += dao.deactivate(match.id, now)

        // A deliberate whole-person forget also removes passive evidence that could otherwise
        // recreate the forgotten channel/person pattern on the next observation.
        val behaviorRows = BehaviorObservationKind.entries
            .flatMap { dao.behaviorByKind(it.name) }
            .distinctBy { it.stableKey }
            .filter { PersonLinkedMemoryIdentity.mentionsName(it.label, canonicalQuery) }
        var behaviorAffected = 0
        behaviorRows.forEach { behaviorAffected += dao.deleteBehavior(it.stableKey) }

        val remaining = dao.activeAll().count {
            PersonLinkedMemoryIdentity.belongsTo(it, matchedNames + canonicalQuery)
        }
        val succeeded = affected > 0 && remaining == 0
        memoryLog(
            "after_delete matched=${matches.size} affected=$affected behaviorAffected=$behaviorAffected remaining=$remaining"
        )
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                type = MemoryDecision.DELETE,
                oldValue = canonicalQuery,
                status = if (succeeded) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                failureReason = if (succeeded) null else "delete_not_verified"
            )
        )
        if (succeeded) {
            MemoryWorkingContext.clearPersonIfMatches(canonicalQuery)
            MemorySessionIndex.invalidate()
        }
        return succeeded
    }

    /**
     * Renames the canonical identity and every linked row in place. The old code only
     * renamed the best-friend row; person:<old-name>:gaming_channel therefore retained
     * the first ASR spelling and made conversation and persistent recall diverge.
     */
    suspend fun renameBestFriend(oldName: String, correctedName: String): Boolean {
        val renamed = renamePerson(oldName, correctedName)
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                type = MemoryDecision.UPDATE,
                oldValue = oldName,
                newValue = correctedName,
                status = if (renamed) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                failureReason = if (renamed) null else "target_not_found_or_verification_failed"
            )
        )
        if (renamed) MemoryWorkingContext.person(correctedName)
        return renamed
    }

    suspend fun renamePerson(oldName: String, correctedName: String): Boolean {
        val allMemories = dao.activeAll()
        val linkedRows = allMemories.filter { PersonLinkedMemoryIdentity.belongsTo(it, listOf(oldName)) }
        val memories = allMemories.filter(MemoryRelationshipPolicy::isBestFriend)
        val bestFriendRows = BestFriendDeleteMatcher.findAll(oldName, memories)
        val oldRows = bestFriendRows.ifEmpty { linkedRows }
        val old = oldRows.firstOrNull() ?: return false
        val canonicalName = correctedName.trim().replace(Regex("\\s+"), " ").replaceFirstChar {
            if (it.isLowerCase()) it.uppercaseChar().toString() else it.toString()
        }
        if (bestFriendRows.isEmpty()) {
            val now = System.currentTimeMillis()
            val stableEntityId = old.entityId ?: NaturalMemoryExtractor.stablePersonId(oldName)
            oldRows.forEach { row ->
                val renamed = PersonLinkedMemoryIdentity.rename(row, listOf(oldName), canonicalName)
                val fact = renamed?.fact ?: row.fact.replace(oldName, canonicalName, ignoreCase = true)
                val key = renamed?.stableKey ?: row.stableKey.replace(
                    PersonLinkedMemoryIdentity.stableToken(oldName),
                    PersonLinkedMemoryIdentity.stableToken(canonicalName)
                )
                dao.upsert(row.copy(
                    stableKey = key,
                    fact = fact,
                    normalizedFact = normalize(fact),
                    entityId = stableEntityId,
                    entityName = canonicalName,
                    provenance = MemoryProvenance.VERIFIED_MEMORY_CORRECTION.name,
                    updatedAt = now,
                    lastConfirmedAt = now
                ))
            }
            val active = dao.activeAll()
            val verified = active.any { it.entityId == stableEntityId && it.entityName == canonicalName } &&
                active.none { it.entityId == stableEntityId && it.entityName.equals(oldName, true) }
            if (verified) MemorySessionIndex.invalidate()
            return verified
        }
        val identityRows = (bestFriendRows + BestFriendDeleteMatcher.findAll(canonicalName, memories))
            .distinctBy { it.id }
        // A verified correction is the canonical spelling authority. Do not pass it
        // through the ASR alias canonicalizer again (Karim must not become Kareem).
        val replacement = MemoryCandidate(
            category = MemoryCategory.PERSON,
            fact = "Zopy's best friend is $canonicalName",
            stableKey = "${MemoryRelationshipPolicy.BEST_FRIEND_KEY}:${PersonLinkedMemoryIdentity.stableToken(canonicalName)}",
            sensitivity = MemorySensitivity.valueOf(old.sensitivity),
            confidence = old.confidence,
            source = old.source,
            provenance = MemoryProvenance.VERIFIED_MEMORY_CORRECTION,
            entityId = old.entityId ?: NaturalMemoryExtractor.stablePersonId(oldName),
            entityName = canonicalName
        )
        val now = System.currentTimeMillis()
        val saved = persist(replacement, replaceBestFriends = false) as? MemoryWriteResult.Saved
            ?: return false
        identityRows.filter { it.id != saved.id }.forEach { dao.deactivate(it.id, now) }
        renameLinkedPersonRows(
            allMemories,
            identityRows,
            oldName,
            canonicalName,
            replacement.entityId ?: NaturalMemoryExtractor.stablePersonId(oldName),
            now
        )
        val verified = verifyRenameCommitted(oldName, canonicalName)
        if (verified) MemorySessionIndex.invalidate()
        return verified
    }

    /** Never report success until Room contains the target and no exact stale alias. */
    private suspend fun verifyRenameCommitted(oldName: String, canonicalName: String): Boolean {
        val active = dao.activeAll()
        val hasCanonicalPerson = active.any {
            MemoryRelationshipPolicy.isBestFriend(it) &&
                MemoryRelationshipPolicy.personName(it.fact)
                    ?.equals(canonicalName, ignoreCase = true) == true
        }
        val oldToken = PersonLinkedMemoryIdentity.stableToken(oldName)
        val staleOldIdentity = active.any { row ->
            row.entityName?.equals(oldName, ignoreCase = true) == true ||
                row.stableKey.startsWith("person:$oldToken:") ||
                PersonLinkedMemoryIdentity.mentionsName(row.fact, oldName)
        }
        val staleTargetAlias = active.any { row ->
            val stored = MemoryRelationshipPolicy.personName(row.fact) ?: return@any false
            BestFriendNameCanonicalizer.canonicalize(stored)
                .equals(canonicalName, ignoreCase = true) &&
                !stored.equals(canonicalName, ignoreCase = true)
        }
        memoryLog(
            "verify_rename canonical=$hasCanonicalPerson staleOld=$staleOldIdentity " +
                "staleTargetAlias=$staleTargetAlias activeKeys=${active.map { it.stableKey }}"
        )
        return hasCanonicalPerson && !staleOldIdentity && !staleTargetAlias
    }

    private suspend fun renameLinkedPersonRows(
        allMemories: List<MemoryEntity>,
        bestFriendRows: List<MemoryEntity>,
        oldName: String,
        canonicalName: String,
        stableEntityId: String,
        now: Long
    ) {
        val aliases = (bestFriendRows.mapNotNull { MemoryRelationshipPolicy.personName(it.fact) } + oldName)
            .distinctBy { it.lowercase(Locale.ROOT) }
        allMemories.filterNot(MemoryRelationshipPolicy::isBestFriend).forEach { row ->
            val renamed = PersonLinkedMemoryIdentity.rename(row, aliases, canonicalName) ?: return@forEach
            val target = dao.findByStableKey(renamed.stableKey)
            if (target != null && target.id != row.id) {
                dao.deactivate(row.id, now)
            } else if (renamed.stableKey != row.stableKey || renamed.fact != row.fact ||
                row.entityId != stableEntityId || row.entityName != canonicalName
            ) {
                dao.upsert(
                    row.copy(
                        stableKey = renamed.stableKey,
                        fact = renamed.fact,
                        normalizedFact = normalize(renamed.fact),
                        entityId = stableEntityId,
                        entityName = canonicalName,
                        provenance = MemoryProvenance.VERIFIED_MEMORY_CORRECTION.name,
                        updatedAt = now,
                        lastConfirmedAt = now
                    )
                )
            }
        }
    }

    private fun memoryLog(message: String) {
        runCatching { Log.d(MEMORY_LOG_TAG, message) }
    }

    suspend fun clearAll() { dao.deleteAll(); dao.deleteAllBehavior(); MemorySessionIndex.invalidate() }

    suspend fun recordBehavior(value: BehaviorObservationEntity) = dao.upsertBehavior(value)
    suspend fun behavior(stableKey: String) = dao.findBehavior(stableKey)
    suspend fun recentBehavior(limit: Int = 100) = dao.recentBehavior(limit)
    suspend fun behaviorByKind(kind: BehaviorObservationKind) = dao.behaviorByKind(kind.name)

    private suspend fun canonicalizeAgainstExistingBestFriends(candidate: MemoryCandidate): MemoryCandidate {
        val proposed = MemoryRelationshipPolicy.canonicalizeAdditional(candidate)
        if (!MemoryRelationshipPolicy.isBestFriend(proposed)) return proposed
        val proposedName = MemoryRelationshipPolicy.personName(proposed.fact) ?: return proposed
        if (BestFriendNameCanonicalizer.isPreferredCanonical(proposedName)) return proposed
        val equivalentNames = dao.activeAll().filter(MemoryRelationshipPolicy::isBestFriend)
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
        val relationshipCanonical = if (candidate.stableKey.startsWith("${MemoryRelationshipPolicy.BEST_FRIEND_KEY}:")) {
            candidate
        } else {
            MemoryRelationshipPolicy.canonicalize(candidate)
        }
        val canonical = PreferenceMemoryIdentity.canonicalize(relationshipCanonical)
        val now = System.currentTimeMillis()
        if (canonical.stableKey == PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY) {
            dao.activeAll()
                .filter(PreferenceMemoryIdentity::isResponseVerbosity)
                .filter { it.stableKey != PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY }
                .forEach { dao.deactivate(it.id, now) }
        }
        if (replaceBestFriends && MemoryRelationshipPolicy.isBestFriend(canonical)) {
            dao.activeAll()
                .filter { MemoryRelationshipPolicy.isBestFriend(it) }
                .forEach { dao.deactivate(it.id, now) }
        }
        val existing = dao.findByStableKey(canonical.stableKey)
        var id = existing?.id ?: UUID.randomUUID().toString()
        if (existing != null && existing.active && normalize(existing.fact) != normalize(canonical.fact)) {
            id = UUID.randomUUID().toString()
            dao.upsert(existing.copy(
                stableKey = "superseded:${existing.id}:${existing.stableKey}",
                active = false,
                lifecycleStatus = MemoryLifecycleStatus.SUPERSEDED.name,
                supersededById = id,
                updatedAt = now
            ))
        }
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
                active = true,
                useCount = existing?.useCount ?: 0,
                lastUsedAt = existing?.lastUsedAt ?: 0,
                provenance = canonical.provenance.name,
                lifecycleStatus = MemoryLifecycleStatus.ACTIVE.name,
                entityId = canonical.entityId,
                entityName = canonical.entityName,
                lastRecalledAt = existing?.lastRecalledAt ?: 0,
                observationMetadata = canonical.observationMetadata
            )
        )
        MemorySessionIndex.invalidate()
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
