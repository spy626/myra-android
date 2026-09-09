package com.myra.assistant.data.memory

import java.io.File
import org.junit.Assert.assertEquals
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
        assertTrue(source.contains("sendToolHeld(id, \"propose_user_memory\")"))
        assertFalse(source.contains("sendToolResponse(id, \"propose_user_memory\", true"))
        assertTrue(source.contains("MEMORY_RESPONSE_OWNER"))
        assertTrue(source.contains("verifiedBeforeResponse=true"))
        assertTrue(source.contains("stagedMemoryRecalls"))
        val proposalHandler = source.substringAfter("private fun handleSemanticMemoryProposal")
            .substringBefore("private fun parseMemorySemanticOperations")
        assertFalse(proposalHandler.contains("MemoryCommandParser.looksLikeIntent("))
        assertTrue(proposalHandler.contains("MemoryCommandParser.parse("))
        assertTrue(proposalHandler.contains("StagedMemoryProposalPolicy.merge("))
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

    @Test fun structuredLinkedAndRelationshipWritesUseCoordinatorValidatedAuthority() {
        val brain = File(sourceRoot, "java/com/myra/assistant/data/memory/MemoryBrainV2.kt").readText()
        val linkedBlock = brain.substringAfter("MemorySemanticIntent.ADD_LINKED_FACT ->")
            .substringBefore("MemorySemanticIntent.UPDATE_FACT")
        val relationshipBlock = brain.substringAfter("MemorySemanticIntent.ADD_RELATIONSHIP ->")
            .substringBefore("MemorySemanticIntent.REMOVE_RELATIONSHIP")
        assertTrue(brain.contains("FinalTurnSourceSpanAuthorizer.authorize("))
        assertFalse(brain.contains("SemanticMemoryProposalValidator"))
        assertFalse(linkedBlock.contains("MemorySensitivity.PERSONAL"))
        assertTrue(linkedBlock.contains("frame.validatedCandidate"))
        assertTrue(relationshipBlock.contains("addPersonRelationship(person, relation)"))
        assertFalse(relationshipBlock.contains("addPersonRelationship(person, relation, frame.fact)"))
    }

    @Test fun cutoverHasOneOwnerOneDatabaseAndHidesInfrastructureAnchors() {
        val memorySources = File(sourceRoot, "java/com/myra/assistant/data/memory")
            .walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals(1, memorySources.sumOf { "class MemoryBrainCoordinator".toRegex().findAll(it.readText()).count() })
        assertEquals(1, memorySources.sumOf { "@Database".toRegex().findAll(it.readText()).count() })
        assertFalse(memorySources.any { it.name == "SemanticMemoryProposalValidator.kt" })
        val ui = File(sourceRoot, "java/com/myra/assistant/ui/settings/MemorySettingsActivity.kt").readText()
        assertTrue(ui.contains("filterNot(::isInfrastructureAnchor)"))
        val gemini = File(sourceRoot, "java/com/myra/assistant/ai/GeminiLiveClient.kt").readText()
        assertFalse(gemini.contains("MemoryDao"))
        assertFalse(gemini.contains("MemoryRepository"))
    }
}
