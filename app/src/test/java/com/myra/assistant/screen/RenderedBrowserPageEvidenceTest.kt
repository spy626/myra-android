package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import org.junit.Assert.*
import org.junit.Test

class RenderedBrowserPageEvidenceTest {
    private fun el(text: String, role: SemanticRole = SemanticRole.TEXT,
        actionable: Boolean = false, index: Int = 0) = SemanticElement(
        id = "4:8:$index", role = role, label = text, actionable = actionable,
        left = 10, top = 40 + index * 50, right = 650, bottom = 80 + index * 50)

    private fun foreground(w: Int = 4, g: Long = 8L,
        pkg: String = "com.android.chrome") =
        ForegroundAppContext(pkg, windowId = w, generation = g, observedAt = 1_000L)

    private fun scene(at: Long, elements: List<SemanticElement>,
        w: Int = 4, g: Long = 8L, pkg: String = "com.android.chrome") =
        CurrentActivityContext(pkg, windowId = w, generation = g,
            visibleElements = elements, confidence = .92, timestamp = at)

    private val before = scene(1_000L, listOf(
        el("Welcome to the product documentation"),
        el("Release notes", SemanticRole.BUTTON, true, 1),
        el("How to install the first version", index = 2),
    ))

    private fun linkPlan() = requireNotNull(RenderedBrowserNavigationPolicy.plan(
        "Click the Release notes link", before, foreground(), 1_040L))

    private fun linkReceipt(first: CurrentActivityContext?, second: CurrentActivityContext?) =
        RenderedBrowserPageEvidence.afterNamedLink(
            linkPlan(), first, foreground(), second, foreground(), 1_500L, 2_300L)

    @Test fun verifiedLinkProducesBoundedHumanVisibleSourceFingerprintNotUrlAuthority() {
        val first = scene(1_800L, listOf(
            el("Release notes"),
            el("Version three introduces stability improvements for readers", index = 1),
            el("Loading content", index = 2),
        ))
        val second = scene(2_230L, listOf(
            el("Version three introduces stability improvements for readers!", index = 0),
            el("Further public documentation is available on this page", index = 1),
        ))
        val receipt = requireNotNull(linkReceipt(first, second))
        assertEquals(RenderedBrowserPageEvidence.SourceAction.EXPLICIT_LINK_TAP, receipt.action)
        assertEquals("com.android.chrome", receipt.browserPackage)
        assertEquals(4, receipt.windowId)
        assertEquals(8L, receipt.generation)
        assertEquals(1, receipt.stableVisibleLines.size)
        assertTrue(receipt.localPreview().startsWith("Version three introduces"))
        assertEquals(64, receipt.contentSha256.length)
        assertTrue(receipt.contentSha256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(receipt.destinationUrlVerified)
        assertFalse(receipt.permitsNextAction)
    }

    @Test fun unchangedOrTransientOrActionableOnlyTextCannotBecomeEvidence() {
        assertNull(linkReceipt(
            scene(1_800L, listOf(el("Loading content please wait"))),
            scene(2_230L, listOf(el("Loading content please wait")))))
        assertNull(linkReceipt(
            scene(1_800L, listOf(el("Version three introduces stability improvements for readers"))),
            scene(2_230L, listOf(el("This page now has different content and no shared paragraph")))))
        assertNull(linkReceipt(
            scene(1_800L, listOf(el("Version three introduces stability improvements for readers",
                SemanticRole.BUTTON, true))),
            scene(2_230L, listOf(el("Version three introduces stability improvements for readers",
                SemanticRole.BUTTON, true)))))
        assertNull(linkReceipt(
            scene(1_800L, listOf(el("Enter password"))),
            scene(2_230L, listOf(el("Enter password")))))
    }

    @Test fun unsafeScreenOrDifferentWindowCannotBeProjectedAsSourceReceipt() {
        val line = el("Version three introduces stability improvements for readers")
        val first = scene(1_800L, listOf(line))
        val second = scene(2_240L, listOf(line))
        assertNull(linkReceipt(first, second.copy(windowId = 99)))
        assertNull(linkReceipt(first, second.copy(generation = 10L)))
        assertNull(linkReceipt(first, second.copy(visibleElements =
            listOf(line, el("Enter password", SemanticRole.TEXT_INPUT, index = 1)))))
        assertNull(linkReceipt(first.copy(timestamp = 1_400L), second))
    }

    @Test fun verifiedOneScrollReturnsOnlyNewCommonVisibleParagraph() {
        val scrollBefore = scene(1_000L, listOf(
            el("Introduction to the product information guide"),
            el("A visible paragraph explaining the initial topic", index = 1)))
        val plan = requireNotNull(RenderedBrowserScrollPolicy.plan(
            "Scroll browser page down once", scrollBefore, foreground(), 1_040L))
        val a = scene(1_800L, listOf(
            el("A visible paragraph explaining the initial topic"),
            el("The next section describes a new public feature in detail", index = 1)))
        val b = scene(2_250L, listOf(
            el("The next section describes a new public feature in detail", index = 0)))
        val receipt = requireNotNull(RenderedBrowserPageEvidence.afterOneScroll(
            plan, a, foreground(), b, foreground(), 1_500L, 2_300L))
        assertEquals(RenderedBrowserPageEvidence.SourceAction.EXPLICIT_ONE_SCROLL, receipt.action)
        assertEquals(listOf("The next section describes a new public feature in detail"),
            receipt.stableVisibleLines)
        assertFalse(receipt.destinationUrlVerified)
        assertFalse(receipt.permitsNextAction)
        assertNull(RenderedBrowserPageEvidence.afterOneScroll(
            plan, a, foreground(), b.copy(packageName = "com.other.app"),
            foreground(), 1_500L, 2_300L))
    }

    @Test fun evidenceNeverContainsAddressBarUrlSecretsOrUnstableLabels() {
        val first = scene(1_800L, listOf(
            el("Version three introduces stability improvements for readers"),
            el("https://example.com/?session=reallyprivate", index = 1),
            el("The access token is token=SECRET do not show this", index = 2)))
        val second = scene(2_240L, listOf(
            el("Version three introduces stability improvements for readers"),
            el("https://example.com/?session=reallyprivate", index = 1),
            el("The access token is token=SECRET do not show this", index = 2)))
        val result = linkReceipt(first, second)
        // A sensitive-looking browser screen must be rejected in its entirety by the
        // upstream navigation gate, not merely hide the offending line in the receipt.
        assertNull(result)
    }
}
