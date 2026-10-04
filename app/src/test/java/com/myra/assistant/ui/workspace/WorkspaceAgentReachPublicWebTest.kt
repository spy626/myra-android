package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachPublicWebTest {
    private fun target(url: String = "https://example.com/guide") =
        WorkspaceAgentReachPolicy.parse(url)
    private fun response(url: String, type: String, body: String, code: Int = 200) =
        Response.Builder().request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", type).body(body.toResponseBody()).build()

    @Test fun readUsesNoAuthNoCookiesAndExistingGuardedNetwork() {
        val req = WorkspaceAgentReachPublicWeb.request(target())
        assertEquals("GET", req.method)
        assertNull(req.header("Authorization"))
        assertNull(req.header("Cookie"))
        assertFalse(WorkspaceAgentReachGitHub.client.followRedirects)
        assertFalse(WorkspaceAgentReachGitHub.client.retryOnConnectionFailure)
        assertTrue(runCatching {
            WorkspaceAgentReachPublicWeb.request(target("https://github.com/a/b"))
        }.isFailure)
    }

    @Test fun extractsSafeStaticOutlineAndOnlySameHostUnfollowedLinks() {
        val t = target()
        val html = "<html><head><title>Help &amp; Guide</title><script>leak()</script></head>" +
            "<body><h1>Installation</h1><p>Read this &amp; continue.</p>" +
            "<script>secretThings()</script><form><input value='private'></form>" +
            "<a href='/next'>Next</a><a href='https://evil.example/path'>Offsite</a></body></html>"
        val page = WorkspaceAgentReachPublicWeb.read(
            response(t.canonicalUrl, "text/html; charset=utf-8", html), t, t, 1)
        assertEquals("Help & Guide", page.title)
        assertTrue(page.headings.contains("Installation"))
        assertTrue(page.excerpt.contains("Read this & continue"))
        assertFalse(page.excerpt.contains("secretThings"))
        assertFalse(page.excerpt.contains("leak()"))
        assertFalse(page.excerpt.contains("private"))
        assertTrue(page.suggestedLinks.contains("https://example.com/next"))
        assertFalse(page.suggestedLinks.any { it.contains("evil.example") })
        assertEquals("public-html-static", page.evidence.provenance.adapter)
        assertEquals(64, page.evidence.provenance.contentSha256.length)
    }

    @Test fun redirectsAreSameHostHttpsOnly() {
        val t = target()
        assertEquals("https://example.com/next",
            WorkspaceAgentReachPublicWeb.redirect(t, "/next").canonicalUrl)
        listOf("https://other.example/next", "http://example.com/a",
            "https://127.0.0.1/admin", "https://169.254.169.254/").forEach {
            assertTrue(it, runCatching { WorkspaceAgentReachPublicWeb.redirect(t, it) }.isFailure)
        }
    }

    @Test fun rejectsBinaryOversizeWrongCharsetAndScriptOnlyPage() {
        val t = target()
        val cases = listOf(
            response(t.canonicalUrl, "application/pdf", "%PDF"),
            response(t.canonicalUrl, "text/html; charset=utf-8", "A".repeat(96_001)),
            response(t.canonicalUrl, "text/html; charset=iso-8859-1", "<p>Hello</p>"),
            response(t.canonicalUrl, "text/html", "<script>onlyJavaScript()</script>")
        )
        cases.forEach { response ->
            assertTrue(runCatching {
                WorkspaceAgentReachPublicWeb.read(response, t, t, 1)
            }.isFailure)
        }
    }
}
