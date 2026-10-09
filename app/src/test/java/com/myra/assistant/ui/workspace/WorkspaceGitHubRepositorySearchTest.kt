package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubRepositorySearchTest {
    private val request = WorkspaceWebLinkIntent.Request("AIRI repo", "github.com")
    private val payload = """
        {
          "total_count":3,
          "items":[
            {"full_name":"proj-airi/airi-tools","name":"airi-tools",
             "html_url":"https://github.com/proj-airi/airi-tools",
             "description":"AIRI tools", "stargazers_count":300, "fork":false},
            {"full_name":"moeru-ai/airi","name":"airi",
             "html_url":"https://github.com/moeru-ai/airi",
             "description":"Self-hosted AI companion", "stargazers_count":50000, "fork":false},
            {"full_name":"someone/unrelated","name":"unrelated",
             "html_url":"https://github.com/someone/unrelated",
             "description":"Mentions AIRI", "stargazers_count":60000, "fork":false},
            {"full_name":"proj-airi","name":"proj-airi",
             "html_url":"https://github.com/proj-airi", "stargazers_count":99999}
          ]
        }
    """.trimIndent()

    @Test fun repoSearchUsesPublicGithubApiWithoutSecrets() {
        assertTrue(WorkspaceGitHubRepositorySearch.supports(request))
        val built = WorkspaceGitHubRepositorySearch.searchRequest(request)
        assertEquals("GET", built.method)
        assertEquals("api.github.com", built.url.host)
        assertEquals("/search/repositories", built.url.encodedPath)
        assertTrue(built.url.queryParameter("q").orEmpty().contains("airi in:name"))
        assertTrue(built.url.queryParameter("q").orEmpty().contains("fork:false"))
        assertEquals(null, built.header("Authorization"))
        assertEquals(null, built.header("Cookie"))
    }

    @Test fun picksActualRepositoryInsteadOfGithubProfileOrUnrelatedRepo() {
        val results = WorkspaceGitHubRepositorySearch.parseJson(payload, request)
        assertEquals("https://github.com/moeru-ai/airi", results.first().url)
        assertEquals(2, results.size)
        assertTrue(results.first().snippet.contains("Self-hosted AI companion"))
    }

    @Test fun regularGithubProfileQueriesDoNotHijackRepoSearch() {
        assertFalse(WorkspaceGitHubRepositorySearch.supports(
            WorkspaceWebLinkIntent.Request("AIRI profile", "github.com")
        ))
    }

    @Test fun invalidOrNonRepositoryApiResultsFailClosed() {
        val json = """
          {"items":[
            {"name":"airi","full_name":"fake/airi","html_url":"https://github.com/fake"},
            {"name":"airi","full_name":"fake/airi","html_url":"https://example.org/fake/airi"}
          ]}
        """.trimIndent()
        assertTrue(WorkspaceGitHubRepositorySearch.parseJson(json, request).isEmpty())
    }
}
