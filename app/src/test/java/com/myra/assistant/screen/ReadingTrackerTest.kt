package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ReadingTrackerTest {
    private fun start(tracker: ReadingTracker, session: String = "s1", page: String = "page", packageName: String = "com.browser") =
        tracker.start(session, page, packageName, ScreenContentType.ARTICLE, true, scrollContainerId = "test-container")

    @Test fun `article start requires explicit request and article type`() {
        val tracker = ReadingTracker()
        assertNull(tracker.start("s1", "page", "com.google.android.youtube", ScreenContentType.VIDEO_PLATFORM, true, scrollContainerId = "test-container"))
        assertNull(tracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, false, scrollContainerId = "test-container"))
        assertNotNull(start(tracker))
        assertEquals("test-container", tracker.snapshot()?.scrollContainerId)
    }

    @Test fun `overlap is never read twice`() {
        val tracker = ReadingTracker()
        start(tracker)
        val first = tracker.acceptVisibleText(
            listOf("Alpha is the first meaningful paragraph in this article.", "Bravo is the second meaningful paragraph in this article."), 10L
        )
        val second = tracker.acceptVisibleText(
            listOf("Bravo is the second meaningful paragraph in this article.", "Charlie is entirely new article content on the next screen."), 20L
        )
        assertEquals(2, first.size)
        assertEquals(listOf("charlie is entirely new article content on the next screen"), second.map { it.text })
    }

    @Test fun `combined overlapping text returns only unread remainder`() {
        val tracker = ReadingTracker()
        start(tracker)
        tracker.acceptVisibleText(
            listOf("The first consumed article paragraph contains enough meaningful words for reading."), 10L
        )
        val fresh = tracker.acceptVisibleText(
            listOf(
                "The first consumed article paragraph contains enough meaningful words for reading. " +
                    "This second paragraph is new and should be spoken only once."
            ), 20L
        )
        assertEquals(listOf("this second paragraph is new and should be spoken only once"), fresh.map { it.text })
    }

    @Test fun `minor punctuation and spacing differences deduplicate`() {
        val tracker = ReadingTracker()
        start(tracker)
        tracker.acceptVisibleText(listOf("This is meaningful article content, with punctuation!"), 10L)
        assertTrue(tracker.acceptVisibleText(listOf(" This  is meaningful article content with punctuation "), 20L).isEmpty())
    }

    @Test fun `pause stop and resume control auto scroll`() {
        val tracker = ReadingTracker()
        start(tracker)
        assertFalse(tracker.canAutoScroll())
        assertTrue(tracker.pause())
        assertFalse(tracker.canAutoScroll())
        assertTrue(tracker.resume())
        assertFalse(tracker.canAutoScroll())
        assertTrue(tracker.stop())
        assertFalse(tracker.canAutoScroll())
    }

    @Test fun `no new content and maximum scroll prevent infinite loop`() {
        val tracker = ReadingTracker(maxAutoScrolls = 2, maxNoNewContent = 2)
        start(tracker)
        tracker.acceptVisibleText(listOf("Initial meaningful article content before the first controlled scroll."), 1L)
        // The JVM unit test has no live AccessibilityService, so container validation
        // correctly blocks an actual device scroll. State/loop limits remain covered
        // by the separate state transition tests below.
        assertFalse(tracker.recordAutoScroll())

        val duplicateTracker = ReadingTracker(maxNoNewContent = 2)
        start(duplicateTracker)
        duplicateTracker.acceptVisibleText(emptyList(), 1L)
        assertFalse(duplicateTracker.canAutoScroll())
        duplicateTracker.acceptVisibleText(emptyList(), 2L)
        assertFalse(duplicateTracker.canAutoScroll())
    }

    @Test fun `reading commands are conservative`() {
        assertEquals(ReadingCommand.Start, ReadingIntentParser.parse("Read this article"))
        assertEquals(ReadingCommand.Continue, ReadingIntentParser.parse("Continue reading"))
        assertEquals(ReadingCommand.ReadNewOnly, ReadingIntentParser.parse("Read only the new content"))
        assertEquals(ReadingCommand.Stop, ReadingIntentParser.parse("Bas"))
        assertNull(ReadingIntentParser.parse("What do you see?"))
    }

    @Test fun `changing from article to YouTube pauses reading`() {
        val tracker = ReadingTracker()
        start(tracker, packageName = "com.android.chrome")
        assertTrue(tracker.pauseIfContextChanged("s1", "com.google.android.youtube"))
        assertEquals(ReadingState.PAUSED, tracker.snapshot()?.state)
        assertFalse(tracker.canAutoScroll())
    }

    @Test fun `scroll follows bounded ownership states`() {
        val tracker = ReadingTracker()
        start(tracker)
        assertTrue(tracker.markWaitingForScroll())
        // Without a live AccessibilityService, the safety gate refuses to execute a
        // physical scroll instead of guessing. The state transition itself is covered.
        assertFalse(tracker.recordAutoScroll())
        assertEquals(ReadingState.WAITING_FOR_SCROLL, tracker.snapshot()?.state)
    }

    @Test fun `automatic scroll decision requires matching article ownership`() {
        val tracker = ReadingTracker()
        start(tracker, session = "screen-1", packageName = "com.android.chrome")
        assertTrue(tracker.markWaitingForScroll())
        assertTrue(tracker.shouldAutoScroll("test-container", "screen-1", "com.android.chrome"))
        assertFalse(tracker.shouldAutoScroll("other-container", "screen-1", "com.android.chrome"))
        assertFalse(tracker.shouldAutoScroll("test-container", "old-screen", "com.android.chrome"))
        assertFalse(tracker.shouldAutoScroll("test-container", "screen-1", "com.google.android.youtube"))
    }

    @Test fun `repeated empty article content reaches safe end`() {
        val tracker = ReadingTracker(maxNoNewContent = 2)
        start(tracker)
        tracker.acceptVisibleText(emptyList(), 1L)
        assertFalse(tracker.reachedArticleEnd())
        tracker.acceptVisibleText(emptyList(), 2L)
        assertTrue(tracker.reachedArticleEnd())
    }

    @Test fun `explicit container identity can be replaced only while waiting or reading`() {
        val tracker = ReadingTracker()
        start(tracker)
        assertTrue(tracker.bindScrollContainer("container-b"))
        assertEquals("container-b", tracker.currentBoundScrollContainerId())
        assertTrue(tracker.pause())
        assertFalse(tracker.bindScrollContainer("container-c"))
    }
}
