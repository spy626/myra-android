package com.myra.assistant.screen

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSemanticActionsTest {
    @Test fun extracts_exact_comment_payloads() {
        assertEquals("I like your video", (YouTubeSemanticCommandParser.parse("Type karo I like your video") as YouTubeSemanticCommand.TypeText).payload)
        assertEquals("nice video bro", (YouTubeSemanticCommandParser.parse("Comment mein likho nice video bro") as YouTubeSemanticCommand.TypeText).payload)
        assertEquals("thank you so much", (YouTubeSemanticCommandParser.parse("Isme type karo thank you so much") as YouTubeSemanticCommand.TypeText).payload)
        assertEquals("amazing video", (YouTubeSemanticCommandParser.parse("Nahi change karo amazing video") as YouTubeSemanticCommand.TypeText).payload)
    }

    @Test fun ordinary_conversation_is_not_text_or_send_intent() {
        assertNull(YouTubeSemanticCommandParser.parse("Ye video kis bare mein hai?"))
        assertNull(YouTubeSemanticCommandParser.parse("done"))
        assertTrue(YouTubeSemanticCommandParser.parse("Send karo") is YouTubeSemanticCommand.SendComment)
    }

    @Test fun recognises_hinglish_and_devanagari_current_video_controls_before_transliteration() {
        assertTrue(YouTubeSemanticCommandParser.parse("comments kholo") is YouTubeSemanticCommand.OpenComments)
        assertTrue(YouTubeSemanticCommandParser.parse("comment open karo") is YouTubeSemanticCommand.OpenComments)
        assertTrue(YouTubeSemanticCommandParser.parse("कमेंट ओपन करो") is YouTubeSemanticCommand.OpenComments)
        assertTrue(YouTubeSemanticCommandParser.parse("कमेंट खोलो") is YouTubeSemanticCommand.OpenComments)
        assertTrue(YouTubeSemanticCommandParser.parse("कमेंट दिखाओ") is YouTubeSemanticCommand.OpenComments)
        assertTrue(YouTubeSemanticCommandParser.parse("video like karo") is YouTubeSemanticCommand.Like)
        assertTrue(YouTubeSemanticCommandParser.parse("वीडियो लाइक करो") is YouTubeSemanticCommand.Like)
        assertTrue(YouTubeSemanticCommandParser.parse("subscribe karo") is YouTubeSemanticCommand.Subscribe)
    }

    @Test fun devanagari_combining_marks_match_in_decomposed_input() {
        val decomposed = Normalizer.normalize("कमेंट ओपन करो", Normalizer.Form.NFD)
        assertTrue(YouTubeSemanticCommandParser.parse(decomposed) is YouTubeSemanticCommand.OpenComments)
    }

    @Test fun profile_name_stays_bound_to_its_card() {
        val elements = listOf(
            YouTubeSemanticElement("a-name", YouTubeSemanticRole.CHANNEL_NAME, "Alice", "a"),
            YouTubeSemanticElement("a-pic", YouTubeSemanticRole.CHANNEL_PROFILE, "Alice profile", "a"),
            YouTubeSemanticElement("j-name", YouTubeSemanticRole.CHANNEL_NAME, "Jonathan", "j"),
            YouTubeSemanticElement("j-pic", YouTubeSemanticRole.CHANNEL_PROFILE, "Jonathan profile", "j")
        )
        val result = YouTubeSemanticResolver.resolveChannel(elements, "Jonathan", true) as YouTubeSemanticResolution.Selected
        assertEquals("j-pic", result.element.id)
    }

    @Test fun ambiguous_channel_does_not_guess() {
        val elements = listOf(
            YouTubeSemanticElement("j1", YouTubeSemanticRole.CHANNEL_PROFILE, "Jonathan profile", "one"),
            YouTubeSemanticElement("j2", YouTubeSemanticRole.CHANNEL_PROFILE, "Jonathan profile", "two")
        )
        assertTrue(YouTubeSemanticResolver.resolveChannel(elements, "Jonathan", true) is YouTubeSemanticResolution.Ambiguous)
    }

    @Test fun current_video_control_excludes_comment_like_and_does_not_toggle_selected() {
        val elements = listOf(
            YouTubeSemanticElement("video-like", YouTubeSemanticRole.LIKE_BUTTON, "Like this video", "watch", selected = true),
            YouTubeSemanticElement("comment-like", YouTubeSemanticRole.MORE_ACTIONS, "Like comment", "comments")
        )
        assertTrue(YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.LIKE_BUTTON) is YouTubeSemanticResolution.AlreadyActive)
    }

    @Test fun subscribe_selected_is_not_toggled_off() {
        val element = YouTubeSemanticElement("subscribe", YouTubeSemanticRole.SUBSCRIBE_BUTTON, "Subscribed", "watch", selected = true)
        assertTrue(YouTubeSemanticResolver.resolveControl(listOf(element), YouTubeSemanticRole.SUBSCRIBE_BUTTON) is YouTubeSemanticResolution.AlreadyActive)
    }

    @Test fun comment_draft_requires_owned_context_and_explicit_send() {
        val tracker = YouTubeCommentComposeTracker()
        tracker.commentsOpened("com.google.android.youtube", 4, 8)
        assertTrue(tracker.draftSet("com.google.android.youtube", 4, 8, "field", "I like your video"))
        assertEquals("I like your video", tracker.snapshot()?.draft)
        assertTrue(tracker.canSend("com.google.android.youtube", 4, 8))
        assertFalse(tracker.canSend("com.android.chrome", 4, 8))
        tracker.invalidateUnless("com.android.chrome", 5, 9)
        assertNull(tracker.snapshot())
    }
}
