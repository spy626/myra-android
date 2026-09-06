package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSceneAwarenessTest {
    @After fun reset() {
        ScreenSceneAwarenessStore.reset()
        AccessibilityVisualCache.invalidate()
    }

    @Test fun `same element moving right to left creates movement delta`() {
        ScreenSceneAwarenessStore.publish(context(element("x", 800, 500, 900, 600), 100))
        ScreenSceneAwarenessStore.publish(context(element("x", 100, 500, 200, 600), 200))

        val moved = ScreenSceneAwarenessStore.lastDelta()!!.changes.single {
            it.type == SceneDeltaType.ELEMENT_MOVED
        }
        assertEquals(ScenePosition.RIGHT, moved.fromHorizontal)
        assertEquals(ScenePosition.LEFT, moved.toHorizontal)
    }

    @Test fun `appeared and disappeared elements are reported`() {
        ScreenSceneAwarenessStore.publish(context(element("old", 10, 10, 100, 100), 100))
        ScreenSceneAwarenessStore.publish(context(element("new", 10, 10, 100, 100), 200))
        val changes = ScreenSceneAwarenessStore.lastDelta()!!.changes.map { it.type }.toSet()
        assertTrue(SceneDeltaType.ELEMENT_APPEARED in changes)
        assertTrue(SceneDeltaType.ELEMENT_DISAPPEARED in changes)
    }

    @Test fun `dialog and scroll advance current scene evidence`() {
        ScreenSceneAwarenessStore.publish(context(element("x", 10, 10, 100, 100), 100), dialogVisible = false)
        ScreenSceneAwarenessStore.markMutation("accessibility_scroll")
        ScreenSceneAwarenessStore.publish(
            context(element("x", 10, 10, 100, 100), 200), dialogVisible = true, scrollObservedAt = 200
        )
        val changes = ScreenSceneAwarenessStore.lastDelta()!!.changes.map { it.type }.toSet()
        assertTrue(SceneDeltaType.DIALOG_APPEARED in changes)
        assertTrue(SceneDeltaType.SCREEN_SCROLLED in changes)
    }

    @Test fun `visual frame older than scene mutation is stale`() {
        val frame = AccessibilityScreenshot(byteArrayOf(1), 10, 10, 100, "pkg", 1, 1)
        AccessibilityVisualCache.put(frame, "same", sceneRevision = 1)
        assertTrue(AccessibilityVisualCache.fresh("pkg", 1, 1, "same", 200, 500, currentSceneRevision = 1) != null)
        assertTrue(AccessibilityVisualCache.fresh("pkg", 1, 1, "same", 200, 500, currentSceneRevision = 2) == null)
    }

    @Test fun `current screen followups are screen questions without phrase-specific object`() {
        assertTrue(ScreenStateFollowUpClassifier.isCurrentScreenFollowUp("Abhi kahan hai?"))
        assertTrue(ScreenStateFollowUpClassifier.isCurrentScreenFollowUp("Ab kya change hua?"))
        assertTrue(ScreenStateFollowUpClassifier.isCurrentScreenFollowUp("Is it still visible now?"))
        assertFalse(ScreenStateFollowUpClassifier.isCurrentScreenFollowUp("Kal kahan jana hai?"))
    }

    @Test fun `external accessibility overlay is permanently disabled`() {
        assertFalse(ExternalScreenVisionOverlayPolicy.CREATE_ACCESSIBILITY_OVERLAY)
    }

    private fun context(element: SemanticElement, at: Long) = CurrentActivityContext(
        packageName = "pkg", windowId = 1, generation = 1, visibleElements = listOf(element),
        confidence = .9, timestamp = at
    )

    private fun element(id: String, left: Int, top: Int, right: Int, bottom: Int) = SemanticElement(
        id, SemanticRole.BUTTON, id, left, top, right, bottom, actionable = true
    )
}

