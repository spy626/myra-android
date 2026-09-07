package com.myra.assistant.data.memory

/**
 * Canonical Memory Core UI adapter. Manual add/edit still flows through the same
 * MemoryRepository safety/lifecycle gate, while preserving linked-person identity.
 */
object MemoryCoreManualActions {
    suspend fun add(
        repository: MemoryRepository,
        fact: String,
        category: MemoryCategory
    ): MemoryWriteResult {
        val clean = fact.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 3..200) {
            return MemoryWriteResult.Rejected("Memory must contain 3 to 200 characters.")
        }

        val linked = PersonLinkedMemoryExtractor.extractAll(clean)
        if (linked.isNotEmpty()) {
            var firstSaved: MemoryWriteResult.Saved? = null
            for (candidate in linked) {
                val result = repository.saveGrounded(
                    candidate.copy(
                        explicitlyRequested = true,
                        source = ManualMemoryPolicy.SOURCE,
                        provenance = MemoryProvenance.MANUAL_UI_SEED
                    ),
                    explicit = true
                )
                if (result is MemoryWriteResult.Rejected) return result
                if (result is MemoryWriteResult.Saved && firstSaved == null) firstSaved = result
            }
            return firstSaved ?: MemoryWriteResult.Rejected("Memory could not be saved.")
        }

        PersonalMemoryExtractor.extract(clean)?.let { extracted ->
            return repository.saveGrounded(
                extracted.copy(
                    explicitlyRequested = true,
                    source = ManualMemoryPolicy.SOURCE,
                    provenance = MemoryProvenance.MANUAL_UI_SEED
                ),
                explicit = true
            )
        }

        val candidate = ManualMemoryPolicy.candidate(clean, category)
            ?: return MemoryWriteResult.Rejected("Memory must contain 3 to 200 characters.")
        return repository.saveGrounded(candidate, explicit = true)
    }

    suspend fun edit(
        repository: MemoryRepository,
        existing: MemoryEntity,
        fact: String,
        category: MemoryCategory
    ): MemoryWriteResult {
        if (existing.category == MemoryCategory.PERSON.name) {
            return MemoryWriteResult.Rejected(
                "Person names must be edited through the linked person rename flow."
            )
        }
        if (existing.entityId == null) {
            return repository.updateManualFact(existing.id, fact, category)
        }

        val clean = fact.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 3..200) {
            return MemoryWriteResult.Rejected("Memory must contain 3 to 200 characters.")
        }
        if (category == MemoryCategory.PERSON) {
            return MemoryWriteResult.Rejected(
                "Use the linked person rename flow for person identities."
            )
        }

        val candidate = MemoryCandidate(
            category = category,
            fact = clean,
            stableKey = existing.stableKey,
            sensitivity = MemorySensitivity.PERSONAL,
            confidence = 1.0,
            explicitlyRequested = true,
            source = ManualMemoryPolicy.SOURCE,
            provenance = MemoryProvenance.MANUAL_UI_EDIT,
            entityId = existing.entityId,
            entityName = existing.entityName,
            observationMetadata = existing.observationMetadata
        )
        return repository.saveGrounded(candidate, explicit = true)
    }
}
