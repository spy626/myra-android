package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastActionAuthorizationTest {
    @Test fun stableScrollAuthorizesOnlyAfterSpeechEnds() {
        val candidate = scrollCandidate()
        assertEquals(FastAuthorizationDecision.WAIT_FOR_FINAL, decide(candidate, speechEnded = false).decision)
        assertEquals(FastAuthorizationDecision.FAST_AUTHORIZE, decide(candidate, speechEnded = true).decision)
    }

    @Test fun conversationContainingScrollIsRejectedByUnifiedSemantics() {
        val text = "Main soch raha hun scroll feature aur fast hona chahiye"
        val candidate = scrollCandidate(text = text)
        val semantic = UnifiedTurnInterpreter.interpret(text, null)
        assertEquals(TurnIntent.CONVERSATION, semantic.intent)
        assertEquals(
            FastAuthorizationDecision.REJECT,
            FastActionAuthorizationPolicy.decide(candidate, 1_300, true, 7, semantic, "pkg", 1, false, false).decision
        )
    }

    @Test fun conflictingCapabilitiesWaitForFinal() {
        val store = FastActionCandidateStore()
        store.stage(scrollCandidate())
        val search = searchCandidate().copy(turnId = 7)
        val conflicted = store.stage(search)
        assertTrue(conflicted.conflicting)
        assertEquals(FastAuthorizationDecision.WAIT_FOR_FINAL, decide(conflicted, semantic = UnifiedTurnInterpreter.interpret(search.semanticText, null)).decision)
    }

    @Test fun differentTurnAndProtectedModalCannotFastAuthorize() {
        val candidate = scrollCandidate()
        val semantic = UnifiedTurnInterpreter.interpret(candidate.semanticText, null)
        assertEquals(
            FastAuthorizationDecision.REJECT,
            FastActionAuthorizationPolicy.decide(candidate, 1_300, true, 8, semantic, "pkg", 1, false, false).decision
        )
        assertEquals(
            FastAuthorizationDecision.REJECT,
            FastActionAuthorizationPolicy.decide(candidate, 1_300, true, 7, semantic, "pkg", 1, true, false).decision
        )
    }

    @Test fun searchQueryMustBeMeaningfulAndStable() {
        val incomplete = searchCandidate(text = "Search karo", query = "")
        assertEquals(FastAuthorizationDecision.WAIT_FOR_FINAL, decide(incomplete, semantic = UnifiedTurnInterpreter.interpret(incomplete.semanticText, null)).decision)
        val complete = searchCandidate()
        assertEquals(FastAuthorizationDecision.FAST_AUTHORIZE, decide(complete, semantic = UnifiedTurnInterpreter.interpret(complete.semanticText, null)).decision)
    }

    @Test fun evolvingSearchQueryRestartsStabilityWithoutCreatingConflict() {
        val store = FastActionCandidateStore()
        store.stage(searchCandidate(text = "Search karo new AI", query = "new AI"))
        val evolved = store.stage(searchCandidate(text = "Search karo new AI in UAE", query = "new AI in UAE").copy(updatedAt = 1_100))
        assertFalse(evolved.conflicting)
        assertEquals(1_100L, evolved.firstStableAt)
        assertEquals(
            FastAuthorizationDecision.WAIT_FOR_FINAL,
            decide(evolved, now = 1_150, semantic = UnifiedTurnInterpreter.interpret(evolved.semanticText, null)).decision
        )
    }

    @Test fun committedTurnCannotAuthorizeOrDispatchAgain() {
        assertEquals(FastAuthorizationDecision.CANCELLED, decide(scrollCandidate(), alreadyCommitted = true).decision)
    }

    @Test fun productionObservationDelaysAreBoundedForFastCapabilities() {
        val registry = AgentToolRegistry()
        val adapters = ProductionGeneralAdapters.create(registry, ProductionAdapterExecutors(
            scroll = { _, _ -> GeneralActionResult(true) },
            browserSearch = { _, _ -> GeneralActionResult(true) },
            observeScreen = { _, _ -> GeneralActionResult(true) },
            verifyScreen = { _, _ -> GeneralActionResult(true) },
            back = { _, _ -> GeneralActionResult(true) }
        ))
        assertTrue(adapters.single { it.adapterId == "GenericScrollAdapter" }.observationDelayMs <= 350)
        assertTrue(adapters.single { it.adapterId == "BrowserSearchAdapter" }.observationDelayMs <= 350)
    }

    private fun decide(
        candidate: FastActionCandidate,
        now: Long = 1_300,
        speechEnded: Boolean = true,
        alreadyCommitted: Boolean = false,
        semantic: AgentTurnDecision = UnifiedTurnInterpreter.interpret(candidate.semanticText, null)
    ) = FastActionAuthorizationPolicy.decide(
        candidate, now, speechEnded, candidate.turnId, semantic, "pkg", 1,
        protectedModal = false, alreadyCommitted = alreadyCommitted
    )

    private fun scrollCandidate(text: String = "Niche jao") = FastActionCandidate(
        7, ToolCapability.ACCESSIBILITY_SCROLL, mapOf("direction" to "DOWN"), text,
        "partial_transcript", .97, "pkg", 1, 1, 1_000, 1_000
    )

    private fun searchCandidate(text: String = "Search karo new AI", query: String = "new AI") = FastActionCandidate(
        9, ToolCapability.BROWSER_SEARCH, mapOf("query" to query), text,
        "partial_transcript", .97, "pkg", 1, 1, 1_000, 1_000
    )
}
