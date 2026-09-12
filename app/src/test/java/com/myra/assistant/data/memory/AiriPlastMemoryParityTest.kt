package com.myra.assistant.data.memory

import com.myra.assistant.agent.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiriPlastMemoryParityTest {
    @Test fun temporalSegmentationKeepsTailUntilEofAndLocksHardBoundaries() {
        val base = listOf(
            message(0, 0, "user", "first useful turn"),
            message(1, 1_000, "assistant", "first reply"),
            message(2, AiriEventSegmenter.HARD_GAP_MS + 2_000, "user", "later useful turn")
        )
        val open = AiriEventSegmenter.plan(base, eof = false)
        assertEquals(listOf(0L..1L), open.finalized)
        assertEquals(2L..2L, open.carriedTail)
        assertTrue(open.boundaries.single().hard)
        assertEquals(listOf(0L..1L, 2L..2L), AiriEventSegmenter.plan(base, eof = true).finalized)
    }

    @Test fun ownerCommitsConversationSegmentationAndEpisodeIdempotently() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val evidence = evidence(4, "We investigated a renderer failure", "We investigated a renderer failure")
        owner.captureConversation(evidence, "The renderer fix was verified")
        assertEquals(2, store.conversationCount("parity"))
        assertEquals(1, store.spans.size)
        assertEquals(1, store.episodes.count { it.first.eventType == "conversation_segment" })
        owner.segmentCommittedConversation("parity", eof = true)
        assertEquals(1, store.spans.size)
        assertNull(store.segmentation["parity"]?.claimId)
    }

    @Test fun staleSegmentationClaimIsRecoveredButFreshClaimIsNotStolen() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        store.appendConversation(message(0, 1, "user", "A useful finalized message"))
        store.segmentation["c"] = SegmentationStateEntity("c", 0, true, 0, 0, 0,
            System.currentTimeMillis(), "fresh", 1)
        assertEquals(0, owner.segmentCommittedConversation("c", true))
        store.segmentation["c"] = store.segmentation.getValue("c").copy(activeSince = 0)
        assertEquals(1, owner.segmentCommittedConversation("c", true))
    }

    @Test fun semanticNewReinforceUpdateInvalidateLifecycleIsDistinct() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val first = evidence(10, "I prefer compact explanations", "I prefer compact explanations")
        execute(owner, first, fact(MemorySemanticIntent.ADD_FACT, "User prefers compact explanations", "response:length", first.displayText))
        val id = store.semantic.single().memoryId
        val reinforce = evidence(11, "I still prefer compact explanations", "I still prefer compact explanations")
        execute(owner, reinforce, fact(MemorySemanticIntent.ADD_FACT, "User prefers compact explanations", "response:length", reinforce.displayText))
        assertEquals(1, store.semantic.size); assertEquals(id, store.semantic.single().memoryId)
        assertTrue(store.semantic.single().confidence > .9)
        val update = evidence(12, "Now I prefer detailed explanations", "Now I prefer detailed explanations")
        execute(owner, update, fact(MemorySemanticIntent.UPDATE_FACT, "User prefers detailed explanations", "response:length", update.displayText))
        assertEquals(1, store.semantic.count { it.active }); assertEquals(1, store.semantic.count { !it.active })
        val invalidate = evidence(13, "That answer preference is no longer true", "That answer preference is no longer true")
        execute(owner, invalidate, MemorySemanticFrame(MemorySemanticIntent.INVALIDATE_FACT,
            stableKey = "response:length", confidence = .97, sourceSpan = invalidate.displayText))
        assertTrue(store.semantic.none { it.active })
    }

    @Test fun hypotheticalAndReportedSpeechCannotBecomeUserFacts() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val hypothetical = evidence(20, "If I liked jazz I would attend", "If I liked jazz I would attend")
        val h = fact(MemorySemanticIntent.ADD_FACT, "User likes jazz", "interest:jazz", hypothetical.displayText)
            .copy(assertionMode = MemoryAssertionMode.HYPOTHETICAL)
        assertEquals(MemoryDecision.REJECT, owner.prepareFinalTurn(hypothetical, listOf(h)).decision)
        val report = evidence(21, "Ravi said he likes jazz", "Ravi said he likes jazz", listOf("Ravi"))
        val r = h.copy(sourceSpan = report.displayText, assertionMode = MemoryAssertionMode.REPORTED_SPEECH)
        assertEquals(MemoryDecision.REJECT, owner.prepareFinalTurn(report, listOf(r)).decision)
        assertTrue(store.semantic.isEmpty())
    }

    @Test fun localVectorRrfFsrsAndFlashbulbPoliciesAreDeterministic() {
        val related = FeatureHashEmbeddingProvider.cosine(
            FeatureHashEmbeddingProvider.embed("finish android memory architecture"),
            FeatureHashEmbeddingProvider.embed("android memory architecture completion")
        )
        val unrelated = FeatureHashEmbeddingProvider.cosine(
            FeatureHashEmbeddingProvider.embed("finish android memory architecture"),
            FeatureHashEmbeddingProvider.embed("cook vegetable soup")
        )
        assertTrue(related > unrelated)
        val fused = ReciprocalRankFusion.merge(listOf(listOf("a", "b"), listOf("b", "c")), { it }, 3)
        assertEquals("b", fused.first().first)
        val reviewed = AiriFsrs.review(FsrsState(1.0, 5.0, null), EpisodeReviewRating.GOOD, 100_000_000)
        assertTrue(reviewed.stability > 1.0)
        assertEquals(reviewed, AiriFsrs.review(reviewed, EpisodeReviewRating.AGAIN, 100_000_001))
        assertTrue(FlashbulbPolicy.retrievalMultiplier(.1, true, .9) > FlashbulbPolicy.retrievalMultiplier(.1, false, 0.0))
    }

    @Test fun sparkCommandAllIntentsContextIsolationAndStaleGuard() {
        val registry = LyraContextRegistry(); val delivered = mutableListOf<SparkCommand>()
        val runtime = LyraSparkRuntime(registry, delivered::add)
        SparkIntent.entries.forEachIndexed { index, intent ->
            val result = runtime.dispatch(SparkCommand(eventId = "e$index", destinations = listOf("memory-lane"),
                intent = intent, contexts = listOf(SparkContextPatch("memory", "recall", ContextMutation.REPLACE_SELF,
                    intent.name, index.toLong()))))
            assertTrue(result.accepted)
        }
        runtime.dispatch(SparkCommand(eventId = "screen", destinations = listOf("screen-lane"),
            contexts = listOf(SparkContextPatch("screen", "scene", ContextMutation.REPLACE_SELF, "visible", 2))))
        assertTrue(registry.bucket("memory:recall").isNotEmpty())
        assertTrue(registry.bucket("screen:scene").isNotEmpty())
        assertTrue(runtime.dispatch(SparkCommand(eventId = "stale", destinations = listOf("memory-lane"),
            contexts = listOf(SparkContextPatch("memory", "recall", ContextMutation.APPEND_SELF, "old", 0)))).stale)
        assertEquals(SparkGuidanceType.MEMORY_RECALL, SparkGuidance(SparkGuidanceType.MEMORY_RECALL,
            options = listOf(SparkGuidanceOption("friends", listOf("retrieve locally")))).type)
    }

    @Test fun sparkNotifySupportsNoResponseTextAndCommandWithParentLinkage() {
        val commands = mutableListOf<SparkCommand>(); val runtime = LyraSparkRuntime(LyraContextRegistry(), commands::add)
        val event = SparkNotifyEvent(eventId = "root", source = "notification", lane = "device", headline = "event")
        assertSame(SparkNotifyDecision.NoResponse, runtime.notify(event) { SparkNotifyDecision.NoResponse })
        assertEquals("hello", (runtime.notify(event) { SparkNotifyDecision.TextReaction("hello") } as SparkNotifyDecision.TextReaction).text)
        runtime.notify(event) { SparkNotifyDecision.Commands(listOf(SparkCommand(eventId = "", destinations = listOf("phone-lane")))) }
        assertEquals("root", commands.single().eventId); assertEquals("root", commands.single().parentEventId)
    }

    private suspend fun execute(owner: MemoryBrainCoordinator, e: AuthoritativeMemoryTurnEvidence, frame: MemorySemanticFrame) {
        val plan = owner.prepareFinalTurn(e, listOf(frame.copy(sourceTurnId = e.turnId)))
        val out = owner.executeFinalTurnPlan(plan, e)
        assertTrue("$plan / $out", out is MemoryBrainOutcome.Mutated || out is MemoryBrainOutcome.Deleted)
    }
    private fun fact(intent: MemorySemanticIntent, text: String, key: String, span: String) = MemorySemanticFrame(
        intent, fact = text, category = MemoryCategory.PREFERENCE, stableKey = key,
        temporalScope = MemoryTemporalScope.CURRENT, confidence = .9, sourceSpan = span
    )
    private fun evidence(turn: Long, canonical: String, display: String, names: List<String> = emptyList()): AuthoritativeMemoryTurnEvidence {
        AiriMemoryRuntime.claimTurn("parity", turn)
        return AuthoritativeMemoryTurnEvidence(turn, canonical, display, names, names, "parity", "p:$turn", turn)
    }
    private fun message(sequence: Long, at: Long, role: String, text: String) = ConversationTruthEntity(
        "m$sequence", "c", sequence, sequence, "u$sequence", role, text, at
    )
}
