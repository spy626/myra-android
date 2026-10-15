package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No live paid search, API keys, login, test-network access or extra coordinator. */
class WorkspaceFreeSearxSearchTest {
    private val research = WorkspaceWebLinkIntent.Request(
        "Android iOS battery optimization", maxResults = 3,
    )

    @Test fun onlySafePublicResearchGoesToFreeSearxngEndpoint() {
        val request = WorkspaceFreeSearxSearch.request(research)
        assertEquals("GET", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("searx.tiekoetter.com", request.url.host)
        assertEquals("/search", request.url.encodedPath)
        assertTrue(request.url.encodedQuery.orEmpty().contains("format=json"))
        assertTrue(request.url.encodedQuery.orEmpty().contains("safesearch=1"))
        assertTrue(request.url.query.orEmpty().contains("Android iOS battery"))
        assertFalse(request.url.encodedQuery.orEmpty().contains("%2520"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
    }

    @Test fun parsesMultipleRelevantPublishersWithoutTrustingTheirSnippets() {
        val json = """
        {"results":[
          {"title":"Android battery optimization official guide",
           "url":"https://developer.android.com/topic/performance/power",
           "content":"Android battery performance details"},
          {"title":"iOS battery optimization on iPhone",
           "url":"https://support.apple.com/en-us/101604",
           "content":"Apple battery-saving support"},
          {"title":"Android battery optimization discussion",
           "url":"https://batteryspotlight.com/android-battery",
           "content":"Tips from an independent site"}
        ]}
        """.trimIndent()
        val parsed = WorkspaceFreeSearxSearch.parseJson(json, research)
        assertEquals(3, parsed.size)
        assertTrue(parsed.any { it.url.startsWith("https://developer.android.com/") })
        assertTrue(parsed.any { it.url.startsWith("https://support.apple.com/") })
        assertTrue(parsed.any { it.url.startsWith("https://batteryspotlight.com/") })
        assertTrue(parsed.all { it.title.isNotBlank() && it.score > 0 })
    }

    @Test fun rejectsHttpLocalAuthPaymentCredentialAndIrrelevantDestinations() {
        val json = """
        {"results":[
          {"title":"Android battery optimization","url":"http://example.org/android-battery"},
          {"title":"Android battery optimization","url":"https://localhost/android-battery"},
          {"title":"Android battery optimization","url":"https://example.com/login/android-battery"},
          {"title":"Android battery optimization","url":"https://example.com/checkout/android-battery"},
          {"title":"Android battery optimization","url":"https://example.com/android-battery?access_token=secret"},
          {"title":"Unrelated topic","url":"https://example.org/unrelated"},
          {"title":"Android battery optimization","url":"https://example.org/android-battery"}
        ]}
        """.trimIndent()
        val parsed = WorkspaceFreeSearxSearch.parseJson(json, research)
        assertEquals(listOf("https://example.org/android-battery"),
            parsed.map { it.url })
    }

    @Test fun snippetCanAdmitTopicalPublisherPageWithGenericTitle() {
        val json = """
        {"results":[
          {"title":"Use Low Power Mode on your iPhone",
           "url":"https://support.apple.com/en-us/101604",
           "content":"Improve battery life by limiting background energy usage."},
          {"title":"Totally unrelated documentation",
           "url":"https://example.org/unrelated",
           "content":"A general programming guide."}
        ]}
        """.trimIndent()
        val parsed = WorkspaceFreeSearxSearch.parseJson(
            json, WorkspaceWebLinkIntent.Request("iOS battery optimization", maxResults = 3))
        assertEquals(1, parsed.size)
        assertEquals("https://support.apple.com/en-us/101604", parsed.single().url)
    }

    @Test fun preferredHostKeepsPublicSearchResultsScoped() {
        val scoped = WorkspaceWebLinkIntent.Request(
            "iOS battery optimization", preferredHost = "support.apple.com",
            maxResults = 3)
        val request = WorkspaceFreeSearxSearch.request(scoped)
        assertTrue(request.url.query.orEmpty().contains("site:support.apple.com"))
        val json = """
        {"results":[
          {"title":"iOS battery optimization","url":"https://random.example.org/ios-battery"},
          {"title":"iOS battery optimization support",
           "url":"https://support.apple.com/en-us/101604"}
        ]}
        """.trimIndent()
        assertEquals(listOf("https://support.apple.com/en-us/101604"),
            WorkspaceFreeSearxSearch.parseJson(json, scoped).map { it.url })
    }

    private fun response(code: Int, type: String, body: String): Response =
        Response.Builder()
            .request(WorkspaceFreeSearxSearch.request(research))
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Blocked")
            .header("Content-Type", type)
            .body(body.toResponseBody())
            .build()

    @Test fun jsonResponseIsReadButForbiddenAndHtmlAreRejectedForDuckduckgoFallback() {
        val payload = """{"results":[
          {"title":"Android battery optimization",
           "url":"https://developer.android.com/topic/performance/power"}]}"""
        val accepted = response(200, "application/json", payload).use {
            WorkspaceFreeSearxSearch.read(it, research)
        }
        assertEquals(1, accepted.size)

        for ((code, type) in listOf(
            403 to "text/html", 429 to "application/json", 200 to "text/html"
        )) {
            val rejected = runCatching {
                response(code, type, payload).use { WorkspaceFreeSearxSearch.read(it, research) }
            }
            assertTrue("Unavailable JSON must fall back, not enter evidence", rejected.isFailure)
        }
        assertTrue(runCatching {
            WorkspaceFreeSearxSearch.parseJson("not JSON", research)
        }.isFailure)
    }

    @Test fun existingDuckduckgoIsStillAvailableWithoutMetaSearch() {
        val ddg = WorkspacePublicWebSearch.searchRequest(research)
        assertEquals("html.duckduckgo.com", ddg.url.host)
        assertEquals("GET", ddg.method)
        assertNull(ddg.header("Authorization"))
    }
}
