package com.myra.assistant.screen

import com.myra.assistant.agent.TurnIntent
import com.myra.assistant.agent.UnifiedTurnInterpreter
import org.junit.Assert.*
import org.junit.Test

class FastVisualTurnTest {
    @Test fun naturalScreenQuestionsOwnFastVisualPath() {
        assertEquals(FastVisualKind.QUESTION, FastVisualRequestClassifier.classify("Abhi kya dikh raha hai?")?.kind)
        assertEquals(FastVisualKind.QUESTION, FastVisualRequestClassifier.classify("Ye error kya bol raha hai?")?.kind)
        assertEquals(FastVisualKind.QUESTION, FastVisualRequestClassifier.classify("Ye kya hai?")?.kind)
        assertEquals(FastVisualKind.QUESTION, FastVisualRequestClassifier.classify("स्क्रीन पर क्या दिख रहा है?")?.kind)
        // Real-phone ASR variant from the supplied diagnostic log.
        assertEquals(FastVisualKind.QUESTION, FastVisualRequestClassifier.classify("Admi kya bikra?")?.kind)
        assertEquals(TurnIntent.SCREEN_QUESTION, UnifiedTurnInterpreter.interpret("Abhi kya dikh raha hai?", null).intent)
        assertEquals(TurnIntent.SCREEN_QUESTION, UnifiedTurnInterpreter.interpret("What do you see?", null).intent)
    }

    @Test fun naturalVisualActionsDoNotNeedExactSentenceParser() {
        assertEquals(FastVisualKind.ACTION, FastVisualRequestClassifier.classify("Jo thumbs jaisa icon hai usko press karo")?.kind)
        assertEquals(FastVisualKind.ACTION, FastVisualRequestClassifier.classify("Uploader ko subscribe kar do")?.kind)
        assertNull(FastVisualRequestClassifier.classify("Tell me a joke"))
    }

    @Test fun oneTurnOwnsVisualResponseAndNewTurnReplacesIt() {
        val coordinator = FastVisualTurnCoordinator()
        val first = coordinator.begin(1, FastVisualRequest(FastVisualKind.QUESTION, "screen"), "a", 2, 3, 10, 11)
        assertTrue(coordinator.owns(first.id))
        val second = coordinator.begin(2, FastVisualRequest(FastVisualKind.ACTION, "thumb"), "a", 2, 3, 20, 21)
        assertFalse(coordinator.owns(first.id))
        assertTrue(coordinator.owns(second.id))
        assertSame(second, coordinator.finish(second.id))
        assertNull(coordinator.current())
    }

    @Test fun visualCacheRequiresFreshMatchingUnchangedContext() {
        val frame = AccessibilityScreenshot(byteArrayOf(1, 2), 10, 20, 1_000, "pkg", 4, 5)
        AccessibilityVisualCache.put(frame, "same")
        assertSame(frame, AccessibilityVisualCache.fresh("pkg", 4, 5, "same", 1_500, 900))
        assertEquals(
            VisualFrameSource.ACCESSIBILITY_CACHE,
            AccessibilityVisualCache.selectFresh("pkg", 4, 5, "same", 1_500, 900)?.source
        )
        assertNull(AccessibilityVisualCache.fresh("pkg", 4, 5, "changed", 1_500, 900))
        assertNull(AccessibilityVisualCache.fresh("other", 4, 5, "same", 1_500, 900))
        assertNull(AccessibilityVisualCache.fresh("pkg", 4, 5, "same", 2_000, 900))
        AccessibilityVisualCache.invalidate()
    }

    @Test fun screenshotRequestCompletesExactlyOnceAndRejectsLateCallback() {
        assertEquals(1_200L, VisualScreenshotTimeoutPolicy.TIMEOUT_MS)
        val gate = VisualCaptureCompletionGate()
        assertTrue(gate.tryComplete())
        assertFalse(gate.tryComplete())
    }

    @Test fun outerDeadlineStartsAtVisualFrameRequestedAndRejectsDelayedDispatch() {
        val gate = VisualAcquisitionGate("turn-a", requestedAt = 1_000, deadlineAt = 2_200)
        assertTrue(gate.mayDispatch("turn-a", 1_100))
        assertTrue(gate.tryTimeout(2_200))
        assertFalse(gate.mayDispatch("turn-a", 2_201))
        assertFalse(gate.tryComplete("turn-a", 2_201))
    }

