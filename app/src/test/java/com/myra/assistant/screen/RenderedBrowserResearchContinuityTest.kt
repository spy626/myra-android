package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class RenderedBrowserResearchContinuityTest {
    private val paragraph = "Version three introduces stability improvements for readers"
    private val receipt = RenderedBrowserPageEvidence.Receipt(
        action = RenderedBrowserPageEvidence.SourceAction.EXPLICIT_LINK_TAP,
        browserPackage = "com.android.chrome", windowId = 4, generation = 8L,
        firstObservedAt = 1_000L, secondObservedAt = 1_500L,
        stableVisibleLines = listOf(paragraph),
        contentSha256 = "a".repeat(64),
    )
    private fun screen(
        lines: List<String> = listOf("$paragraph!"),
        browser: String = "com.android.chrome",
        window: Int = 4,
        generation: Long = 8L,
        observed: Long = 2_200L,
        screenshot: Long = 2_210L,
    ) = RenderedBrowserObservation.Snapshot(
        browserPackage = browser, windowId = window, generation = generation,
        observedAt = observed, screenshotAt = screenshot,
        textLines = lines, linkLabels = listOf("Download", "Another link"),
    )
    private fun match(
        prior: RenderedBrowserPageEvidence.Receipt? = receipt,
        current: RenderedBrowserObservation.Snapshot? = screen(),
        now: Long = 2_300L,
    ) = RenderedBrowserResearchContinuity.correlate(prior, current, now)

    @Test fun explicitFreshScreenQuestionCanCorrelateAlreadyVisiblePriorActionText() {
        val linked = requireNotNull(match())
        assertEquals(RenderedBrowserPageEvidence.SourceAction.EXPLICIT_LINK_TAP, linked.action)
        assertEquals(receipt.contentSha256, linked.priorVisibleTextSha256)
        assertEquals(1, linked.matchedLineCount)
        val prompt = linked.prompt()
        assertTrue(prompt.contains("visible-text continuity ONLY"))
        assertTrue(prompt.contains("No click, scroll, follow-up"))
        assertFalse(prompt.contains(paragraph)) // never replay old raw page text into prompt
        assertFalse(prompt.contains("https://"))
    }

    @Test fun packageWindowGenerationAndChangedContentMustNotCorrelate() {
        assertNull(match(current = screen(browser = "org.mozilla.firefox")))
        assertNull(match(current = screen(window = 5)))
        assertNull(match(current = screen(generation = 9L)))
        assertNull(match(current = screen(lines = listOf(
            "The unrelated updated page contains completely different information"))))
        assertNull(match(current = screen(lines = listOf("New version",
            "Another visible link"), screenshot = 2_210L)))
        assertNull(match(prior = null))
        assertNull(match(current = null))
    }

    @Test fun rejectStaleFutureAndOldScreenshotInsteadOfReusingEvidence() {
        assertNull(match(current = screen(observed = 47_000L, screenshot = 47_010L), now = 47_100L))
        assertNull(match(current = screen(observed = 1_400L, screenshot = 2_210L)))
        assertNull(match(current = screen(observed = 2_200L, screenshot = 1_400L)))
        assertNull(match(current = screen(observed = 2_200L, screenshot = 2_500L)))
        assertNull(match(current = screen(observed = 2_200L, screenshot = 2_210L), now = 4_000L))
    }

    @Test fun shortGenericStringsOrLinkLabelsCannotAnchorResearchContext() {
        val generic = receipt.copy(stableVisibleLines = listOf("Click here to see more"))
        assertNull(match(prior = generic,
            current = screen(lines = listOf("Click here to see more"))))
        assertNull(match(current = screen(lines = emptyList())))
        val scroll = receipt.copy(
            action = RenderedBrowserPageEvidence.SourceAction.EXPLICIT_ONE_SCROLL)
        assertTrue(requireNotNull(match(prior = scroll)).prompt()
            .contains("one explicit page scroll"))
        assertFalse(requireNotNull(match()).prompt().contains("destination verified"))
    }
}
