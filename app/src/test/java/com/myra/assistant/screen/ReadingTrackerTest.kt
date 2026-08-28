package com.myra.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ReadingTrackerTest {
    @Test fun `article start requires explicit request and article type`() {
        val tracker = ReadingTracker()
        assertNull(tracker.start("s1", "page", "com.google.android.youtube", ScreenContentType.VIDEO_PLATFORM, true))
        assertNull(tracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, false))
        assertNotNull(tracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, true))
    }

    @Test fun `overlap is never read twice`() {
        val tracker = ReadingTracker()
        tracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, true)
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
        tracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, true)
        tracker.acceptVisibleText(listOf("This is meaningful article content, with punctuation!"), 10L)
        assertTrue(tracker.acceptVisibleText(listOf(" This  is meaningful article content with punctuation "), 20L).isEmpty())
    }

    @Test fun `pause stop and resume control auto scroll`() {
        val tracker = ReadingTracker()
        tracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, true)
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
        tracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, true)
        assertTrue(tracker.recordAutoScroll())
        assertTrue(tracker.markVerifyingNewContent(2L, 2L))
        tracker.acceptVisibleText(listOf("A new meaningful paragraph after the first controlled article scroll."), 2L)
        assertTrue(tracker.recordAutoScroll())
        assertFalse(tracker.recordAutoScroll())

        val duplicateTracker = ReadingTracker(maxNoNewContent = 2)
        duplicateTracker.start("s1", "page", "com.browser", ScreenContentType.ARTICLE, true)
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

    @Test fun `changing from article to YouTube pauses reading`() {
        val tracker = ReadingTracker()
        tracker.start("s1", "article", "com.android.chrome", ScreenContentType.ARTICLE, true)
        assertTrue(tracker.pauseIfContextChanged("s1", "com.google.android.youtube"))
        assertEquals(ReadingState.PAUSED, tracker.snapshot()?.state)
        assertFalse(tracker.canAutoScroll())
    }

    @Test fun `scroll follows bounded ownership states`() {
        val tracker = ReadingTracker()
        tracker.start("s1", "article", "com.android.chrome", ScreenContentType.ARTICLE, true)
        assertTrue(tracker.markWaitingForScroll())
        assertTrue(tracker.recordAutoScroll())
        assertEquals(ReadingState.SCROLLING, tracker.snapshot()?.state)
        assertTrue(tracker.markVerifyingNewContent(7L, 8L))
        assertEquals(ReadingState.VERIFYING_NEW_CONTENT, tracker.snapshot()?.state)
    }
}
