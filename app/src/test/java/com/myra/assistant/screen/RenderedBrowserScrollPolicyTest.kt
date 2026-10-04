package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import org.junit.Assert.*
import org.junit.Test

class RenderedBrowserScrollPolicyTest {
    private fun element(text: String, index: Int = 0,
        role: SemanticRole = SemanticRole.TEXT, actionable: Boolean = false
    ) = SemanticElement("$index", role, text, 10, 30 + index * 40, 700,
        65 + index * 40, actionable)

    private fun context(
        timestamp: Long = 1_000L, window: Int = 7, generation: Long = 2,
        elements: List<SemanticElement> = listOf(
            element("Public project overview and documentation", 0),
            element("Read the complete changelog in the next section", 1),
        ),
    ) = CurrentActivityContext(packageName = "com.android.chrome",
        windowId = window, generation = generation, visibleElements = elements,
        confidence = .90, timestamp = timestamp)

    private fun browser(window: Int = 7, generation: Long = 2,
        pkg: String = "com.android.chrome") =
        ForegroundAppContext(pkg, windowId = window, generation = generation,
            observedAt = 1_000L)

    @Test fun explicitOnceOnlyUserScrollCanBePlanned() {
        val down = requireNotNull(RenderedBrowserScrollPolicy.plan(
            "Scroll this browser page down once", context(), browser(), 1_040L))
        assertTrue(down.down)
        val up = requireNotNull(RenderedBrowserScrollPolicy.plan(
            "Scroll the webpage up", context(), browser(), 1_040L))
        assertFalse(up.down)
        assertTrue(RenderedBrowserScrollPolicy.isBrowserPageScrollShaped(
            "Scroll browser page down forever", "com.android.chrome"))
        assertNull(RenderedBrowserScrollPolicy.plan(
            "Scroll browser page down forever", context(), browser(), 1_040L))
        assertNull(RenderedBrowserScrollPolicy.plan(
            "Scroll this browser page down and click login", context(), browser(), 1_040L))
        assertNull(RenderedBrowserScrollPolicy.plan(
            "Scroll down automatically", context(), browser(), 1_040L))
    }

    @Test fun staleChangedBrowserAndPrivatePageAreNeverEligible() {
        val command = "Scroll browser page down"
        assertNull(RenderedBrowserScrollPolicy.plan(
            command, context(timestamp = 10), browser(), 1_050L))
        assertNull(RenderedBrowserScrollPolicy.plan(
            command, context(window = 8), browser(), 1_050L))
        assertNull(RenderedBrowserScrollPolicy.plan(
            command, context(), browser(generation = 3), 1_050L))
        assertNull(RenderedBrowserScrollPolicy.plan(
            command, context(), browser(pkg = "com.bank"), 1_050L))
        assertNull(RenderedBrowserScrollPolicy.plan(
            command, context(elements = listOf(element("Enter password to continue"))),
            browser(), 1_050L))
        assertNull(RenderedBrowserScrollPolicy.plan(
            command, context(elements = listOf(element("Search or type web address",
                role = SemanticRole.TEXT_INPUT))), browser(), 1_050L))
    }

    @Test fun newStableVisibleTextAfterOneScrollIsOnlyBoundedEvidence() {
        val plan = requireNotNull(RenderedBrowserScrollPolicy.plan(
            "Scroll browser page down", context(), browser(), 1_050L))
        val first = context(timestamp = 1_750L, elements = listOf(
            element("New documentation section about offline caching", 0),
            element("Loading next section please wait", 1)))
        val second = context(timestamp = 2_200L, elements = listOf(
            element("New documentation section about offline caching", 0),
            element("Frequently asked questions about cache updates", 1)))
        assertEquals(RenderedBrowserScrollPolicy.Verification.NEW_STABLE_VISIBLE_TEXT_URL_UNVERIFIED,
            RenderedBrowserScrollPolicy.verify(plan, first, browser(), second,
                browser(), 1_500L, 2_250L))
        assertEquals(RenderedBrowserScrollPolicy.Verification.UNKNOWN,
            RenderedBrowserScrollPolicy.verify(plan, first, browser(),
                second.copy(visibleElements = listOf(element(
                    "A different new promotional banner appears here"))),
                browser(), 1_500L, 2_250L))
        assertEquals(RenderedBrowserScrollPolicy.Verification.UNKNOWN,
            RenderedBrowserScrollPolicy.verify(plan, first, browser(),
                second.copy(visibleElements = listOf(element(
                    "Loading next section please wait"))),
                browser(), 1_500L, 2_250L))
    }

    @Test fun noConfirmationAcrossChangedWindowStaleTimeOrPrivateSecondObservation() {
        val plan = requireNotNull(RenderedBrowserScrollPolicy.plan(
            "Scroll browser page down", context(), browser(), 1_050L))
        val first = context(timestamp = 1_750L, elements = listOf(
            element("New documentation section about offline caching")))
        val second = context(timestamp = 2_200L, elements = first.visibleElements)
        val unknown = RenderedBrowserScrollPolicy.Verification.UNKNOWN
        assertEquals(unknown, RenderedBrowserScrollPolicy.verify(plan, first,
            browser(), second.copy(windowId = 8), browser(window = 8), 1_500L, 2_250L))
        assertEquals(unknown, RenderedBrowserScrollPolicy.verify(plan, first,
            browser(), second, browser(generation = 3), 1_500L, 2_250L))
        assertEquals(unknown, RenderedBrowserScrollPolicy.verify(plan, first,
            browser(), second.copy(timestamp = 1_820L), browser(), 1_500L, 2_250L))
        assertEquals(unknown, RenderedBrowserScrollPolicy.verify(plan, first,
            browser(), second.copy(timestamp = 5_000L), browser(), 1_500L, 5_100L))
        assertEquals(unknown, RenderedBrowserScrollPolicy.verify(plan, first,
            browser(), second.copy(visibleElements = second.visibleElements +
                element("Enter OTP 123456", 2)), browser(), 1_500L, 2_250L))
    }

    @Test fun cosmeticChangedButtonsCannotBePresentedAsReadPageContent() {
        val plan = requireNotNull(RenderedBrowserScrollPolicy.plan(
            "Scroll browser page down", context(), browser(), 1_050L))
        val action = context(timestamp = 1_750L, elements = listOf(
            element("Another visible new button changes position", 0,
                SemanticRole.BUTTON, actionable = true)))
        val second = action.copy(timestamp = 2_200L)
        assertEquals(RenderedBrowserScrollPolicy.Verification.UNKNOWN,
            RenderedBrowserScrollPolicy.verify(plan, action, browser(), second,
                browser(), 1_500L, 2_250L))
    }
}
