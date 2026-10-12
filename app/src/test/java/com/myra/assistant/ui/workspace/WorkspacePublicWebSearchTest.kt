package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePublicWebSearchTest {
    @Test fun parsesAndRanksSafeDirectResultFromPublicSearchHtml() {
        val html = """
            <div class="result">
              <a rel="nofollow" class="result__a"
                 href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2Fmoeru-ai%2Fairi">
                 moeru-ai / airi · GitHub
              </a>
              <a class="result__snippet">A self hosted AI companion project on GitHub.</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://example.com/airi">AIRI notes</a>
            </div>
        """.trimIndent()

        val results = WorkspacePublicWebSearch.parseHtml(html, "AIRI repo", "github.com")
        assertTrue(results.isNotEmpty())
        assertEquals("https://github.com/moeru-ai/airi", results.first().url)
        assertTrue(results.first().snippet.contains("AI companion"))
    }

    @Test fun htmlSearchSupportsReorderedAttributesAndResultsWithNestedMarkup() {
        val html = """
            <a href="https://kotlinlang.org/docs/coroutines-overview.html"
               class="result__a"><span>Kotlin Coroutines overview</span></a>
            <a class="result-link" data-pos="2"
               href="https://developer.android.com/kotlin/coroutines">
               Kotlin coroutines on Android
            </a>
            <a class="other" href="https://irrelevant.example.com/coroutines">
               Fake result not in search
            </a>
        """.trimIndent()
        val results = WorkspacePublicWebSearch.parseHtml(
            html, "Kotlin coroutines documentation", null)
        assertEquals(2, results.size)
        assertTrue(results.any {
            it.url == "https://kotlinlang.org/docs/coroutines-overview.html"
        })
        assertTrue(results.any { it.url.startsWith("https://developer.android.com/") })
    }

    @Test fun fallbackRejectsSubstringOnlyGithubMatches() {
        val html = """
            <a class="result__a" href="https://github.com/Shottakon/AirialPerspectiveEffecter">
                AIRI perspective effect repo
            </a>
            <a class="result__a" href="https://github.com/moeru-ai/airi">
                moeru-ai/airi · GitHub
            </a>
        """.trimIndent()
        val candidates = WorkspacePublicWebSearch.parseHtml(html, "AIRI repo", "github.com")
        assertEquals(listOf("https://github.com/moeru-ai/airi"), candidates.map { it.url })
    }

    @Test fun unsafeAndCredentialDestinationsAreDropped() {
        val html = """
            <a class="result__a" href="http://example.com/test">Example test</a>
            <a class="result__a" href="https://localhost/test">Local test</a>
            <a class="result__a" href="https://example.com/?access_token=secret">Token page</a>
            <a class="result__a" href="https://example.com/login">Login page</a>
        """.trimIndent()
        assertTrue(WorkspacePublicWebSearch.parseHtml(html, "Example test", null).isEmpty())
    }

    @Test fun repositoryRequestRejectsProfilesAndPicksRepoEvenWhenProfileIsFirst() {
        val html = """
            <a class="result__a" href="https://github.com/proj-airi">
                Project AIRI · GitHub
            </a>
            <a class="result__snippet">The AIRI organization profile.</a>
            <a class="result__a" href="https://github.com/moeru-ai/airi">
                moeru-ai/airi: AI companion · GitHub
            </a>
            <a class="result__snippet">Source code for the AIRI project.</a>
        """.trimIndent()
        val candidates = WorkspacePublicWebSearch.parseHtml(html, "AIRI repo", "github.com")
        assertEquals(1, candidates.size)
        assertEquals("https://github.com/moeru-ai/airi", candidates.single().url)
    }

    @Test fun repositoryRequestFailsClosedOnProfilesAndOtherGithubPages() {
        val html = """
            <a class="result__a" href="https://github.com/proj-airi">AIRI profile</a>
            <a class="result__a" href="https://github.com/features/copilot">AIRI repo docs</a>
            <a class="result__a" href="https://github.com/moeru-ai/airi/issues">AIRI issues</a>
        """.trimIndent()
        assertTrue(WorkspacePublicWebSearch.parseHtml(html, "AIRI repo", "github.com").isEmpty())
        assertTrue(!WorkspacePublicWebSearch.matchesRequestedDestination(
            "https://github.com/proj-airi", "AIRI repository", "github.com"))
        assertTrue(!WorkspacePublicWebSearch.matchesRequestedDestination(
            "https://github.com/moeru-ai/airi/tree/main", "AIRI repo", "github.com"))
    }

    @Test fun githubProfileRequestStillAllowsProfiles() {
        assertTrue(WorkspacePublicWebSearch.matchesRequestedDestination(
            "https://github.com/proj-airi", "AIRI profile", "github.com"))
        assertTrue(WorkspacePublicWebSearch.isGitHubRepositoryUrl(
            "https://github.com/moeru-ai/airi"))
    }

    @Test fun preferredHostIsNotSufficientWithoutMatchingTopic() {
        val html = """
            <a class="result__a" href="https://example.org/unrelated">Unrelated</a>
            <a class="result__a" href="https://platform.openai.com/docs">OpenAI API documentation</a>
            <a class="result__a" href="https://openai.com/about">Company</a>
        """.trimIndent()
        val results = WorkspacePublicWebSearch.parseHtml(html, "API docs", "openai.com")
        assertEquals(listOf("https://platform.openai.com/docs"), results.map { it.url })
    }

    @Test fun docsDiscoveryCanRetryOnceWithoutModifyingRequestedCount() {
        val req = WorkspaceWebLinkIntent.Request(
            "Kotlin coroutines documentation", maxResults = 3)
        val broader = WorkspacePublicWebSearch.broaderDocumentationRequest(req)
        assertEquals("Kotlin coroutines", broader?.query)
        assertEquals(3, broader?.maxResults)
        assertNull(WorkspacePublicWebSearch.broaderDocumentationRequest(
            WorkspaceWebLinkIntent.Request("AIRI repo", "github.com")))
        assertNull(WorkspacePublicWebSearch.broaderDocumentationRequest(
            WorkspaceWebLinkIntent.Request("Kotlin coroutines")))
    }

    @Test fun multiSourceRequiresVerifiedDistinctSites() {
        val request = WorkspaceWebLinkIntent.Request("Kotlin coroutines", maxResults = 3)
        val first = WorkspacePublicWebSearch.Verified(
            "Kotlin docs", "https://kotlinlang.org/docs/coroutines-overview.html",
            "Kotlin", "text/html",
        )
        val sameSite = first.copy(finalUrl = "https://kotlinlang.org/docs/coroutines-guide.html")
        val other = first.copy(finalUrl = "https://developer.android.com/kotlin/coroutines")
        assertTrue(!WorkspacePublicWebSearch.canAddResult(request, listOf(first), sameSite))
        assertTrue(!WorkspacePublicWebSearch.canAddResult(request, listOf(first), first))
        assertTrue(WorkspacePublicWebSearch.canAddResult(request, listOf(first), other))
        val result = WorkspacePublicWebSearch.receiptAll(listOf(first), 3)
        assertTrue(result.contains("only 1 could be verified"))
    }

    @Test fun searchRequestIsGetOnlyWithoutCredentialHeaders() {
        val request = WorkspacePublicWebSearch.searchRequest(
            WorkspaceWebLinkIntent.Request("AIRI repo", "github.com")
        )
        assertEquals("GET", request.method)
        assertEquals("html.duckduckgo.com", request.url.host)
        assertTrue(request.url.query.orEmpty().contains("github.com"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
    }
}
