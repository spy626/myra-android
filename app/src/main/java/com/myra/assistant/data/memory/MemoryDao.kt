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

    @Query("SELECT * FROM memories WHERE active = 1 ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE active = 1 AND normalizedFact LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<MemoryEntity>

    @Query("UPDATE memories SET active = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun deactivate(id: String, updatedAt: Long): Int

    @Query("UPDATE memories SET active = 0, updatedAt = :updatedAt WHERE stableKey = :stableKey AND active = 1")
    suspend fun deactivateByStableKey(stableKey: String, updatedAt: Long): Int

    @Query("UPDATE memories SET stableKey = :stableKey, fact = :fact, normalizedFact = :normalizedFact, updatedAt = :updatedAt, lastConfirmedAt = :updatedAt WHERE id = :id AND active = 1")
    suspend fun rename(id: String, stableKey: String, fact: String, normalizedFact: String, updatedAt: Long): Int

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
