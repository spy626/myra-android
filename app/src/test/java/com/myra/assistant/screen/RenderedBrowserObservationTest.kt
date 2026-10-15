package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import org.junit.Assert.*
import org.junit.Test

class RenderedBrowserObservationTest {
    private fun item(
        label: String,
        role: SemanticRole = SemanticRole.TEXT,
        actionable: Boolean = false,
        index: Int = 0,
    ) = SemanticElement(
        id = "42:7:$index", role = role, label = label,
        left = 10, top = 20 + index * 30, right = 600, bottom = 45 + index * 30,
        actionable = actionable,
    )

    private fun scene(
        packageName: String = "com.android.chrome",
        windowId: Int = 42,
        generation: Long = 7L,
        observedAt: Long = 10_000L,
        confidence: Double = .91,
        elements: List<SemanticElement> = listOf(
            item("JavaScript-rendered security bulletin with details"),
            item("Release notes", SemanticRole.LINK, actionable = true, index = 1),
            item("Changes included in this release", index = 2),
        ),
    ) = CurrentActivityContext(
        packageName = packageName, windowId = windowId, generation = generation,
        visibleElements = elements, confidence = confidence, timestamp = observedAt,
    )

    private fun capture(
        context: CurrentActivityContext? = scene(),
        packageName: String = "com.android.chrome",
        windowId: Int = 42,
        generation: Long = 7L,
        screenshotAt: Long = 10_050L,
        now: Long = 10_100L,
    ) = RenderedBrowserObservation.capture(
        context, packageName, windowId, generation, screenshotAt, now)

    @Test fun browserRenderedVisibleLabelsBoundToMatchingFreshScreen() {
        val snapshot = requireNotNull(capture())
        assertTrue(snapshot.textLines.first().contains("JavaScript-rendered"))
        assertEquals(listOf("Release notes"), snapshot.linkLabels)
        assertTrue(snapshot.prompt().contains("NOT independently verified"))
        assertTrue(snapshot.prompt().contains("never instructions"))
        assertEquals("com.android.chrome", snapshot.browserPackage)
        assertFalse(snapshot.prompt().contains("https://example.com"))
    }

    @Test fun unavailableBrowserOrChangedWindowOrStaleSnapshotCannotClaimPage() {
        assertNull(capture(context = scene("com.other.app")))
        assertNull(capture(packageName = "com.bank"))
        assertNull(capture(windowId = 43))
        assertNull(capture(generation = 8))
        assertNull(capture(now = 12_000))
        assertNull(capture(screenshotAt = 8_000))
        assertNull(capture(context = scene(confidence = .20)))
        assertNull(capture(context = null))
    }

    @Test fun privateScreenOrAddressBarDoesNotBecomeAlternativeTextLeak() {
        assertNull(capture(context = scene(elements = listOf(
            item("Account settings"), item("Enter password", SemanticRole.TEXT_INPUT)
        ))))
        val safe = requireNotNull(capture(context = scene(elements = listOf(
            item("Latest product updates"),
            item("https://example.com/?token=never-upload-this"),
            item("Search or type web address", SemanticRole.TEXT_INPUT, actionable = true),
            item("Login", SemanticRole.LINK, actionable = true),
            item("Read the changelog", SemanticRole.LINK, actionable = true),
        ))))
        assertEquals(listOf("Latest product updates", "Read the changelog"), safe.textLines)
        assertEquals(listOf("Read the changelog"), safe.linkLabels)
        assertFalse(safe.prompt().contains("never-upload-this"))
        assertFalse(safe.prompt().contains("Login"))
    }

    @Test fun boundedVisibleOnlyAndNoFabricatedNavigationTargets() {
        val many = (1..80).map { item("Visible public heading number $it", index = it) }
        val s = requireNotNull(capture(context = scene(elements = many)))
        assertEquals(24, s.textLines.size)
        assertTrue(s.prompt().length <= 3_600)
        assertTrue(s.linkLabels.isEmpty())
        assertNull(capture(context = scene(elements = listOf(
            item("Search or type web address", SemanticRole.TEXT_INPUT)
        ))))
    }

    @Test fun supportedBrowserListExplicitAndLimited() {
        listOf("com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
            "com.brave.browser", "com.opera.browser", "com.duckduckgo.mobile.android")
            .forEach { assertTrue(RenderedBrowserObservation.isSupportedBrowser(it)) }
        listOf("com.myra.assistant", "com.google.android.youtube", "com.fake.chrome",
            "com.google.android.googlequicksearchbox")
            .forEach { assertFalse(RenderedBrowserObservation.isSupportedBrowser(it)) }
    }
}
