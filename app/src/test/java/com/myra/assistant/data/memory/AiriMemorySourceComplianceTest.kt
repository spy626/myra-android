package com.myra.assistant.data.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AiriMemorySourceComplianceTest {
    private val root = File("src/main/java/com/myra/assistant")

    @Test fun exactlyOneOwnerAndOneRoomDatabaseRemain() {
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals(1, sources.sumOf { Regex("class MemoryBrainCoordinator\\b").findAll(it.readText()).count() })
        assertEquals(1, sources.sumOf { Regex("class LyraMemoryDatabase\\b").findAll(it.readText()).count() })
        assertEquals(1, sources.sumOf { Regex("Room\\.databaseBuilder").findAll(it.readText()).count() })
    }

    @Test fun legacyNaturalMemoryOwnersCannotReturn() {
        val forbidden = setOf("MemoryBrainV2.kt", "MemoryCommandParser.kt", "NaturalMemoryExtractor.kt",
            "PersonalMemoryExtractor.kt", "PersonLinkedMemoryExtractor.kt", "BestFriendNameCorrectionParser.kt",
            "SemanticMemoryProposalValidator.kt", "AutomaticMemoryExtractor.kt", "AutomaticMemoryChangeParser.kt")
        assertTrue(root.walkTopDown().filter { it.isFile }.none { it.name in forbidden })
    }

    @Test fun geminiAndVoiceServiceCannotWriteDaoDirectly() {
        val gemini = File(root, "ai/GeminiLiveClient.kt").readText()
        val service = File(root, "service/MyraVoiceService.kt").readText()
        assertFalse(gemini.contains("AiriMemoryDao")); assertFalse(gemini.contains("RoomAiriMemoryStore"))
        assertFalse(service.contains("airiMemoryDao()")); assertFalse(service.contains("insertSemantic("))
        assertTrue(service.contains("memoryBrain.executeFinalTurnPlan"))
    }

    @Test fun simpleRecallIsBoundedLocalStoreWork() {
        val store = File(root, "data/memory/AiriMemoryStore.kt").readText()
        assertTrue(store.contains("limit.coerceIn(1, 8)"))
        assertFalse(store.contains("Gemini")); assertFalse(store.contains("http")); assertFalse(store.contains("Retrofit"))
    }

    @Test fun simpleRecallCrossesServiceBoundaryWithoutGeminiToolStaging() {
        val lane = File(root, "data/memory/LocalFastMemoryLane.kt").readText()
        val service = File(root, "service/MyraVoiceService.kt").readText()
        assertTrue(lane.contains("class LocalFastMemoryLane"))
        assertTrue(lane.contains("owner.recall"))
        assertFalse(lane.contains("GeminiLiveClient"))
        assertFalse(lane.contains("query_user_memory"))
        assertTrue(service.contains("fastMemoryLane.recall(finalUtterance.memoryEvidence)"))
        assertTrue(service.contains("networkCall=false"))
    }

    @Test fun allProductionDurableWritersAreCoordinatorOwned() {
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        val roomConstructors = sources.filter { it.readText().contains("RoomAiriMemoryStore(") }
        assertEquals(setOf("AiriMemoryStore.kt", "AiriMemoryCoordinator.kt"), roomConstructors.map { it.name }.toSet())
        val passive = File(root, "data/memory/BehaviorMemoryLearner.kt").readText()
        val ui = File(root, "ui/settings/MemorySettingsActivity.kt").readText()
        assertFalse(passive.contains("RoomAiriMemoryStore("))
        assertTrue(passive.contains("owner.recordBehaviorObservation"))
        assertFalse(ui.contains(".forgetCard(")); assertFalse(ui.contains(".renamePerson(")); assertFalse(ui.contains(".clearAll("))
        assertTrue(ui.contains("memoryOwner.deleteMemory")); assertTrue(ui.contains("memoryOwner.renameFromManualUi"))
    }

    @Test fun entityAnchorsAreNotStoredAsUserFacingCards() {
        val entity = File(root, "data/memory/MemoryEntity.kt").readText()
        val store = File(root, "data/memory/AiriMemoryStore.kt").readText()
        assertTrue(entity.contains("data class PersonEntity"))
        assertFalse(store.contains("person known to Zopy"))
    }
}
