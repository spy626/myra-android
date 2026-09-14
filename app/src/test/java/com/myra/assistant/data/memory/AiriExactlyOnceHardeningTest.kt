package com.myra.assistant.data.memory

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiriExactlyOnceHardeningTest {
    @Test fun localWakeAndWorkManagerRaceCrossesPredictCalibrateOnce() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val message = ConversationTruthEntity(
            "race-message", "race", 0, 1, "race:1", "user",
            "I build accessible software", 1,
            canonicalText = "I build accessible software",
            displayText = "I build accessible software"
        )
        store.appendConversation(message)
        val span = EpisodeSpanEntity(
            "race-span", "race", 0, 0,
            SegmentClassification.INFORMATIVE.name, "EOF", 2
        )
        store.saveEpisodeSpan(span)
        val episodeId = store.ensureEpisodeForSpan(span, listOf(message))!!
        val provider = BlockingReasoningProvider()
        val owner = MemoryBrainCoordinator(store, provider, recoverOnInit = false)

        owner.scheduleEpisodeConsolidation(episodeId)
        provider.started.await()

        val competingWorker = async { owner.runDurableBackgroundWork() }
        assertEquals(0, competingWorker.await())
        assertEquals(1, provider.calibrations.get())

        provider.release.complete(Unit)
        repeat(100) {
            if (store.episodes.single().first.consolidatedAt != null &&
                "CONSOLIDATION:$episodeId" !in store.backgroundWork) return@repeat
            delay(10)
        }

        assertEquals(1, provider.calibrations.get())
        assertNotNull(store.episodes.single().first.consolidatedAt)
        assertFalse("CONSOLIDATION:$episodeId" in store.backgroundWork)
    }

    @Test fun concurrentDurableDrainsClaimOneConsolidationExecution() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val message = ConversationTruthEntity(
            "worker-message", "workers", 0, 2, "workers:2", "user",
            "I value accessible software", 1,
            canonicalText = "I value accessible software",
            displayText = "I value accessible software"
        )
        store.appendConversation(message)
        val span = EpisodeSpanEntity(
            "worker-span", "workers", 0, 0,
            SegmentClassification.INFORMATIVE.name, "EOF", 2
        )
        store.saveEpisodeSpan(span)
        val episodeId = store.ensureEpisodeForSpan(span, listOf(message))!!
        store.enqueueBackgroundWork("CONSOLIDATION", episodeId, 10)
        val provider = BlockingReasoningProvider()
        val owner = MemoryBrainCoordinator(store, provider, recoverOnInit = false)

        val first = async { owner.runDurableBackgroundWork(20) }
        provider.started.await()
        val second = async { owner.runDurableBackgroundWork(20) }
        assertEquals(0, second.await())
        assertEquals(1, provider.calibrations.get())

        provider.release.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(1, provider.calibrations.get())
        assertFalse("CONSOLIDATION:$episodeId" in store.backgroundWork)
    }

    @Test fun duplicateLegacyRelationshipEnumsAreNotAuthorities() = runBlocking {
        val operation = JSONObject()
            .put("intent", "ADD_RELATIONSHIP")
            .put("person", "Tavish")
            .put("relationship", "BEST_FRIEND")
            .put("replacement_relationship", "BEST_FRIEND")
            .put("semantic_relationship", "FRIEND")
            .put("source_span", "Tavish is a person I know as a friend")
            .put("confidence", .95)
            .put("assertion_mode", "USER_ASSERTED")
        val parsed = GeminiMemoryOperationParser.parse(
            JSONObject().put("operations", JSONArray().put(operation))
        ).single()
        assertNull(parsed.relationship)
        assertNull(parsed.replacementRelationship)
        assertEquals(PersonRelationship.FRIEND, parsed.semanticRelationship)

        val store = InMemoryAiriMemoryStore()
        val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        AiriMemoryRuntime.claimTurn("single-authority", 3)
        val evidence = AuthoritativeMemoryTurnEvidence(
            3, "Tavish is a person I know as a friend",
            "Tavish is a person I know as a friend",
            listOf("Tavish"), listOf("Tavish"),
            "single-authority", "single-authority:3", 3
        )
        val plan = owner.prepareFinalTurn(
            evidence, listOf(parsed.copy(sourceTurnId = 3))
        )
        val outcome = owner.executeFinalTurnPlan(plan, evidence)
        assertTrue(outcome is MemoryBrainOutcome.Mutated)
        assertEquals(
            PersonRelationship.FRIEND.name,
            store.relationships.single { it.active }.relationshipType
        )
    }

    @Test fun missingSemanticStrengthRejectsBeforePersonProjection() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        AiriMemoryRuntime.claimTurn("missing-strength", 4)
        val evidence = AuthoritativeMemoryTurnEvidence(
            4, "Aarav is someone close to me", "Aarav is someone close to me",
            listOf("Aarav"), listOf("Aarav"),
            "missing-strength", "missing-strength:4", 4
        )
        val frame = MemorySemanticFrame(
            MemorySemanticIntent.ADD_RELATIONSHIP,
            person = "Aarav",
            relationship = PersonRelationship.BEST_FRIEND,
            confidence = .95,
            sourceSpan = evidence.canonicalText,
            sourceTurnId = 4
        )
        val plan = owner.prepareFinalTurn(evidence, listOf(frame))
        assertEquals(MemoryDecision.REJECT, plan.decision)
        assertTrue(store.people.isEmpty())
        assertTrue(store.relationships.isEmpty())
    }

    private class BlockingReasoningProvider : MemoryReasoningProvider {
        val calibrations = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun classifyPrimitive(messages: List<ConversationTruthEntity>) =
            SegmentClassification.INFORMATIVE
        override suspend fun splitPrimitive(messages: List<ConversationTruthEntity>) = emptyList<Long>()
        override suspend fun resegmentInformative(
            messages: List<ConversationTruthEntity>, softBoundaries: List<Long>
        ) = emptyList<Long>()
        override suspend fun reviewBoundaries(
            messages: List<ConversationTruthEntity>, candidates: List<SegmentBoundary>
        ) = emptyList<ReviewedBoundary>()
        override suspend fun predict(title: String, facts: List<ConsolidationCandidate>) = ""
        override suspend fun calibrate(
            title: String, content: String, prediction: String?,
            facts: List<ConsolidationCandidate>
        ): List<EpisodeSemanticAction> {
            calibrations.incrementAndGet()
            started.complete(Unit)
            release.await()
            return emptyList()
        }
        override suspend fun rateEpisodes(
            context: List<ConversationTruthEntity>,
            episodes: List<MemoryEntity>,
            queries: Map<String, List<String>>
        ) = emptyMap<String, EpisodeReviewRating>()
    }
}
