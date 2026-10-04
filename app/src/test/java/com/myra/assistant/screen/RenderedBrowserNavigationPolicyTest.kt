package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import org.junit.Assert.*
import org.junit.Test

class RenderedBrowserNavigationPolicyTest {
    private fun el(label: String, actionable: Boolean = false,
        role: SemanticRole = SemanticRole.TEXT, index: Int = 0) = SemanticElement(
        id = "9:3:$index", label = label, role = role, actionable = actionable,
        left = 20, top = 20 + index * 50, right = 400, bottom = 60 + index * 50)

    private fun foreground(pkg: String = "com.android.chrome", w: Int = 9, g: Long = 3L) =
        ForegroundAppContext(pkg, windowId = w, generation = g, observedAt = 1_000L)

    private fun scene(pkg: String = "com.android.chrome", w: Int = 9, g: Long = 3L,
        timestamp: Long = 1_000L, elements: List<SemanticElement> = listOf(
            el("Welcome to the project guide", index = 0),
            el("Release notes", true, SemanticRole.BUTTON, 1),
            el("Project overview and introduction", index = 2))
    ) = CurrentActivityContext(pkg, windowId = w, generation = g, visibleElements = elements,
        confidence = .90, timestamp = timestamp)

    @Test fun oneExplicitNamedVisibleLinkCanBePlannedButNotInventedByModel() {
        val p = requireNotNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(), foreground(), 1_050L))
        assertEquals("Release notes", p.label)
        assertEquals("com.android.chrome", p.packageName)
        assertTrue(RenderedBrowserNavigationPolicy.isLinkShapedCommand(
            "Click the Release notes link", "com.android.chrome"))
        assertFalse(RenderedBrowserNavigationPolicy.isLinkShapedCommand(
            "I wonder if I should click a link", "com.android.chrome"))
        assertNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Security updates link", scene(), foreground(), 1_050L))
    }

    @Test fun modelCannotChooseUnsafeDestinationOrBypassNegativeTurn() {
        val linkPage = scene(elements = listOf(
            el("Delete account", true, SemanticRole.BUTTON),
            el("Release notes", true, SemanticRole.BUTTON, 1)))
        listOf("Click the Delete account link", "Tap the login link", 
            "Click the Release notes link and download the file",
            "Click the Release notes link but don't open it",
            "Open https://example.com link").forEach {
            assertNull(it, RenderedBrowserNavigationPolicy.plan(
                it, linkPage, foreground(), 1_050L))
        }
        assertTrue(RenderedBrowserNavigationPolicy.isLinkShapedCommand(
            "Click the Delete account link", "com.android.chrome"))
        assertFalse(RenderedBrowserNavigationPolicy.isLinkShapedCommand(
            "Click the Release notes link", "com.other.app"))
    }

    @Test fun ambiguousStaleCrossWindowOrPrivateScreenFailsClosed() {
        assertNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link",
            scene(elements = listOf(
                el("Release notes", true, SemanticRole.BUTTON),
                el("Release notes", true, SemanticRole.BUTTON, 1))),
            foreground(), 1_050L))
        assertNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(w = 10), foreground(), 1_050L))
        assertNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(), foreground(g = 4), 1_050L))
        assertNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(timestamp = 100), foreground(), 1_050L))
        assertNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(elements = listOf(
                el("Release notes", true, SemanticRole.BUTTON),
                el("Enter password", role = SemanticRole.TEXT_INPUT, index = 1))),
            foreground(), 1_050L))
    }

    @Test fun resolvedClickMustHaveExactlyTheObservedLabelAndSafeGeometry() {
        val p = requireNotNull(RenderedBrowserNavigationPolicy.plan(
            "Tap the Release notes link", scene(), foreground(), 1_050L))
        fun allowed(label: String, role: String = "interactive", confidence: Double = 1.0,
            width: Int = 240, height: Int = 50) =
            RenderedBrowserNavigationPolicy.allowsResolvedTarget(
                p, label, role, confidence, width, height, 1080, 2400)
        assertTrue(allowed("Release notes"))
        assertFalse(allowed("Release notes and buy now"))
        assertFalse(allowed("Release notes", confidence = .70))
        assertFalse(allowed("Release notes", role = "search_field"))
        assertFalse(allowed("Release notes", width = 1050))
        assertFalse(allowed("Release notes", height = 2000))
    }

    @Test fun postActionContentMustBeNewFreshMatchingBrowserNotJustTapAccepted() {
        val p = requireNotNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(), foreground(), 1_050L))
        val next = scene(timestamp = 1_800L, elements = listOf(
            el("Release notes", index = 0),
            el("Version 3 introduces security improvements and bug fixes", index = 1)))
        assertEquals(RenderedBrowserNavigationPolicy.Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED,
            RenderedBrowserNavigationPolicy.verify(p, next, foreground(), 1_500L, 1_900L))
        assertEquals(RenderedBrowserNavigationPolicy.Verification.UNKNOWN,
            RenderedBrowserNavigationPolicy.verify(p, next, foreground(w = 10), 1_500L, 1_900L))
        assertEquals(RenderedBrowserNavigationPolicy.Verification.UNKNOWN,
            RenderedBrowserNavigationPolicy.verify(p, scene(timestamp = 1_800L),
                foreground(), 1_500L, 1_900L))
        assertEquals(RenderedBrowserNavigationPolicy.Verification.UNKNOWN,
            RenderedBrowserNavigationPolicy.verify(p, next.copy(timestamp = 1_400L),
                foreground(), 1_500L, 1_900L))
        assertEquals(RenderedBrowserNavigationPolicy.Verification.UNKNOWN,
            RenderedBrowserNavigationPolicy.verify(p, next.copy(packageName = "com.other.app"),
                foreground(), 1_500L, 1_900L))
    }

    @Test fun twoStableNewRenderedContentObservationsAllowOnlyBoundedChangeClaim() {
        val plan = requireNotNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(), foreground(), 1_050L))
        val first = scene(timestamp = 1_800L, elements = listOf(
            el("Release notes"),
            el("Version 3 introduces security improvements and bug fixes", index = 1),
            el("Loading content", index = 2)))
        val second = scene(timestamp = 2_260L, elements = listOf(
            el("Version 3 introduces security improvements and bug fixes", index = 0),
            el("Here are the public product changes in this update", index = 1)))
        val expected = RenderedBrowserNavigationPolicy.Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED
        assertEquals(expected, RenderedBrowserNavigationPolicy.verifyStable(
            plan, first, foreground(), second, foreground(), 1_500L, 2_300L))
    }

    @Test fun oneTransientOrTwoDifferentChangesCannotProveNavigation() {
        val plan = requireNotNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(), foreground(), 1_050L))
        fun post(at: Long, label: String) = scene(timestamp = at, elements = listOf(
            el("Release notes", index = 0), el(label, index = 1)))
        val first = post(1_800L, "Version 3 introduces security improvements and bug fixes")
        val stable = post(2_260L, "Version 3 introduces security improvements and bug fixes")
        fun verify(second: CurrentActivityContext?) =
            RenderedBrowserNavigationPolicy.verifyStable(
                plan, first, foreground(), second, foreground(), 1_500L, 2_300L)
        val unknown = RenderedBrowserNavigationPolicy.Verification.UNKNOWN
        assertEquals(unknown, verify(post(2_260L, "Just another unrelated banner is displayed now")))
        assertEquals(unknown, verify(post(2_260L, "Loading content please wait while redirecting")))
        assertEquals(unknown, verify(post(1_850L, "Version 3 introduces security improvements and bug fixes")))
        assertEquals(unknown, verify(stable.copy(windowId = 10)))
        assertEquals(unknown, verify(stable.copy(generation = 4)))
        assertEquals(unknown, verify(stable.copy(timestamp = 1_600L)))
        assertEquals(unknown, verify(stable.copy(timestamp = 5_100L)))
        assertEquals(unknown, verify(stable.copy(visibleElements = listOf(
            el("Version 3 introduces security improvements and bug fixes", true,
                SemanticRole.BUTTON, 1)))))
    }

    @Test fun browserForegroundReplacementOrSensitiveSecondSampleFailsClosed() {
        val plan = requireNotNull(RenderedBrowserNavigationPolicy.plan(
            "Click the Release notes link", scene(), foreground(), 1_050L))
        val first = scene(timestamp = 1_800L, elements = listOf(
            el("Public release notes for version three", index = 0)))
        val second = scene(timestamp = 2_200L, elements = listOf(
            el("Public release notes for version three", index = 0)))
        val unknown = RenderedBrowserNavigationPolicy.Verification.UNKNOWN
        assertEquals(unknown, RenderedBrowserNavigationPolicy.verifyStable(
            plan, first, foreground(), second, foreground(w = 10), 1_500L, 2_300L))
        assertEquals(unknown, RenderedBrowserNavigationPolicy.verifyStable(
            plan, first, foreground(), second.copy(visibleElements = second.visibleElements +
                el("Enter password", role = SemanticRole.TEXT_INPUT, index = 1)),
            foreground(), 1_500L, 2_300L))
        assertEquals(unknown, RenderedBrowserNavigationPolicy.verifyStable(
            plan, first.copy(timestamp = 1_400L), foreground(), second,
            foreground(), 1_500L, 2_300L))
    }

}
