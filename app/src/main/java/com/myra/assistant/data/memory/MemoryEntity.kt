package com.myra.assistant.data.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [Index(value = ["stableKey"], unique = true), Index(value = ["category", "active"]), Index(value = ["entityId"])]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val stableKey: String,
    val category: String,
    val fact: String,
    val normalizedFact: String,
    val sensitivity: String,
    val confidence: Double,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastConfirmedAt: Long,
    val active: Boolean = true,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
    val provenance: String = MemoryProvenance.LEGACY.name,
    val lifecycleStatus: String = MemoryLifecycleStatus.ACTIVE.name,
    val supersededById: String? = null,
    val entityId: String? = null,
    val entityName: String? = null,
    val lastRecalledAt: Long = 0,
    val observationMetadata: String? = null
)

@Entity(
    tableName = "behavior_observations",
    indices = [Index(value = ["stableKey"], unique = true), Index(value = ["kind", "lastObservedAt"])]
)
data class BehaviorObservationEntity(
    @PrimaryKey val id: String,
    val stableKey: String,
    val kind: String,
    val label: String,
    val observationCount: Int,
    val sessionCount: Int,
    val dayCount: Int,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val lastSessionId: String,
    val lastDayBucket: Long,
    val metadata: String? = null,
    val promotedMemoryId: String? = null
)
