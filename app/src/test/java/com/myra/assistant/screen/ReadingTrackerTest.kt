package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ReadingTrackerTest {
    @Test fun `article start requires explicit request and article type`() {
        val tracker = ReadingTracker()
        assertNull(tracker.start("s1", "page", ScreenContentType.VIDEO_PLATFORM, true))
        assertNull(tracker.start("s1", "page", ScreenContentType.ARTICLE, false))
        assertNotNull(tracker.start("s1", "page", ScreenContentType.ARTICLE, true))
    }

    @Test fun `overlap is never read twice`() {
        val tracker = ReadingTracker()
        tracker.start("s1", "page", ScreenContentType.ARTICLE, true)
        val first = tracker.acceptVisibleText(
            listOf("Alpha is the first meaningful paragraph in this article.", "Bravo is the second meaningful paragraph in this article."), 10L
        )
        val second = tracker.acceptVisibleText(
            listOf("Bravo is the second meaningful paragraph in this article.", "Charlie is entirely new article content on the next screen."), 20L
        )
        assertEquals(2, first.size)
        assertEquals(listOf("charlie is entirely new article content on the next screen"), second.map { it.text })
    }

    @Test fun `minor punctuation and spacing differences deduplicate`() {
        val tracker = ReadingTracker()
        tracker.start("s1", "page", ScreenContentType.ARTICLE, true)
        tracker.acceptVisibleText(listOf("This is meaningful article content, with punctuation!"), 10L)
        assertTrue(tracker.acceptVisibleText(listOf(" This  is meaningful article content with punctuation "), 20L).isEmpty())
    }

    @Test fun `pause stop and resume control auto scroll`() {
        val tracker = ReadingTracker()
        tracker.start("s1", "page", ScreenContentType.ARTICLE, true)
        assertTrue(tracker.canAutoScroll())
        assertTrue(tracker.pause())
        assertFalse(tracker.canAutoScroll())
        assertTrue(tracker.resume())
        assertTrue(tracker.canAutoScroll())
        assertTrue(tracker.stop())
        assertFalse(tracker.canAutoScroll())
    }

    @Test fun `no new content and maximum scroll prevent infinite loop`() {
        val tracker = ReadingTracker(maxAutoScrolls = 2, maxNoNewContent = 2)
        tracker.start("s1", "page", ScreenContentType.ARTICLE, true)
        assertTrue(tracker.recordAutoScroll())
        assertTrue(tracker.recordAutoScroll())
        assertFalse(tracker.recordAutoScroll())

        val duplicateTracker = ReadingTracker(maxNoNewContent = 2)
        duplicateTracker.start("s1", "page", ScreenContentType.ARTICLE, true)
        duplicateTracker.acceptVisibleText(emptyList(), 1L)
        assertTrue(duplicateTracker.canAutoScroll())
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
}
