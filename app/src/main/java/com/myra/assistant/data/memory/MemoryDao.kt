package com.myra.assistant.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE stableKey = :stableKey LIMIT 1")
    suspend fun findByStableKey(stableKey: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE active = 1 AND entityId = :entityId ORDER BY updatedAt DESC")
    suspend fun findByEntityId(entityId: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE active = 1 ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    /** Concrete helper keeps old fake DAOs compatible while allowing uncapped local ranking. */
    suspend fun activeAll(): List<MemoryEntity> = recent(Int.MAX_VALUE)

    @Query("SELECT * FROM memories WHERE active = 1 AND normalizedFact LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<MemoryEntity>

    @Query("UPDATE memories SET active = 0, lifecycleStatus = 'INACTIVE', updatedAt = :updatedAt WHERE id = :id")
    suspend fun deactivate(id: String, updatedAt: Long): Int

    @Query("UPDATE memories SET active = 0, lifecycleStatus = 'INACTIVE', updatedAt = :updatedAt WHERE stableKey = :stableKey AND active = 1")
    suspend fun deactivateByStableKey(stableKey: String, updatedAt: Long): Int

    suspend fun updateLifecycleByStableKey(stableKey: String, status: String, updatedAt: Long): Int {
        val row = findByStableKey(stableKey)?.takeIf { it.active } ?: return 0
        upsert(row.copy(lifecycleStatus = status, updatedAt = updatedAt))
        return 1
    }

    @Query("UPDATE memories SET active = 0, lifecycleStatus = 'INACTIVE', updatedAt = :updatedAt WHERE entityId = :entityId AND active = 1")
    suspend fun deactivateEntity(entityId: String, updatedAt: Long): Int

    @Query("UPDATE memories SET useCount = useCount + 1, lastUsedAt = :usedAt, lastRecalledAt = :usedAt WHERE id = :id AND active = 1")
    suspend fun markUsed(id: String, usedAt: Long): Int

    @Query("UPDATE memories SET stableKey = :stableKey, fact = :fact, normalizedFact = :normalizedFact, updatedAt = :updatedAt, lastConfirmedAt = :updatedAt WHERE id = :id AND active = 1")
    suspend fun rename(id: String, stableKey: String, fact: String, normalizedFact: String, updatedAt: Long): Int

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBehavior(observation: BehaviorObservationEntity)

    @Query("SELECT * FROM behavior_observations WHERE stableKey = :stableKey LIMIT 1")
    suspend fun findBehavior(stableKey: String): BehaviorObservationEntity?

    @Query("SELECT * FROM behavior_observations ORDER BY lastObservedAt DESC LIMIT :limit")
    suspend fun recentBehavior(limit: Int): List<BehaviorObservationEntity>

    suspend fun behaviorByKind(kind: String): List<BehaviorObservationEntity> =
        recentBehavior(Int.MAX_VALUE).filter { it.kind == kind }
            .sortedWith(compareByDescending<BehaviorObservationEntity> { it.observationCount }
                .thenByDescending { it.sessionCount }
                .thenByDescending { it.dayCount })

    @Query("DELETE FROM behavior_observations")
    suspend fun deleteAllBehavior()
}
