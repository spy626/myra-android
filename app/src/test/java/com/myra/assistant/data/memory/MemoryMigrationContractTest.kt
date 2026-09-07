package com.myra.assistant.data.memory

import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

class MemoryMigrationContractTest {
    @Test fun v2ToV3PreservesRealRowsOnSQLite() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { statement ->
                statement.execute(
                    """CREATE TABLE memories (
                        id TEXT NOT NULL PRIMARY KEY,
                        stableKey TEXT NOT NULL,
                        category TEXT NOT NULL,
                        fact TEXT NOT NULL,
                        normalizedFact TEXT NOT NULL,
                        sensitivity TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        source TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastConfirmedAt INTEGER NOT NULL,
                        active INTEGER NOT NULL,
                        useCount INTEGER NOT NULL DEFAULT 0,
                        lastUsedAt INTEGER NOT NULL DEFAULT 0
                    )""".trimIndent()
                )
                statement.execute("CREATE UNIQUE INDEX index_memories_stableKey ON memories(stableKey)")
                statement.execute("CREATE INDEX index_memories_category_active ON memories(category, active)")
                statement.execute(
                    "INSERT INTO memories VALUES ('old-1','person:kareem','PERSON','Kareem is Zopy''s friend','kareem is zopy s friend','PERSONAL',0.96,'legacy',10,20,20,1,7,1234)"
                )
                statement.execute(
                    "INSERT INTO memories VALUES ('old-2','preference:old','PREFERENCE','Old preference','old preference','LOW',0.90,'legacy',11,21,21,0,2,4321)"
                )
                LyraMemoryDatabase.MIGRATION_2_3_SQL.forEach(statement::execute)
            }

            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id,fact,active,useCount,lastUsedAt,provenance,lifecycleStatus,lastRecalledAt FROM memories ORDER BY id"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("old-1", rows.getString("id"))
                    assertEquals("Kareem is Zopy's friend", rows.getString("fact"))
                    assertEquals(1, rows.getInt("active"))
                    assertEquals(7, rows.getInt("useCount"))
                    assertEquals(1234L, rows.getLong("lastUsedAt"))
                    assertEquals(MemoryProvenance.LEGACY.name, rows.getString("provenance"))
                    assertEquals(MemoryLifecycleStatus.ACTIVE.name, rows.getString("lifecycleStatus"))
                    assertEquals(1234L, rows.getLong("lastRecalledAt"))

                    assertTrue(rows.next())
                    assertEquals("old-2", rows.getString("id"))
                    assertEquals(0, rows.getInt("active"))
                    assertEquals(MemoryLifecycleStatus.INACTIVE.name, rows.getString("lifecycleStatus"))
                    assertEquals(4321L, rows.getLong("lastRecalledAt"))
                    assertFalse(rows.next())
                }

                statement.execute(
                    "INSERT INTO behavior_observations (id,stableKey,kind,label,observationCount,sessionCount,dayCount,firstObservedAt,lastObservedAt,lastSessionId,lastDayBucket) " +
                        "VALUES ('b1','behavior:app_usage:youtube','APP_USAGE','YouTube',5,3,2,1,2,'s2',2)"
                )
                statement.executeQuery("SELECT COUNT(*) AS total FROM behavior_observations").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt("total"))
                }
            }
        }
    }

    @Test fun productionMigrationObjectStillTargetsTwoToThree() {
        assertEquals(2, LyraMemoryDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, LyraMemoryDatabase.MIGRATION_2_3.endVersion)
    }
}
