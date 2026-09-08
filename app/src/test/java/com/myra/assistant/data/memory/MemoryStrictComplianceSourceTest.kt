package com.myra.assistant.data.memory

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStrictComplianceSourceTest {
    private val sourceRoot = File("src/main")

    @Test fun memoryCoreUsesDrawableIconsInsteadOfDecorativeUnicodeGlyphs() {
        val source = File(sourceRoot, "java/com/myra/assistant/ui/settings/MemorySettingsActivity.kt").readText()
        assertTrue(source.contains("ImageView"))
        assertTrue(source.contains("categoryIconResource"))
        listOf("✦", "♙", "♡", "▣", "◎", "↻", "◇", "▤", "◉", "✧").forEach {
            assertFalse(source.contains("\"$it\""))
        }
    }

    @Test fun serviceDelegatesCorrectionSemanticsToMemoryBrain() {
        val source = File(sourceRoot, "java/com/myra/assistant/service/MyraVoiceService.kt").readText()
        assertFalse(source.contains("BestFriendNameCorrectionParser.analyze("))
        assertFalse(source.contains("BestFriendNameCorrectionParser.validateNewName("))
        assertFalse(source.contains("ClarifiedPersonNameResolver.resolve("))
        assertFalse(source.contains("memoryBrain.assessFinalTurn("))
        assertTrue(source.contains("memoryBrain.prepareFinalTurn("))
        assertTrue(source.contains("memoryBrain.executeFinalTurnPlan("))
        assertTrue(source.contains("MEMORY_SEMANTIC_PROPOSAL_STAGED"))
        assertFalse(source.contains("memoryBrain.processPersonRename("))
        assertTrue(source.contains("memoryBrain.processStructuredCorrection("))
        assertFalse(source.contains("memoryRepository.saveGrounded("))
        assertFalse(source.contains("memoryRepository.forgetMatching("))
        assertFalse(source.contains("memoryRepository.renamePerson("))
        val userProposalHandler = source.substringAfter("private fun handleSemanticMemoryProposal")
            .substringBefore("private fun handlePendingConfirmation")
        assertFalse(userProposalHandler.contains("processGroundedProposal("))
    }

    @Test fun semanticInterpretersCannotOwnPersistence() {
        val semantic = File(
            sourceRoot,
            "java/com/myra/assistant/data/memory/MemorySemanticInterpreter.kt"
        ).readText()
        val commandParser = File(
            sourceRoot,
            "java/com/myra/assistant/data/memory/MemoryCommandParser.kt"
        ).readText()
        assertFalse(semantic.contains("MemoryRepository"))
        assertFalse(semantic.contains("MemoryDao"))
        assertFalse(semantic.contains("matchEntire("))
        assertFalse(semantic.contains("containsMatchIn("))
        assertFalse(commandParser.contains("MemoryRepository"))
        assertFalse(commandParser.contains("MemoryDao"))
    }
}
