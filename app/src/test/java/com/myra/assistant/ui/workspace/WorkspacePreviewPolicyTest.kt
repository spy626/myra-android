package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspacePreviewPolicyTest {
    @Test fun permitsOnlySupportedLocalAssets() {
        assertEquals("index.html", WorkspacePreviewPolicy.route("/"))
        assertEquals("index.html", WorkspacePreviewPolicy.route("/index.html?refresh=123"))
        assertEquals("assets/style.css", WorkspacePreviewPolicy.route("/assets/style.css"))
        assertEquals("pages/index.html", WorkspacePreviewPolicy.route("/pages/"))
        assertEquals("assets/logo.png", WorkspacePreviewPolicy.route("/assets/logo.png"))
        assertEquals("text/css; charset=utf-8", WorkspacePreviewPolicy.mime("style.css"))
        assertEquals("my+file.js", WorkspacePreviewPolicy.route("/my+file.js"))
    }

    @Test fun refusesTraversalProtectedMetadataRemoteUrlsAndUnsupportedAssets() {
        listOf(
            "/../index.html", "/%2e%2e/index.html", "/assets/%2findex.html",
            "/assets/%5cindex.html", "/.lyra/project.json", "/assets/.lyra/project.html",
            "/.lyra-write-x.js", "/secrets.env", "/data.json", "//elsewhere/index.html",
            "https://example.com/index.html", "/bad%25name.html", "/a:b.html",
        ).forEach { assertNull("Rejected: $it", WorkspacePreviewPolicy.route(it)) }
    }

    @Test fun hostMustBeExactLoopbackAndPort() {
        assertTrue(WorkspacePreviewPolicy.validHost("127.0.0.1:45555", 45555))
        assertFalse(WorkspacePreviewPolicy.validHost("evil.example:45555", 45555))
        assertFalse(WorkspacePreviewPolicy.validHost("127.0.0.1:1234", 45555))
        assertFalse(WorkspacePreviewPolicy.validHost(null, 45555))
    }
}
