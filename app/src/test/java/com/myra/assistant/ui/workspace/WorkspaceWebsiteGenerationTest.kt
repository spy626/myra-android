package com.myra.assistant.ui.workspace

import org.json.JSONObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceWebsiteGenerationTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Fixture(val projects: WorkspaceProjectStore, val files: WorkspaceFileStore,
                               val tasks: WorkspaceTaskStore)

    private fun fixture(): Fixture {
        val projects = WorkspaceProjectStore(temp.newFolder(), idFactory = { "site" })
        projects.createProject("Minicoy", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task" }, revisionFactory = { "rev" })
        val task = tasks.create("site", "Build a Minicoy landing page with a heading and button",
            rawAcceptanceCriteria = "Saved website files can be inspected in Preview")
        tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), true)
        return Fixture(projects, files, tasks)
    }

    private fun generated(title: String = "Welcome to Minicoy"): Map<String, String> = mapOf(
        "index.html" to "<!doctype html><html lang=\"en\"><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1>$title</h1><button id=\"explore\">Explore</button><script src=\"script.js\"></script></body></html>",
        "style.css" to "body { font-family: system-ui; background: #eef; }",
        "script.js" to "document.getElementById('explore').onclick = () => alert('Minicoy');",
    )

    private fun envelope(files: Map<String, String>): String =
        JSONObject().put("files", JSONObject(files)).toString()

    @Test fun emptyProjectIsNotFilledWithUnrelatedStarterBeforeModelCompletes() {
        val s = fixture()
        val before = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        assertTrue(before.original.values.all { it == null })
        assertTrue(s.files.list("site").isEmpty())
        assertNull(WorkspaceWebsiteGeneration.pending(s.projects, "site"))
    }

    @Test fun completeThreeFileJsonIsRequiredWithoutPartialWrites() {
        val s = fixture()
        val good = generated()
        assertEquals(good, WorkspaceWebsiteGeneration.parse(envelope(good)))
        assertEquals(good, WorkspaceWebsiteGeneration.parse("```json\n${envelope(good)}\n```"))
        for (bad in listOf("<html>not JSON</html>", "${envelope(good)} trailing",
            envelope(good - "script.js"), envelope(good + ("secret.env" to "oops")),
            envelope(good + ("index.html" to "not complete")),
            envelope(good + ("script.js" to "api_key = abcdefghijklmnop")))) {
            assertTrue("must reject incomplete or unsafe model reply", runCatching {
                WorkspaceWebsiteGeneration.parse(bad)
            }.isFailure)
        }
        assertTrue(s.files.list("site").isEmpty())
    }

    @Test fun oneSendWritesAllThreeFilesAndUndoRestoresOriginalState() {
        val s = fixture()
        val snapshot = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        val files = WorkspaceWebsiteGeneration.parse(envelope(generated()))
        WorkspaceWebsiteGeneration.apply(s.files, s.tasks, s.projects, snapshot, files)
        WorkspaceWebsiteGeneration.PATHS.forEach { assertEquals(files[it], s.files.readFile("site", it)) }
        assertNotNull(WorkspaceWebsiteGeneration.pending(s.projects, "site"))
        WorkspaceWebsiteGeneration.undo(s.files, s.projects, "site")
        assertNull(WorkspaceWebsiteGeneration.pending(s.projects, "site"))
        assertTrue(s.files.list("site").isEmpty())
    }

    @Test fun existingFilesArePreservedByUndoAndNextRequestNeedsNoKeepDialog() {
        val s = fixture()
        s.files.createFile("site", "index.html")
        s.files.saveFile("site", "index.html", "<html>Old page</html>")
        val first = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        WorkspaceWebsiteGeneration.apply(s.files, s.tasks, s.projects, first, generated())
        WorkspaceWebsiteGeneration.finishPreviousForNewRequest(s.files, s.projects, "site")
        assertNull(WorkspaceWebsiteGeneration.pending(s.projects, "site"))
        val next = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        WorkspaceWebsiteGeneration.apply(s.files, s.tasks, s.projects, next, generated("Updated Minicoy"))
        WorkspaceWebsiteGeneration.undo(s.files, s.projects, "site")
        assertEquals(generated()["index.html"], s.files.readFile("site", "index.html"))
    }

    @Test fun manualEditPreventsSilentOverwriteAndProtectsRollback() {
        val s = fixture()
        val snapshot = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        WorkspaceWebsiteGeneration.apply(s.files, s.tasks, s.projects, snapshot, generated())
        s.files.saveFile("site", "index.html", "<html>Manual work</html>")
        assertTrue(runCatching {
            WorkspaceWebsiteGeneration.finishPreviousForNewRequest(s.files, s.projects, "site")
        }.isFailure)
        assertTrue(runCatching { WorkspaceWebsiteGeneration.undo(s.files, s.projects, "site") }.isFailure)
        assertEquals("<html>Manual work</html>", s.files.readFile("site", "index.html"))
        assertNotNull(WorkspaceWebsiteGeneration.pending(s.projects, "site"))
    }

    @Test fun staleSourceCannotBeOverwrittenAfterModelRequest() {
        val s = fixture()
        val snapshot = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        s.files.createFile("site", "index.html")
        s.files.saveFile("site", "index.html", "New local edit")
        assertTrue(runCatching {
            WorkspaceWebsiteGeneration.apply(s.files, s.tasks, s.projects, snapshot, generated())
        }.isFailure)
        assertEquals("New local edit", s.files.readFile("site", "index.html"))
        assertNull(WorkspaceWebsiteGeneration.pending(s.projects, "site"))
    }

    @Test fun groqHttp400IsNotLabeledAsQuotaAndDoesNotSavePartialFiles() {
        val snapshot = fixture()
        val request = WorkspaceWebsiteGroqFallback.request("gsk_test_key",
            WorkspaceWebsiteGeneration.prepare(snapshot.files, snapshot.tasks, snapshot.projects, "site"))
        val response = okhttp3.Response.Builder().request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1).code(400).message("Bad Request").build()
        val failure = runCatching { WorkspaceWebsiteGeneration.readResponse(response) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("request or output format rejected"))
        assertTrue(failure.message.orEmpty().contains("not a quota"))
        assertTrue(snapshot.files.list("site").isEmpty())
    }

    @Test fun openRouter400ReportsSafeCategoryAndNeverWritesOrRetries() {
        val s = fixture()
        val snapshot = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        val request = WorkspaceWebsiteGeneration.request("safe_test_key", snapshot)
        val raw = """{"error":{"message":"response_format unsupported SECRET_DO_NOT_ECHO"}}"""
        val response = okhttp3.Response.Builder().request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1).code(400).message("Bad Request")
            .body(raw.toResponseBody()).build()
        val failure = runCatching { WorkspaceWebsiteGeneration.readResponse(response) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("OpenRouter Free HTTP 400: response format rejected"))
        assertFalse(failure.message.orEmpty().contains("SECRET_DO_NOT_ECHO"))
        assertTrue(s.files.list("site").isEmpty())
    }

    @Test fun providerRequestUsesOnlyFreeRouteWithoutPersonalMemory() {
        val s = fixture()
        val snapshot = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        val request = WorkspaceWebsiteGeneration.request("safe_test_key", snapshot)
        val buffer = okio.Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals("openrouter/free", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertFalse(body.getJSONObject("provider").optBoolean("require_parameters", false))
        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", body.getJSONObject("provider").getString("data_collection"))
        assertEquals(0, body.getJSONObject("provider").getJSONObject("max_price").getInt("prompt"))
        assertEquals(false, body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertEquals(2, body.getJSONArray("messages").length())
        assertTrue(body.getInt("max_tokens") > 2048)
    }
}
