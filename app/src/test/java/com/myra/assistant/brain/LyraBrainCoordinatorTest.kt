package com.myra.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyraBrainCoordinatorTest {
    @Test fun `classifies reading and reference actions separately`() {
        assertEquals(BrainIntent.READING_REQUEST, LyraBrainCoordinator.classify("Read this article"))
        assertEquals(BrainIntent.SCREEN_REFERENCE_ACTION, LyraBrainCoordinator.classify("Open it"))
        assertEquals(BrainIntent.CONVERSATION, LyraBrainCoordinator.classify("What is 10 plus 5"))
    }
    @Test fun classifiesEnglishHindiAndHinglishIntents() {
        assertEquals(BrainIntent.PHONE_ACTION, LyraBrainCoordinator.classify("Open YouTube"))
        assertEquals(BrainIntent.SCREEN_ANALYSIS, LyraBrainCoordinator.classify("What's on my screen?"))
        assertEquals(BrainIntent.MEMORY, LyraBrainCoordinator.classify("Remember that I like this"))
        assertEquals(BrainIntent.SCREEN_ACTION, LyraBrainCoordinator.classify("Jo beech mein hai usko open karo"))
        assertEquals(BrainIntent.CANCELLATION, LyraBrainCoordinator.classify("No, stop"))
    }

    @Test fun plansScrollThenSecondVideoAsOneCancellableTask() {
        val brain = LyraBrainCoordinator()
        val decision = brain.interpret("Scroll down and open the second video")
            as BrainDecision.ScrollThenOpenVideo

        assertEquals(ScrollDirection.DOWN, decision.direction)
        assertEquals(2, decision.ordinal)
        assertTrue(brain.isTaskCurrent(decision.taskToken))
    }

    @Test fun cancellationInvalidatesPendingMultiStepTask() {
        val brain = LyraBrainCoordinator()
        val plan = brain.interpret("Scroll down and open the second video")
            as BrainDecision.ScrollThenOpenVideo
        val cancellation = brain.interpret("Never mind") as BrainDecision.Cancel

        assertFalse(brain.isTaskCurrent(plan.taskToken))
        assertTrue(brain.isTaskCurrent(cancellation.taskToken))
    }

    @Test fun otherOneUsesPreviousOrderedTarget() {
        val brain = LyraBrainCoordinator()
        brain.resolveScreenTarget(targetText = null, position = "center", ordinal = 1)
        brain.recordScreenAction(ScreenTargetReference(position = "center", ordinal = 1), true)

        val decision = brain.interpret("Nahi, doosra wala") as BrainDecision.ScreenAction
        assertEquals("center", decision.target.position)
        assertEquals(2, decision.target.ordinal)
        assertTrue(decision.contextual)
    }

    @Test fun ambiguousOtherReferenceAsksInsteadOfGuessing() {
        val brain = LyraBrainCoordinator()
        assertTrue(brain.interpret("Doosra wala") is BrainDecision.Clarify)
    }

    @Test fun repeatedReferenceReusesKnownTargetOnly() {
        val brain = LyraBrainCoordinator()
        brain.resolveScreenTarget("AI agents", null, 1)
        val repeat = brain.interpret("Do that again") as BrainDecision.ScreenAction
        assertEquals("AI agents", repeat.target.targetText)
    }

    @Test fun relativeCorrectionUpdatesPreviousTarget() {
        val brain = LyraBrainCoordinator()
        brain.resolveScreenTarget(targetText = "video", position = "center", ordinal = null)
        brain.recordScreenAction(ScreenTargetReference(targetText = "video", position = "center"), true)

        val correction = brain.interpret("Nahi, upar wala") as BrainDecision.ScreenAction

        assertEquals("video", correction.target.targetText)
        assertEquals("top", correction.target.position)
        assertEquals(null, correction.target.ordinal)
    }

    @Test fun `new explicit action never falls back to old screen target`() {
        val brain = LyraBrainCoordinator()
        brain.resolveScreenTarget("Old center video", "center", null)
        brain.recordScreenAction(ScreenTargetReference("Old center video", "center"), true)

        assertEquals(null, brain.resolveScreenTarget(null, null, null))
        assertEquals(
            "Miss Interwala video",
            brain.resolveScreenTarget("Miss Interwala video", null, null)?.targetText
        )
    }

    @Test
    fun `ambiguous click reference without current target asks instead of guessing`() {
        val brain = LyraBrainCoordinator()
        val decision = brain.interpret("click that one")
        assertTrue(decision is BrainDecision.Clarify)
    }

    @Test
    fun `contextual target retains foreground ownership metadata`() {
        val brain = LyraBrainCoordinator()
        brain.recordScreenAction(
            ScreenTargetReference(
                targetText = "second video",
                ordinal = 2,
                appPackage = "com.google.android.youtube",
                activeWindowId = 12,
                screenContextGeneration = 4L
            ),
            success = true
        )
        val decision = brain.interpret("open it") as BrainDecision.ScreenAction
        assertEquals("com.google.android.youtube", decision.target.appPackage)
        assertEquals(12, decision.target.activeWindowId)
        assertEquals(4L, decision.target.screenContextGeneration)
    }

    @Test
    fun `live accessibility package propagates into current app state`() {
        val brain = LyraBrainCoordinator()
        brain.observeForegroundApp("com.google.android.youtube")
        assertEquals("com.google.android.youtube", brain.snapshot().currentApp)
    }

    @Test
    fun `second video is a deterministic accessibility screen action`() {
        val brain = LyraBrainCoordinator()
        brain.observeForegroundApp("com.google.android.youtube")
        val decision = brain.interpret("second video open karo")
        assertTrue(decision is BrainDecision.ScreenAction)
        decision as BrainDecision.ScreenAction
        assertEquals(2, decision.target.ordinal)
        assertEquals("video", decision.target.targetText)
        assertFalse(decision.contextual)
    }

    @Test
    fun `ordinal video action does not require screen vision classification`() {
        assertEquals(
            BrainIntent.SCREEN_ACTION,
            LyraBrainCoordinator.classify("open the second video")
        )
    }
}
