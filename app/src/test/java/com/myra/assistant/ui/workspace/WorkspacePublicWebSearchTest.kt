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

    @Test fun unsafeAndCredentialDestinationsAreDropped() {
        val html = """
            <a class="result__a" href="http://example.com/test">Example test</a>
            <a class="result__a" href="https://localhost/test">Local test</a>
            <a class="result__a" href="https://example.com/?access_token=secret">Token page</a>
            <a class="result__a" href="https://example.com/login">Login page</a>
        """.trimIndent()
        assertTrue(WorkspacePublicWebSearch.parseHtml(html, "Example test", null).isEmpty())
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
