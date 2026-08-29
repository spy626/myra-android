package com.myra.assistant.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTrackerSafetyTest {
    @Test fun youtube_cannot_start_article_session() {
        val tracker = ReadingTracker()
        assertTrue(tracker.start("session", "page", "com.google.android.youtube", ScreenContentType.ARTICLE, true) == null)
    }

    @Test fun bound_scroll_container_must_match_current_session() {
        val tracker = ReadingTracker()
        val session = tracker.start("session", "page", "com.android.chrome", ScreenContentType.ARTICLE, true)
        assertNotNull(session)
        assertTrue(tracker.bindScrollContainer("article-body"))
        assertTrue(tracker.acceptsScrollContainer("article-body", "session", "com.android.chrome"))
        assertFalse(tracker.acceptsScrollContainer("other", "session", "com.android.chrome"))
        assertFalse(tracker.acceptsScrollContainer("article-body", "old-session", "com.android.chrome"))
        assertFalse(tracker.acceptsScrollContainer("article-body", "session", "com.google.android.youtube"))
    }

    @Test fun duplicate_visible_text_is_not_returned_as_new_content() {
        val tracker = ReadingTracker()
        tracker.start("session", "page", "com.android.chrome", ScreenContentType.ARTICLE, true)
        val first = tracker.acceptVisibleText(listOf("This is the first article paragraph with enough meaningful content."), 1L)
        val second = tracker.acceptVisibleText(listOf("This is the first article paragraph with enough meaningful content."), 2L)
        assertTrue(first.isNotEmpty())
        assertTrue(second.isEmpty())
    }
}
