package com.myra.assistant.data.memory

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** AIRI/plast-mem Android completion: durable semantic truth with retained history. */
@Entity(tableName = "airi_semantic_memory", indices = [Index(value = ["semanticKey", "active"]), Index(value = ["category", "active"]), Index(value = ["subjectEntityId"]), Index(value = ["updatedAt"]), Index(value = ["lastAccessed"])])
data class SemanticMemoryEntity(
    @PrimaryKey val memoryId: String, val semanticKey: String, val category: String,
    val statement: String, val normalizedStatement: String, val subjectEntityId: String? = null,
    val temporalScope: String, val confidence: Double, val importance: Int, val explicit: Boolean,
    val provenance: String, val sourceTurnId: Long, val sourceUtteranceId: String,
    val active: Boolean = true, val supersededById: String? = null,
    val createdAt: Long, val updatedAt: Long, val lastAccessed: Long,
    val accessCount: Int = 0, val deletedAt: Long? = null,
    val conversationId: String = "default", val validAt: Long = createdAt,
    val invalidAt: Long? = null, val embedding: String = ""
)

@Entity(tableName = "airi_people", indices = [Index(value = ["canonicalName"]), Index(value = ["active"])])
data class PersonEntity(
    @PrimaryKey val entityId: String, val canonicalName: String, val normalizedName: String,
    val createdAt: Long, val updatedAt: Long, val lastAccessed: Long,
    val accessCount: Int = 0, val active: Boolean = true, val deletedAt: Long? = null
)