    @Test fun successfulPlatformCallbackDoesNotCompleteOuterDeadlineBeforeFrameDelivery() {
        val gate = VisualAcquisitionGate("turn-a", requestedAt = 1_000, deadlineAt = 2_200)
        assertTrue(gate.onPlatformCallback("turn-a", 1_080))
        assertFalse(gate.isComplete())
        assertTrue(gate.tryTimeout(2_200))
        assertFalse(gate.tryComplete("turn-a", 2_201))
    }

    @Test fun usableFrameDeliveryCompletesAndCancelsTimeoutOwnership() {
        val gate = VisualAcquisitionGate("turn-a", requestedAt = 1_000, deadlineAt = 2_200)
        assertTrue(gate.onPlatformCallback("turn-a", 1_080))
        assertTrue(gate.tryComplete("turn-a", 1_160))
        assertFalse(gate.tryTimeout(2_200))
    }

    @Test fun replacedVisualTurnCannotInvokeQueuedCapture() {
        val gate = VisualAcquisitionGate("turn-a", requestedAt = 1_000, deadlineAt = 2_200)
        assertFalse(gate.mayDispatch("turn-b", 1_100))
        assertFalse(gate.tryComplete("turn-b", 1_100))
    }

    @Test fun semanticScreenFallbackRequiresFreshExactlyOwnedScene() {
        assertTrue(SemanticScreenFallbackPolicy.mayAnswer("pkg", 4, 5, "pkg", 4, 5, 3, 400))
        assertFalse(SemanticScreenFallbackPolicy.mayAnswer("pkg", 4, 5, "other", 4, 5, 3, 400))
        assertFalse(SemanticScreenFallbackPolicy.mayAnswer("pkg", 4, 5, "pkg", 4, 5, 0, 400))
        assertFalse(SemanticScreenFallbackPolicy.mayAnswer("pkg", 4, 5, "pkg", 4, 5, 3, 2_501))
    }

    @Test fun replacedVisualTurnCannotOwnLateScreenshotResult() {
        val coordinator = FastVisualTurnCoordinator()
        val old = coordinator.begin(1, FastVisualRequest(FastVisualKind.QUESTION, "screen"), "pkg", 2, 3, 10, 11)
        coordinator.begin(2, FastVisualRequest(FastVisualKind.QUESTION, "screen"), "pkg", 2, 3, 20, 21)
        assertFalse(coordinator.owns(old.id))
    }

    @Test fun armedScreenQuestionExpiresInsteadOfDispatchingOnLaterSpeechEdge() {
        assertTrue(ArmedScreenQuestionPolicy.isFresh(1_000, 3_500))
        assertFalse(ArmedScreenQuestionPolicy.isFresh(1_000, 3_501))
        assertFalse(ArmedScreenQuestionPolicy.isFresh(0, 1_000))
    }

    @Test fun stableReadOnlyScreenQuestionCanAuthorizeAtLocalSpeechEnd() {
        assertTrue(EarlyScreenQuestionPolicy.mayAuthorizeAtSpeechEnd("What do you see?", speechActive = false))
        assertTrue(EarlyScreenQuestionPolicy.mayAuthorizeAtSpeechEnd("What can you see now?", speechActive = false))
        assertTrue(EarlyScreenQuestionPolicy.mayAuthorizeAtSpeechEnd("Ab kya dikh raha hai?", speechActive = false))
        assertFalse(EarlyScreenQuestionPolicy.mayAuthorizeAtSpeechEnd("click this button", speechActive = false))
        assertFalse(EarlyScreenQuestionPolicy.mayAuthorizeAtSpeechEnd("What do you see?", speechActive = true))
    }

    @Test fun matchingFinalTranscriptReusesEarlyVisualTurnButChangedActionCancels() {
        assertEquals(
            ScreenQuestionReconciliation.MATCH,
            EarlyScreenQuestionPolicy.reconcile("What do you see?", "What do you see on screen?")
        )
        assertEquals(
            ScreenQuestionReconciliation.MATERIAL_CHANGE,
            EarlyScreenQuestionPolicy.reconcile("What do you see?", "click the second item")
        )
    }
}
