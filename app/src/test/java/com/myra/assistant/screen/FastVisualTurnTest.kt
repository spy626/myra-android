package com.myra.assistant.screen

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
        assertNull(AccessibilityVisualCache.fresh("pkg", 4, 5, "changed", 1_500, 900))
        assertNull(AccessibilityVisualCache.fresh("other", 4, 5, "same", 1_500, 900))
        assertNull(AccessibilityVisualCache.fresh("pkg", 4, 5, "same", 2_000, 900))
        AccessibilityVisualCache.invalidate()
    }
}
