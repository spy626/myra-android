package com.myra.assistant.agent

import com.myra.assistant.ai.CommandParser
import com.myra.assistant.model.AppCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SearchDestinationResolverTest {
    @Test fun browser_foreground_generic_search_never_becomes_youtube() {
        val request = BrowserSearchRequestParser.parse("search karo new AI")!!
        assertNull(request.explicitDestination)
        assertEquals(
            SearchDestination.BROWSER,
            SearchDestinationResolver.resolve(request, "com.google.android.googlequicksearchbox", null)
        )
        assertFalse(CommandParser.parse("search karo new AI") is AppCommand.SearchYouTube)
        val resolution = SearchDestinationResolver.resolveDetailed(request, "com.android.chrome", null)
        assertEquals(BrowserSearchExecutor.CURRENT_BROWSER, resolution.selectedExecutor)
        assertEquals("com.android.chrome", resolution.targetPackage)
    }

    @Test fun google_foreground_generic_search_stays_in_current_google_search_environment() {
        val request = BrowserSearchRequestParser.parse("Search karo new AI")!!
        val resolution = SearchDestinationResolver.resolveDetailed(
            request, "com.google.android.googlequicksearchbox", "com.google.android.youtube"
        )
        assertEquals(SearchDestination.BROWSER, resolution.destination)
        assertEquals(BrowserSearchExecutor.CURRENT_GOOGLE_APP, resolution.selectedExecutor)
        assertEquals("com.google.android.googlequicksearchbox", resolution.targetPackage)
        assertEquals("current_google_search_context", resolution.reason)
    }

    @Test fun explicit_youtube_destination_is_allowed() {
        val request = BrowserSearchRequestParser.parse("Youtube pe new AI search karo")!!
        assertEquals(SearchDestination.YOUTUBE, request.explicitDestination)
        assertEquals(SearchDestination.YOUTUBE, SearchDestinationResolver.resolve(request, "com.android.chrome", null))
    }

    @Test fun generic_search_uses_current_youtube_context_only_when_current() {
        val request = BrowserSearchRequestParser.parse("search karo new AI")!!
        assertEquals(
            SearchDestination.YOUTUBE,
            SearchDestinationResolver.resolve(request, "com.google.android.youtube", "com.google.android.youtube")
        )
    }

    @Test fun assistant_overlay_preserves_active_search_destination_but_real_app_switch_wins() {
        val request = BrowserSearchRequestParser.parse("search karo new AI")!!
        assertEquals(
            SearchDestination.YOUTUBE,
            SearchDestinationResolver.resolve(request, "com.myra.assistant", "com.google.android.youtube")
        )
        assertEquals(
            SearchDestination.BROWSER,
            SearchDestinationResolver.resolve(request, "com.android.chrome", "com.google.android.youtube")
        )
    }

    @Test fun youtube_search_feature_discussion_stays_conversation() {
        val decision = UnifiedTurnInterpreter.interpret("Youtube mein search feature better hona chahiye", null)
        assertEquals(TurnIntent.CONVERSATION, decision.intent)
        assertFalse(decision.authorizesPhoneActions)
    }
}
