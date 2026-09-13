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
        owner.appendConversationTruth(evidence, "The renderer fix was verified")
        owner.segmentCommittedConversation("parity", eof = false)
        assertEquals(2, store.conversationCount("parity"))
        assertTrue(store.spans.isEmpty())
        assertEquals(1, owner.flushConversation("parity", "TEST_EOF"))
        assertEquals(1, store.spans.size)
        assertEquals(1, store.episodes.count { it.first.eventType == "conversation_segment" })
        owner.flushConversation("parity", "TEST_EOF")
        assertEquals(1, store.spans.size)
        assertNull(store.segmentation["parity"]?.claimId)
        assertEquals("CLOSED", store.segmentation["parity"]?.conversationStatus)
    }

    @Test fun threeRelatedTurnsRemainOneUnresolvedTailUntilRealEof() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        owner.appendConversationTruth(evidence(30, "I went to the market today", "I went to the market today"), "What happened there?")
        owner.appendConversationTruth(evidence(31, "I met Devansh there", "I met Devansh there", listOf("Devansh")), "Nice")
        owner.appendConversationTruth(evidence(32, "Then we had coffee", "Then we had coffee"), "Sounds good")
        owner.segmentCommittedConversation("parity", eof = false)
        assertTrue(store.spans.isEmpty())
        assertNotNull(store.segmentation["parity"])
        assertFalse(store.segmentation.getValue("parity").eofIdentified)
        owner.flushConversation("parity", "TEST_EOF")
        assertEquals(1, store.spans.size)
        assertEquals(1, store.episodes.count { it.first.eventType == "conversation_segment" })
    }

    @Test fun committedEpisodeLinksVerifiedSemanticFactsBeforeConsolidatedMarker() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store)
        val turn = evidence(40, "I prefer concise technical summaries", "I prefer concise technical summaries")
        execute(owner, turn, fact(MemorySemanticIntent.ADD_FACT,
            "User prefers concise technical summaries", "response:detail", turn.displayText))
        owner.appendConversationTruth(turn, "Understood")
        owner.flushConversation("parity", "TEST_EOF")
        val episode = store.episodes.single { it.first.eventType == "conversation_segment" }.first
        assertNotNull(episode.consolidatedAt)
        assertEquals(listOf(SemanticProvenanceEntity(store.semantic.single().memoryId, episode.episodeId)),
            store.provenance)
        assertEquals(SemanticConsolidationAction.NEW.name, store.consolidationActions.single().action)
        assertEquals(episode.episodeId, store.consolidationActions.single().episodeId)
        assertNotNull(store.consolidationActions.single().calibratedAt)
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

    @Test fun abandonedOpenConversationIsRecoveredAfterProcessRestart() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        store.appendConversation(message(0, 1, "user", "A committed message survived process death"))
        store.segmentation["c"] = SegmentationStateEntity("c", 0, false, 0,
            conversationStatus = "ACTIVE", lastActivityAt = 1)
        val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        assertEquals(1, owner.recoverAbandonedConversations(System.currentTimeMillis()))
        assertEquals("CLOSED", store.segmentation.getValue("c").conversationStatus)
        assertEquals(1, store.spans.size)
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

    @Test fun fsrsSixDefaultParametersMatchPinnedUpstreamInference() {
        val initial = AiriFsrs.initial(EpisodeReviewRating.GOOD, 86_400_000L)
        assertEquals(2.3065, initial.stability, 0.000001)
        assertEquals(2.118103970459015, initial.difficulty, 0.000001)
        assertEquals(.9, AiriFsrs.retrievability(FsrsState(1.0, 5.0, 0), 86_400_000L), 0.000001)
        val next = AiriFsrs.review(initial, EpisodeReviewRating.GOOD, 2 * 86_400_000L)
        assertEquals(7.315300744077282, next.stability, 0.000001)
        assertEquals(2.1112142357853942, next.difficulty, 0.000001)
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
            persona = mapOf("cautiousness" to SparkPersonaStrength.HIGH),
            options = listOf(SparkGuidanceOption("friends", listOf("retrieve locally")))).type)
        runtime.dispatch(SparkCommand(eventId = "routed", destinations = listOf("memory-lane"), contexts = listOf(
            SparkContextPatch("memory", "detail", ContextMutation.REPLACE_SELF, "base", 3,
                ideas = listOf("structured query"), hints = listOf("stay local"),
                destinations = SparkContextDestinations.Filter(include = listOf("memory-lane")),
                metadata = mapOf("network" to false)))))
        assertTrue(registry.bucket("memory:detail").single().value.contains("structured query"))
        assertEquals("false", registry.bucket("memory:detail").single().metadata["network"])
    }

    @Test fun sparkNotifySupportsNoResponseTextAndCommandWithParentLinkage() {
        val commands = mutableListOf<SparkCommand>(); val runtime = LyraSparkRuntime(LyraContextRegistry(), commands::add)
        val event = SparkNotifyEvent(eventId = "root", source = "notification", lane = "device", headline = "event")
        assertSame(SparkNotifyDecision.NoResponse, runtime.notify(event) { SparkNotifyDecision.NoResponse })
        assertEquals("hello", (runtime.notify(event) { SparkNotifyDecision.TextReaction("hello") } as SparkNotifyDecision.TextReaction).text)
        runtime.notify(event) { SparkNotifyDecision.Commands(listOf(SparkCommand(eventId = "", destinations = listOf("phone-lane")))) }
        assertEquals("root", commands.single().eventId); assertEquals("root", commands.single().parentEventId)
        assertSame(SparkNotifyDecision.NoResponse, runtime.notify(event,
            SparkNotifyResponseControl(forceTextResponse = true)) { SparkNotifyDecision.Commands(emptyList()) })
    }

    @Test fun sparkNotifyAttentionQueueDeduplicatesSchedulesAndRetries() {
        var now = 1_000L; val runtime = LyraSparkRuntime(LyraContextRegistry(), commandSink = { })
        val scheduler = SparkNotifyScheduler(runtime, clock = { now }, requeueDelayMs = 5, maxAttempts = 2)
        val event = SparkNotifyEvent(eventId = "queued", source = "task", lane = "reminder",
            headline = "due", urgency = SparkUrgency.SOON)
        assertTrue(scheduler.enqueue(event)); assertFalse(scheduler.enqueue(event))
        assertNull(scheduler.tick { SparkNotifyDecision.TextReaction("early") })
        now += 10_000
        assertEquals("ready", (scheduler.tick { SparkNotifyDecision.TextReaction("ready") } as SparkNotifyDecision.TextReaction).text)
        val retry = event.copy(eventId = "retry", urgency = SparkUrgency.IMMEDIATE)
        scheduler.enqueue(retry); var calls = 0
        scheduler.tick { calls++; error("temporary") }
        assertEquals(1, scheduler.pendingCount())
        now += 5
        scheduler.tick { calls++; SparkNotifyDecision.NoResponse }
        assertEquals(2, calls); assertEquals(0, scheduler.pendingCount())
    }

    @Test fun modelReviewedBoundariesKeepHardBoundaryAndRejectUnapprovedCandidates() {
        val messages = (0L..25L).map { message(it, it * 1_000, if (it % 2L == 0L) "user" else "assistant", "topic ${it / 8} message $it") }
        val base = AiriEventSegmenter.plan(messages, eof = true)
        val candidate = base.boundaries.first()
        val reviewed = AiriEventSegmenter.applyReview(messages, true, base,
            listOf(ReviewedBoundary(candidate.afterSequence, true, .91, "topic changed")))
        assertTrue(reviewed.boundaries.any { it.afterSequence == candidate.afterSequence && it.confidence == .91 })
        val rejected = AiriEventSegmenter.applyReview(messages, true, base,
            base.boundaries.map { ReviewedBoundary(it.afterSequence, false, .99, "same event") })
        assertTrue(rejected.boundaries.all { it.hard })
    }

    @Test fun activeProductionSegmentationMatchesPrimitiveSoftHardAndCarryContracts() = runBlocking {
        val tiny = listOf(message(0, 0, "user", "ok"), message(1, 1, "assistant", "noted"))
        val low = AiriActiveSegmentationPipeline.plan(tiny, true,
            FakeReasoningProvider(primitiveClassification = SegmentClassification.LOW_INFO))
        assertEquals(SegmentClassification.LOW_INFO, low.classifications.values.single())
        val informative = AiriActiveSegmentationPipeline.plan(tiny, true,
            FakeReasoningProvider(primitiveClassification = SegmentClassification.INFORMATIVE))
        assertEquals(SegmentClassification.INFORMATIVE, informative.classifications.values.single())

        val long = (0L..24L).map { message(it, it * 1_000, if (it % 2L == 0L) "user" else "assistant", "message $it") }
        val split = AiriActiveSegmentationPipeline.plan(long, true,
            FakeReasoningProvider(primitiveSplits = listOf(12)))
        assertEquals(listOf(0L..11L, 12L..24L), split.finalized)

        val soft = (0L..9L).map { sequence -> message(sequence,
            if (sequence < 5) sequence * 1_000 else AiriEventSegmenter.SOFT_GAP_MS + sequence * 1_000,
            if (sequence % 2L == 0L) "user" else "assistant", "informative $sequence") }
        assertEquals(1, AiriActiveSegmentationPipeline.plan(soft, true,
            FakeReasoningProvider(resegmentSplits = emptyList())).finalized.size)
        assertEquals(2, AiriActiveSegmentationPipeline.plan(soft, true,
            FakeReasoningProvider(resegmentSplits = listOf(5))).finalized.size)
        val hard = soft.map { if (it.sequence < 5) it else it.copy(committedAt = AiriEventSegmenter.HARD_GAP_MS + it.sequence * 1_000 + 1) }
        val hardPlan = AiriActiveSegmentationPipeline.plan(hard, true, FakeReasoningProvider())
        assertEquals(2, hardPlan.finalized.size)
        assertTrue(hardPlan.boundaries.any { it.hard })
        val open = AiriActiveSegmentationPipeline.plan(long, false,
            FakeReasoningProvider(primitiveSplits = listOf(12)))
        assertEquals(listOf(0L..11L), open.finalized); assertEquals(12L..24L, open.carriedTail)
    }

    @Test fun relevantEpisodeCandidatesPreferOldMatchingFactOverRecentNoise() = runBlocking {
        val store = InMemoryAiriMemoryStore(); val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        val old = evidence(500, "Aarav works on the Aurora project", "Aarav works on the Aurora project", listOf("Aarav", "Aurora"))
        execute(owner, old, fact(MemorySemanticIntent.ADD_FACT, "Aarav works on the Aurora project", "project:aurora", old.displayText))
        repeat(25) { index ->
            val e = evidence(501L + index, "Unrelated preference $index", "Unrelated preference $index")
            execute(owner, e, fact(MemorySemanticIntent.ADD_FACT, "Speaker has unrelated preference $index", "noise:$index", e.displayText))
        }
        val candidates = store.semanticCandidatesForEpisode("parity", "Aurora project planning with Aarav", 20)
        assertEquals(AiriText.semanticKey("project:aurora"), candidates.first().semanticKey)
    }

    @Test fun backgroundRelationshipAndGoalConvergeIntoFastStructuredRecall() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        suspend fun episode(sequence: Long, text: String, action: EpisodeSemanticAction) {
            val row = ConversationTruthEntity("bg-$sequence", "c", sequence, sequence, "bg:$sequence", "user",
                text, sequence, canonicalText = text, displayText = text,
                provenanceMetadata = action.criticalLiterals.joinToString("\u001F"))
            store.appendConversation(row)
            val span = EpisodeSpanEntity("bg-span-$sequence", "c", sequence, sequence,
                SegmentClassification.INFORMATIVE.name, "EOF", sequence)
            store.saveEpisodeSpan(span); val id = store.ensureEpisodeForSpan(span, listOf(row))!!
            assertEquals(1, MemoryBrainCoordinator(store, FakeReasoningProvider(actions = listOf(action)), false).consolidateEpisode(id))
        }
        episode(600, "Devansh is my very good friend", EpisodeSemanticAction(SemanticConsolidationAction.NEW,
            "Devansh is the speaker's good friend", "RELATIONSHIP", null, .96,
            criticalLiterals = listOf("Devansh"), person = "Devansh", relationship = "GOOD_FRIEND"))
        episode(601, "My goal is to complete Aurora", EpisodeSemanticAction(SemanticConsolidationAction.NEW,
            "Speaker aims to complete Aurora", "GOAL", null, .96,
            criticalLiterals = listOf("Aurora"), goalTitle = "Complete Aurora"))
        val owner = MemoryBrainCoordinator(store, recoverOnInit = false)
        assertEquals("Devansh", owner.recall("friends", type = MemoryRecallType.FRIENDS).rows.single().entityName)
        assertTrue(owner.recall("goals", type = MemoryRecallType.GOALS).rows.single().fact.contains("Aurora"))
        assertTrue(store.semantic.isEmpty())
    }

    @Test fun e5ContractUsesPrefixesMasksBoundsAndRejectsIncompatibleVectors() {
        val json = """{"model":{"type":"Unigram","unk_id":3,"vocab":[["<s>",0],["<pad>",0],["</s>",0],["<unk>",-10],["▁query",5],[":",4],["▁hello",5]]}}"""
        val tokenizer = XlmRobertaUnigramTokenizer.fromJson(json)
        assertArrayEquals(longArrayOf(0, 4, 5, 6, 2), tokenizer.encode(AndroidE5EmbeddingProvider.queryInput("hello"), 8))
        assertEquals("passage: hello", AndroidE5EmbeddingProvider.passageInput("hello"))
        assertEquals(4, tokenizer.encode("hello hello hello", 4).size)
        val inputs = E5InputBuilder.build(longArrayOf(0, 6, 2))
        assertArrayEquals(longArrayOf(1, 1, 1), inputs.attentionMask.single())
        assertArrayEquals(longArrayOf(0, 0, 0), inputs.tokenTypes.single())
        val pooled = E5Pooling.meanNormalized(arrayOf(floatArrayOf(3f, 0f), floatArrayOf(1f, 0f)), 2)
        assertEquals(1.0, pooled[0], 1e-9); assertEquals(0.0, pooled[1], 1e-9)
        val provider = object : LocalEmbeddingProvider {
            override val modelId = "e5"; override val version = 2; override val dimensions = 384
            override val isNeuralReady = true; override fun embed(text: String) = DoubleArray(384)
        }
        assertTrue(EmbeddingCompatibility.matches(provider, "e5", 2, 384, "0.1"))
        assertFalse(EmbeddingCompatibility.matches(provider, "lyra-feature-hash", 1, 64, "0.1"))
        assertFalse(EmbeddingCompatibility.matches(provider, "e5", 1, 384, "0.1"))
    }

    @Test fun episodeDrivenColdStartCreatesFactAndMarksConsolidatedOnlyAfterAtomicApply() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val user = message(0, 1, "user", "I build accessible Android software")
        store.appendConversation(user)
        val span = EpisodeSpanEntity("span", "c", 0, 0, SegmentClassification.INFORMATIVE.name, "EOF", 2)
        store.saveEpisodeSpan(span)
        val episodeId = store.ensureEpisodeForSpan(span, listOf(user))!!
        val provider = FakeReasoningProvider(actions = listOf(EpisodeSemanticAction(
            SemanticConsolidationAction.NEW, "Speaker builds accessible Android software", "IDENTITY", null, .95)))
        val owner = MemoryBrainCoordinator(store, provider, recoverOnInit = false)
        assertEquals(1, owner.consolidateEpisode(episodeId))
        assertEquals(1, store.semantic.count { it.active })
        assertEquals(episodeId, store.provenance.single().episodeId)
        assertNotNull(store.episodes.single().first.consolidatedAt)
    }

    @Test fun predictCalibrateRejectsHallucinatedTargetIds() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val e = evidence(91, "I prefer clear summaries", "I prefer clear summaries")
        execute(MemoryBrainCoordinator(store, recoverOnInit = false), e,
            fact(MemorySemanticIntent.ADD_FACT, "Speaker prefers clear summaries", "response:clarity", e.displayText))
        val message = ConversationTruthEntity("pc-user", "parity", 300, 92, "pc:92", "user",
            "I still prefer clear summaries", 5)
        store.appendConversation(message)
        val span = EpisodeSpanEntity("pc-span", "parity", 300, 300, SegmentClassification.INFORMATIVE.name, "EOF", 6)
        store.saveEpisodeSpan(span); val episode = store.ensureEpisodeForSpan(span, listOf(message))!!
        val provider = FakeReasoningProvider(actions = listOf(EpisodeSemanticAction(
            SemanticConsolidationAction.INVALIDATE, "", "PREFERENCE", "invented-id", .99)))
        val owner = MemoryBrainCoordinator(store, provider, recoverOnInit = false)
        assertEquals(0, owner.consolidateEpisode(episode))
        assertTrue(store.semantic.single().active)
    }

    @Test fun episodeConsolidationRejectsNonUserAssertionsAndInventedCriticalNumbers() = runBlocking {
        suspend fun run(action: EpisodeSemanticAction): InMemoryAiriMemoryStore {
            val store = InMemoryAiriMemoryStore()
            val user = message(401, 10, "user", "I discussed a future travel idea")
            store.appendConversation(user)
            val span = EpisodeSpanEntity("guard-${action.assertionMode}-${action.fact.hashCode()}", "c", 401, 401,
                SegmentClassification.INFORMATIVE.name, "EOF", 11)
            store.saveEpisodeSpan(span)
            val episode = store.ensureEpisodeForSpan(span, listOf(user))!!
            val owner = MemoryBrainCoordinator(store, FakeReasoningProvider(actions = listOf(action)), recoverOnInit = false)
            assertEquals(0, owner.consolidateEpisode(episode))
            return store
        }
        assertTrue(run(EpisodeSemanticAction(SemanticConsolidationAction.NEW,
            "Speaker will move next year", "EXPERIENCE", null, .95, "HYPOTHETICAL")).semantic.isEmpty())
        assertTrue(run(EpisodeSemanticAction(SemanticConsolidationAction.NEW,
            "Speaker departs on 2047-09-11", "EXPERIENCE", null, .95)).semantic.isEmpty())
        assertTrue(run(EpisodeSemanticAction(SemanticConsolidationAction.NEW,
            "Speaker lives in Valencia", "IDENTITY", null, .95,
            criticalLiterals = listOf("Valencia"))).semantic.isEmpty())
    }

    @Test fun nearEquivalentNewActionReinforcesInsteadOfDuplicating() = runBlocking {
        val store = InMemoryAiriMemoryStore()
        val e = evidence(94, "I value accessible design", "I value accessible design")
        execute(MemoryBrainCoordinator(store, recoverOnInit = false), e,
            fact(MemorySemanticIntent.ADD_FACT, "Speaker values accessible design", "design:accessibility", e.displayText))
        val user = ConversationTruthEntity("dedup-user", "parity", 402, 95, "pc:95", "user",
            "I value accessible design", 12)
        store.appendConversation(user)
        val span = EpisodeSpanEntity("dedup-span", "parity", 402, 402,
            SegmentClassification.INFORMATIVE.name, "EOF", 13)
        store.saveEpisodeSpan(span); val episode = store.ensureEpisodeForSpan(span, listOf(user))!!
        val action = EpisodeSemanticAction(SemanticConsolidationAction.NEW,
            "Speaker values accessible design", "PREFERENCE", null, .95)
        val owner = MemoryBrainCoordinator(store, FakeReasoningProvider(actions = listOf(action)), recoverOnInit = false)
        assertEquals(1, owner.consolidateEpisode(episode))
        assertEquals(1, store.semantic.count { it.active })
        assertEquals(1, store.provenance.count { it.episodeId == episode })
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

private class FakeReasoningProvider(
    private val actions: List<EpisodeSemanticAction> = emptyList(),
    private val ratings: Map<String, EpisodeReviewRating> = emptyMap(),
    private val primitiveClassification: SegmentClassification? = SegmentClassification.INFORMATIVE,
    private val primitiveSplits: List<Long> = emptyList(),
    private val resegmentSplits: List<Long> = emptyList()
) : MemoryReasoningProvider {
    override suspend fun classifyPrimitive(messages: List<ConversationTruthEntity>) = primitiveClassification
    override suspend fun splitPrimitive(messages: List<ConversationTruthEntity>) = primitiveSplits
    override suspend fun resegmentInformative(messages: List<ConversationTruthEntity>, softBoundaries: List<Long>) = resegmentSplits
    override suspend fun reviewBoundaries(messages: List<ConversationTruthEntity>, candidates: List<SegmentBoundary>) =
        candidates.map { ReviewedBoundary(it.afterSequence, true, .9, "model-reviewed") }
    override suspend fun predict(title: String, facts: List<ConsolidationCandidate>) = "prediction"
    override suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>) = actions
    override suspend fun rateEpisodes(context: List<ConversationTruthEntity>, episodes: List<MemoryEntity>, queries: Map<String, List<String>>) = ratings
}
