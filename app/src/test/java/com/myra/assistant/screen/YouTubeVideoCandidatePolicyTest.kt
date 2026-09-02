package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeVideoCandidatePolicyTest {
    private fun candidate(
        id: Int,
        title: String,
        context: String,
        group: String,
        top: Int
    ) = YouTubeVideoCandidate(id, title, context, group, top)

    @Test
    fun sponsored_ad_does_not_shift_video_ordinal() {
        val candidates = listOf(
            candidate(1, "Install", "Sponsored advertisement Learn more", "ad", 100),
            candidate(2, "Video A", "Video title 12K views 2 days ago", "video-a", 300),
            candidate(3, "Video B", "Video title 8K views 1 day ago", "video-b", 600)
        )
        assertEquals(3, YouTubeVideoCandidatePolicy.selectOrdinal(candidates, 2)?.id)
    }

    @Test
    fun thumbnail_title_and_metadata_count_as_one_logical_video() {
        val candidates = listOf(
            candidate(1, "Video A thumbnail", "thumbnail 12K views", "video-a", 200),
            candidate(2, "Video A", "video title 12K views 2 days ago", "video-a", 220),
            candidate(3, "12K views", "Video A metadata", "video-a", 240),
            candidate(4, "Video B", "video title 3K views", "video-b", 500)
        )
        assertEquals(2, YouTubeVideoCandidatePolicy.logicalVideos(candidates).size)
        assertEquals(4, YouTubeVideoCandidatePolicy.selectOrdinal(candidates, 2)?.id)
    }

    @Test
    fun install_and_learn_more_are_not_video_candidates() {
        val candidates = listOf(
            candidate(1, "Install", "Sponsored Google Play", "cta-1", 100),
            candidate(2, "Learn More", "Advertisement visit advertiser", "cta-2", 200)
        )
        assertNull(YouTubeVideoCandidatePolicy.selectOrdinal(candidates, 1))
    }

    @Test
    fun shorts_and_navigation_controls_are_not_normal_videos() {
        val candidates = listOf(
            candidate(1, "Shorts", "Shorts video 1M views", "shorts", 100),
            candidate(2, "Home", "YouTube home navigation", "home", 200),
            candidate(3, "Normal Video", "video title 9K views", "video", 400)
        )
        assertEquals(3, YouTubeVideoCandidatePolicy.selectOrdinal(candidates, 1)?.id)
    }

    @Test
    fun video_open_selects_playable_child_not_profile_or_action_menu() {
        val candidates = listOf(
            candidate(1, "Video A", "video title 10K views", "a", 100),
            candidate(2, "Video B", "video title 8K views", "b", 400).copy(semanticRole = YouTubeSemanticRole.VIDEO_TITLE),
            candidate(3, "Jonathan profile", "Video B 8K views", "b", 410).copy(semanticRole = YouTubeSemanticRole.CHANNEL_PROFILE),
            candidate(4, "Action menu for Video B", "Video B 8K views", "b", 420).copy(semanticRole = YouTubeSemanticRole.MORE_ACTIONS)
        )
        val selected = YouTubeVideoCandidatePolicy.selectOrdinal(candidates, 2)
        assertEquals(2, selected?.id)
        assertEquals(YouTubeSemanticRole.VIDEO_TITLE, selected?.semanticRole)
    }

    @Test
    fun wrong_child_alone_is_not_a_video_open_target() {
        val candidates = listOf(
            candidate(1, "Action menu for Video A", "video title 10K views", "a", 100)
                .copy(semanticRole = YouTubeSemanticRole.MORE_ACTIONS)
        )
        assertNull(YouTubeVideoCandidatePolicy.selectOrdinal(candidates, 1))
    }
}
