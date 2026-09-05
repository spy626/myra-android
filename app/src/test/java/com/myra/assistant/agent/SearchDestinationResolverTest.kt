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

    @Test fun finalGoogleSearchBuildsRuntimeIntentWhileGoogleAppIsForeground() {
        val agent = UnifiedLyraAgent()
        val context = CurrentActivityContext(
            "com.google.android.googlequicksearchbox", "Google", "SEARCH", 1, 1,
            emptyList(), confidence = .9, timestamp = 1
        )
        val decision = agent.acceptTurn("Google mein search karo new AI", context, true, turnId = 41L)
        val runtime = GeneralAgentRuntimeStore.runtime.activeTask()!!
        assertEquals(TurnIntent.MULTI_STEP_GOAL, decision.intent)
        assertEquals(41L, runtime.turnId)
        assertEquals(setOf(ToolCapability.BROWSER_SEARCH), runtime.intent.requiredCapabilities)
        val resolution = SearchDestinationResolver.resolveDetailed(
            BrowserSearchRequestParser.parse("Google mein search karo new AI")!!,
            context.packageName, null
        )
        assertEquals(BrowserSearchExecutor.CURRENT_GOOGLE_APP, resolution.selectedExecutor)
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
        val completed = store.completeSearch("browser_results_visible", TaskCompletionState.SUCCESS)
        assertNull(store.snapshot().lastVerifiedSuccess)
        assertNull(store.snapshot().completionState)
        assertNull(store.snapshot().resolvedDestination)
        assertEquals(TaskCompletionState.SUCCESS, completed.completionState)
        assertEquals(SearchDestination.BROWSER, store.snapshot().lastCompletedTask?.destination)
    }

    @Test fun completed_youtube_search_cannot_bias_new_chrome_search() {
        var time = 10L
        val store = WorkingTaskContextStore { time++ }
        store.beginSearch("old query", SearchDestination.YOUTUBE, "YOUTUBE", "results")
        store.completeSearch("youtube_results_visible", TaskCompletionState.SUCCESS)
        val resolution = SearchDestinationResolver.resolveDetailed(
            BrowserSearchRequestParser.parse("Search karo new AI")!!,
            "com.android.chrome",
            store.snapshot().activeExternalApp
        )
        assertEquals("new AI", BrowserSearchRequestParser.parse("Search karo new AI")!!.query)
        assertEquals(SearchDestination.BROWSER, resolution.destination)
        assertEquals(BrowserSearchExecutor.CURRENT_BROWSER, resolution.selectedExecutor)
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
