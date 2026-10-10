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

    @Test fun actualHinglishPromptProducesExactGitHubQueryAndRejectsSubstringTrap() {
        val fullRequest = requireNotNull(
            WorkspaceWebLinkIntent.decide("GitHub pe AIRI repo ka link do")
        )
        assertEquals("AIRI repo", fullRequest.query)
        val built = WorkspaceGitHubRepositorySearch.searchRequest(fullRequest)
        assertEquals("airi in:name fork:false", built.url.queryParameter("q"))
        val apiResults = """
            {"items":[
               {"full_name":"Shottakon/AirialPerspectiveEffecter",
                "name":"AirialPerspectiveEffecter",
                "html_url":"https://github.com/Shottakon/AirialPerspectiveEffecter",
                "stargazers_count":900000, "fork":false},
               {"full_name":"moeru-ai/airi", "name":"airi",
                "html_url":"https://github.com/moeru-ai/airi",
                "description":"AI companion",
                "stargazers_count":50000,"fork":false}
            ]}
        """.trimIndent()
        val candidates = WorkspaceGitHubRepositorySearch.parseJson(apiResults, fullRequest)
        assertEquals(listOf("https://github.com/moeru-ai/airi"),
            candidates.map { it.url })
    }

    @Test fun similarButDifferentRepositoryNamesAreNotMistakenAsExact() {
        assertFalse(WorkspaceGitHubRepositorySearch.nameMatchesQuery(
            "https://github.com/Shottakon/AirialPerspectiveEffecter", "AIRI repo"
        ))
        assertTrue(WorkspaceGitHubRepositorySearch.nameMatchesQuery(
            "https://github.com/moeru-ai/airi", "AIRI repo"
        ))
        assertTrue(WorkspaceGitHubRepositorySearch.nameMatchesQuery(
            "https://github.com/team/airi-tools", "AIRI repo"
        ))
        assertTrue(WorkspaceGitHubRepositorySearch.nameMatchesQuery(
            "https://github.com/team/super.pe", "super.pe repo"
        ))
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
