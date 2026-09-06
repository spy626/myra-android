package com.myra.assistant.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryMigrationContractTest {
    @Test fun v2ToV3IsRegisteredAndAdditive() {
        assertEquals(2, LyraMemoryDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, LyraMemoryDatabase.MIGRATION_2_3.endVersion)
        // Legacy defaults are intentionally compatible with every existing v2 row.
        assertEquals(MemoryProvenance.LEGACY.name, MemoryEntity("id", "key", "GOAL", "fact",
            "fact", "LOW", .9, "legacy", 1, 1, 1).provenance)
        assertTrue(MemoryEntity("id", "key", "GOAL", "fact", "fact", "LOW", .9,
            "legacy", 1, 1, 1).active)
    }
}
