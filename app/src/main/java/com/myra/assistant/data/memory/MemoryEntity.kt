package com.myra.assistant.data.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [Index(value = ["stableKey"], unique = true), Index(value = ["category", "active"])]
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
    val active: Boolean = true
)
