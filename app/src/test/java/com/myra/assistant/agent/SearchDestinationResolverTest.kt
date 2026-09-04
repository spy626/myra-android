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

    @Test fun partial_search_is_candidate_only_and_cannot_execute() {
        assertFalse(SearchExecutionPolicy.mayExecute(authoritativeFinalTranscript = false))
        assertEquals(true, SearchExecutionPolicy.mayExecute(authoritativeFinalTranscript = true))
    }

    @Test fun search_for_query_is_owned_by_contextual_search_not_legacy_youtube() {
        val request = BrowserSearchRequestParser.parse("Search for new AI")!!
        assertEquals("new AI", request.query)
        val decision = UnifiedTurnInterpreter.interpret("Search for new AI", null)
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        val resolution = SearchDestinationResolver.resolveDetailed(request, "com.android.chrome", null)
        assertEquals(SearchDestination.BROWSER, resolution.destination)
        assertEquals(BrowserSearchExecutor.CURRENT_BROWSER, resolution.selectedExecutor)
    }

    @Test fun browser_results_with_query_are_verified_success_not_model_opinion() {
        val request = BrowserSearchRequest("new AI")
        val resolution = SearchDestinationResolver.resolveDetailed(request, "com.android.chrome", null)
        assertEquals(
            SearchVerification.SUCCESS,
            BrowserSearchVerificationPolicy.verify(
                request, resolution, "com.android.chrome", listOf("new AI - Google Search", "Results")
            )
        )
        assertEquals(
            SearchVerification.UNKNOWN,
            BrowserSearchVerificationPolicy.verify(request, resolution, "com.google.android.youtube", listOf("new AI"))
        )
    }

    @Test fun working_search_task_records_authoritative_verified_outcome() {
        var time = 10L
        val store = WorkingTaskContextStore { time++ }
        store.beginSearch("new AI", SearchDestination.BROWSER, "CURRENT_BROWSER", "search_results_visible")
        assertEquals(TaskCompletionState.EXECUTING, store.snapshot().completionState)
        store.completeSearch("browser_results_visible", TaskCompletionState.SUCCESS)
        assertEquals(true, store.snapshot().lastVerifiedSuccess)
        assertEquals(TaskCompletionState.SUCCESS, store.snapshot().completionState)
        assertEquals(SearchDestination.BROWSER, store.snapshot().resolvedDestination)
    }

    @Test fun verifiedSuccessCannotProduceFailureOrOrdinaryModelResult() {
        assertFalse(SearchTaskResultPolicy.maySpeakFailure(SearchVerification.SUCCESS))
        assertFalse(SearchTaskResultPolicy.maySpeakFailure(SearchVerification.UNKNOWN))
        assertEquals(true, SearchTaskResultPolicy.maySpeakFailure(SearchVerification.FAILURE))
        assertFalse(SearchTaskResultPolicy.ordinaryModelMayReportResult(TaskCompletionState.EXECUTING))
        assertFalse(SearchTaskResultPolicy.ordinaryModelMayReportResult(TaskCompletionState.SUCCESS))
        assertFalse(SearchTaskResultPolicy.ordinaryModelMayReportResult(TaskCompletionState.UNKNOWN))
    }

    @Test fun youtubeSearchUsesSameVerificationContract() {
        assertEquals(
            SearchVerification.SUCCESS,
            YouTubeSearchVerificationPolicy.verify(
                "new AI", "com.google.android.youtube", listOf("new AI", "Search results")
            )
        )
        assertEquals(
            SearchVerification.UNKNOWN,
            YouTubeSearchVerificationPolicy.verify(
                "new AI", "com.android.chrome", listOf("new AI")
            )
        )
    }
}
