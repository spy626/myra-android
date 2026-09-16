package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI

class WorkspacePreviewServerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun servesOnlySavedWebsiteAssetsOnLoopback() {
        val store = WorkspaceProjectStore(temp.newFolder("projects"))
        val project = store.createProject("Preview test", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(store)
        files.addWebsiteStarter(project.projectId)
        val preview = WorkspacePreviewServer(store, project.projectId)
        try {
            val url = URI(preview.start())
            assertEquals("127.0.0.1", url.host)
            assertTrue(url.port > 0)
            assertTrue(get(url, "index.html").contains("200 OK"))
            assertTrue(get(url, "index.html").contains("Hello, Workspace!"))
            assertTrue(get(url, "style.css").contains("text/css"))
            assertTrue(get(url, "script.js").contains("application/javascript"))
            assertTrue(get(url, ".lyra/project.json").contains("404 Not Found"))
            assertTrue(get(url, "%2e%2e/.lyra/project.json").contains("404 Not Found"))
            assertTrue(get(url, "index.html", host = "evil.example:${url.port}").contains("403 Forbidden"))
            assertTrue(get(url, "index.html", method = "POST").contains("405 Method Not Allowed"))
            files.delete(project.projectId, "style.css")
            assertTrue(get(url, "style.css").contains("404 Not Found"))
        } finally {
            preview.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun androidProjectCannotRunWebsitePreview() {
        val store = WorkspaceProjectStore(temp.newFolder("other-projects"))
        val project = store.createProject("Native app", WorkspaceProjectType.ANDROID_APP)
        WorkspacePreviewServer(store, project.projectId).use { it.start() }
    }

    private fun get(url: URI, path: String, host: String = "127.0.0.1:${url.port}",
                    method: String = "GET"): String {
        val requestPath = url.path + path
        Socket(InetAddress.getByName("127.0.0.1"), url.port).use { client ->
            client.soTimeout = 5000
            client.getOutputStream().write(
                "$method $requestPath HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            return client.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
}
