package com.myra.assistant.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeActionResolverTest {
    private fun video(id: Int, title: String, left: Int, top: Int, right: Int, bottom: Int) =
        YouTubeActionResolver.VideoCandidate(
            ScreenTargetCandidate(id, title, "video", left, top, right, bottom, clickable = true)
        )

    @Test fun center_selects_currently_visible_center_video() {
        val result = YouTubeActionResolver.resolveVideoTarget(
            listOf(
                video(1, "Left video", 0, 500, 300, 800),
                video(2, "Center video", 250, 650, 750, 1050),
                video(3, "Right video", 780, 500, 1080, 800)
            ), "video", "center", null, 1080, 1920
        )
        assertTrue(result is YouTubeActionResolver.Result.Selected)
        assertEquals(2, (result as YouTubeActionResolver.Result.Selected).candidate.element.id)
    }

    @Test fun explicit_title_outranks_position() {
        val result = YouTubeActionResolver.resolveVideoTarget(
            listOf(
                video(1, "AI agents tutorial", 250, 650, 750, 1050),
                video(2, "Travel vlog", 0, 450, 300, 750)
            ), "AI agents video", "center", null, 1080, 1920
        )
        assertTrue(result is YouTubeActionResolver.Result.Selected)
        assertEquals(1, (result as YouTubeActionResolver.Result.Selected).candidate.element.id)
    }

    @Test fun similar_titles_are_ambiguous() {
        val result = YouTubeActionResolver.resolveVideoTarget(
            listOf(
                video(1, "AI agents tutorial part one", 0, 500, 500, 900),
                video(2, "AI agents tutorial part two", 550, 500, 1050, 900)
            ), "AI agents tutorial", null, null, 1080, 1920
        )
        assertTrue(result is YouTubeActionResolver.Result.Ambiguous)
    }

    @Test fun stale_before_and_unchanged_after_cannot_verify_video_open() {
        assertTrue(!YouTubeActionResolver.verifyVideoOpened("com.google.android.youtube", "com.google.android.youtube", "same", "same", true))
    }
}
