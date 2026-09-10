package com.myra.assistant.data.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * JARVIS memory tables and DAO ported from the reference app.
 *
 * The two original JARVIS tables keep the same names and recent-history limits:
 * messages (30) and command_logs (20). LYRA adds one memories table only for durable
 * facts/preferences/relationships requested by the user. All three tables live inside
 * LyraMemoryDatabase so there is exactly one Room database and one storage truth.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["messageKey"], unique = true), Index(value = ["timestamp"])]
)
data class JarvisMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val content: String,
    val actionType: String? = null,
    val isSuccess: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val messageKey: String,
    val sessionId: String,
    val turnId: Long,
    val utteranceId: String,
    val sourceKind: String
)

@Entity(tableName = "command_logs", indices = [Index(value = ["timestamp"])])
data class JarvisCommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawCommand: String,
    val intentType: String,
    val resultText: String,
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val verified: Boolean = false,
    val sourceKind: String = JarvisMemorySource.COMMAND.name
)

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["memoryKey"], unique = true),
        Index(value = ["memoryType", "active"]),
        Index(value = ["normalizedSubject", "active"]),
        Index(value = ["updatedAt"])
    ]
)
data class JarvisMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryKey: String,
    val memoryType: String,
    val subject: String,
    val normalizedSubject: String,
    val value: String,
    val sourceText: String,
    val sourceKind: String,
    val sourceSessionId: String,
    val sourceTurnId: Long,
    val sourceUtteranceId: String,
    val confidence: Float,
    val importance: Int,
    val active: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0
)

@Dao
interface JarvisDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<JarvisMessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 30")
    fun getRecentMessages(): List<JarvisMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: JarvisMessageEntity): Long

    @Query("DELETE FROM messages")
    fun clearMessages()

    @Query("SELECT * FROM command_logs ORDER BY timestamp DESC LIMIT 20")
    fun getRecentLogs(): List<JarvisCommandLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLog(log: JarvisCommandLogEntity): Long

    @Query("DELETE FROM command_logs")
    fun clearLogs()

    @Query("SELECT * FROM memories WHERE active = 1 ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    fun getActiveMemories(limit: Int): List<JarvisMemoryEntity>

    @Query("SELECT * FROM memories WHERE memoryKey = :key AND active = 1 LIMIT 1")
    fun getMemoryByKey(key: String): JarvisMemoryEntity?

    @Query("SELECT * FROM memories WHERE normalizedSubject = :subject AND active = 1 ORDER BY updatedAt DESC")
    fun getMemoriesForSubject(subject: String): List<JarvisMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMemory(memory: JarvisMemoryEntity): Long

    @Query("UPDATE memories SET lastAccessedAt = :at, accessCount = accessCount + 1 WHERE id = :id AND active = 1")
    fun touchMemory(id: Long, at: Long)

    @Query("UPDATE memories SET active = 0, updatedAt = :at WHERE id = :id AND active = 1")
    fun deactivateMemory(id: Long, at: Long): Int

    @Query("UPDATE memories SET active = 0, updatedAt = :at WHERE normalizedSubject = :subject AND active = 1")
    fun deactivateSubject(subject: String, at: Long): Int

    @Query("DELETE FROM memories")
    fun clearMemories()
}

/**
 * Compatibility entry point used by the JARVIS runtime and migration bridge.
 * It deliberately delegates to LyraMemoryDatabase instead of constructing another Room DB.
 */
object JarvisDatabase {
    fun getInstance(context: Context): LyraMemoryDatabase = LyraMemoryDatabase.get(context)
}