@Entity(tableName = "airi_person_aliases", foreignKeys = [ForeignKey(entity = PersonEntity::class, parentColumns = ["entityId"], childColumns = ["entityId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["entityId"]), Index(value = ["normalizedAlias"])])
data class PersonAliasEntity(
    @PrimaryKey val aliasId: String, val entityId: String, val alias: String,
    val normalizedAlias: String, val sourceTurnId: Long, val createdAt: Long
)

@Entity(tableName = "airi_relationships", foreignKeys = [ForeignKey(entity = PersonEntity::class, parentColumns = ["entityId"], childColumns = ["targetEntityId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["targetEntityId"]), Index(value = ["relationshipType", "active"]), Index(value = ["updatedAt"])])
data class RelationshipEntity(
    @PrimaryKey val relationshipId: String, val targetEntityId: String, val relationshipType: String,
    val temporalScope: String, val confidence: Double, val provenance: String,
    val sourceTurnId: Long, val sourceUtteranceId: String, val active: Boolean = true,
    val supersededById: String? = null, val createdAt: Long, val updatedAt: Long,
    val lastAccessed: Long, val accessCount: Int = 0, val deletedAt: Long? = null
)

@Entity(tableName = "airi_episodes", indices = [Index(value = ["eventType"]), Index(value = ["occurredAt"]), Index(value = ["sourceTurnId"])])
data class EpisodicMemoryEntity(
    @PrimaryKey val episodeId: String, val eventType: String, val summary: String,
    val normalizedSummary: String, val temporalScope: String, val occurredAt: Long,
    val confidence: Double, val importance: Int, val provenance: String,
    val sourceTurnId: Long, val sourceUtteranceId: String, val createdAt: Long,
    val lastAccessed: Long, val accessCount: Int = 0, val deletedAt: Long? = null,
    val conversationId: String = "default", val startSequence: Long = sourceTurnId * 2,
    val endSequence: Long = sourceTurnId * 2 + 1, val title: String = eventType,
    val content: String = summary, val classification: String = "INFORMATIVE",
    val embedding: String = "", val stability: Double = 1.0, val difficulty: Double = 5.0,
    val surprise: Double = 0.0, val consolidatedAt: Long? = null,
    val lastReviewedAt: Long? = null, val isFlashbulb: Boolean = false
)

@Entity(tableName = "airi_episode_participants", primaryKeys = ["episodeId", "entityId"], foreignKeys = [ForeignKey(entity = EpisodicMemoryEntity::class, parentColumns = ["episodeId"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = PersonEntity::class, parentColumns = ["entityId"], childColumns = ["entityId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["entityId"])])
data class EpisodeParticipantEntity(val episodeId: String, val entityId: String)

@Entity(tableName = "airi_goals", indices = [Index(value = ["stableKey", "status"]), Index(value = ["updatedAt"]), Index(value = ["parentGoalId"])])
data class GoalMemoryEntity(
    @PrimaryKey val goalId: String, val stableKey: String, val title: String,
    val description: String?, val status: String, val priority: Int, val progress: Int,
    val deadline: Long? = null, val parentGoalId: String? = null, val category: String,
    val provenance: String, val sourceTurnId: Long, val sourceUtteranceId: String,
    val createdAt: Long, val updatedAt: Long, val lastAccessed: Long,
    val accessCount: Int = 0, val deletedAt: Long? = null
)

@Entity(tableName = "airi_behavior_patterns", indices = [Index(value = ["stableKey"], unique = true), Index(value = ["kind", "lastObservedAt"]), Index(value = ["state"])])
data class BehaviorObservationEntity(
    @PrimaryKey val patternId: String, val stableKey: String, val kind: String, val label: String,
    val observationCount: Int, val sessionCount: Int, val dayCount: Int,
    val firstObservedAt: Long, val lastObservedAt: Long, val lastSessionId: String,
    val lastDayBucket: Long, val state: String = "OBSERVED", val confidence: Double = 0.0,
    val importance: Int = 1, val metadata: String? = null
)

/** Append-only conversation truth. Prompt projection never deletes these rows. */
@Entity(tableName = "airi_conversation_truth", indices = [Index(value = ["sessionId", "sequence"], unique = true), Index(value = ["turnId"]), Index(value = ["committedAt"])])
data class ConversationTruthEntity(
    @PrimaryKey val messageId: String, val sessionId: String, val sequence: Long,
    val turnId: Long, val utteranceId: String, val role: String, val content: String,
    val committedAt: Long, val rawText: String = content, val canonicalText: String = content,
    val displayText: String = content, val source: String = "VOICE", val finalized: Boolean = true,
    val provenanceMetadata: String? = null
)

/** Plast-Mem current stateful segmentation claim, scoped by conversation. */
@Entity(tableName = "airi_segmentation_state")
data class SegmentationStateEntity(
    @PrimaryKey val conversationId: String, val lastMessageSequence: Long,
    val eofIdentified: Boolean, val nextSegmentStartSequence: Long,
    val activeSegmentStartSequence: Long? = null, val activeSegmentEndSequence: Long? = null,
    val activeSince: Long? = null, val claimId: String? = null, val generation: Long = 0
)

/** Immutable committed segmentation range. The unresolved tail is not written here. */
@Entity(tableName = "airi_episode_spans", indices = [Index(value = ["conversationId", "startSequence"], unique = true), Index(value = ["createdAt"])])
data class EpisodeSpanEntity(
    @PrimaryKey val spanId: String, val conversationId: String,
    val startSequence: Long, val endSequence: Long, val classification: String,
    val boundaryReason: String, val createdAt: Long
)

/** Retrieval side-effect queue; consumed by the one owner's episodic review lane. */
@Entity(tableName = "airi_pending_review", indices = [Index(value = ["conversationId", "createdAt"])])
data class PendingReviewEntity(
    @PrimaryKey val reviewId: String, val conversationId: String,
    val episodeIds: String, val queryFingerprint: String, val createdAt: Long
)

/** Many-to-many semantic provenance matching Plast-Mem source_episodic_ids. */
@Entity(tableName = "airi_semantic_provenance", primaryKeys = ["memoryId", "episodeId"],
    indices = [Index(value = ["episodeId"])])
data class SemanticProvenanceEntity(val memoryId: String, val episodeId: String)

/** Metadata-only durable trace for Spark notify/command parentage and outcome. */
@Entity(tableName = "airi_spark_trace", indices = [Index(value = ["eventId"]), Index(value = ["createdAt"])])
data class SparkTraceEntity(
    @PrimaryKey val traceId: String, val eventId: String, val parentEventId: String?,
    val source: String, val lane: String, val kind: String, val outcome: String,
    val createdAt: Long
)

@Fts4
@Entity(tableName = "airi_semantic_fts")
data class SemanticMemoryFtsEntity(
    @PrimaryKey @androidx.room.ColumnInfo(name = "rowid") val rowId: Int,
    val memoryId: String, val fact: String
)

@Fts4
@Entity(tableName = "airi_episode_fts")
data class EpisodicMemoryFtsEntity(
    @PrimaryKey @androidx.room.ColumnInfo(name = "rowid") val rowId: Int,
    val episodeId: String, val searchText: String
)

/** User-facing/retrieval projection; not another stored truth. */
data class MemoryEntity(
    val id: String, val stableKey: String, val category: String, val fact: String,
    val confidence: Double, val provenance: String, val createdAt: Long, val updatedAt: Long,
    val entityId: String? = null, val entityName: String? = null,
    val lastRecalledAt: Long = 0, val temporalScope: String = MemoryTemporalScope.CURRENT.name,
    val importance: Int = 5, val explicit: Boolean = true, val kind: String = "SEMANTIC"
)
