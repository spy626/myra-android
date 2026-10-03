package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceStructuredEditTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Fixture(
        val projects: WorkspaceProjectStore,
        val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore,
    )

    private fun fixture(source: String = "<h1>Hello</h1>\n"): Fixture {
        val projects = WorkspaceProjectStore(temp.newFolder(), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_1" })
        val task = tasks.create("site", "Improve heading", rawAcceptanceCriteria = "Heading updated")
        tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", source)
        return Fixture(projects, tasks, files)
    }

    private fun json(path: String = "index.html", old: String = "Hello", new: String = "Welcome") =
        """{"schemaVersion":1,"operation":"replace_exact_once","path":"$path","oldText":"$old","newText":"$new","rationale":"matches the saved spec"}"""

    @Test fun validStructuredDraftIsReadOnlyUntilExistingSafeExecutorIsExplicitlyApplied() {
        val s = fixture()
        val before = s.files.readFile("site", "index.html")
        val draft = WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json())
        assertEquals("index.html", draft.proposal.path)
        assertEquals("Hello", draft.proposal.oldText)
        assertEquals(before, s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))

        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, draft.context, draft.proposal)
        assertTrue(s.files.readFile("site", "index.html").contains("Welcome"))
        assertNotNull(WorkspaceScopedEdit.pending(s.projects, "site"))
    }

    @Test fun targetAndSourceAreGroundedLocallyNotTrustedFromEnvelope() {
        val s = fixture()
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json("../outside.txt"))
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json(old = "Missing"))
        }.isFailure)
        assertEquals("<h1>Hello</h1>\n", s.files.readFile("site", "index.html"))
    }

    @Test fun ambiguousAndUnsupportedModelOutputFailsClosed() {
        val s = fixture("<h1>Hello Hello</h1>\n")
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json())
        }.isFailure)
        val extra = """{"schemaVersion":1,"operation":"replace_exact_once","path":"index.html","oldText":"Hello","newText":"Welcome","shell":"rm -rf /"}"""
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", extra)
        }.isFailure)
    }

    @Test fun concatenatedObjectsTrailingTextAndArrayAreRejectedBeforeAnyWrite() {
        val s = fixture()
        val original = s.files.readFile("site", "index.html")
        for (invalid in listOf(json() + "\n" + json(), json() + " trailing", "[" + json() + "]")) {
            val result = runCatching {
                WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", invalid)
            }
            assertTrue("Must reject extra document/trailing text: $invalid", result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("exactly one JSON object"))
        }
        assertEquals(original, s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        assertNotNull(WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", "\n " + json() + " \n"))
    }

    @Test fun pauseAndRevocationBlockImportedPatch() {
        val s = fixture()
        s.tasks.setPaused("site", true)
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json())
        }.isFailure)
        s.tasks.setPaused("site", false)
        val current = requireNotNull(s.tasks.get("site"))
        s.tasks.setSpecificationApproved("site", current.taskId, WorkspaceTaskContract.specToken(current), false)
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json())
        }.isFailure)
    }

    @Test fun possibleSecretAndPendingRollbackBlockNewStructuredPatch() {
        val secret = fixture("api_key = abcdefghijklmnop\nHello\n")
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(secret.files, secret.tasks, secret.projects, "site", json())
        }.isFailure)

        val s = fixture()
        val first = WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json())
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, first.context, first.proposal)
        assertTrue(runCatching {
            WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site",
                json(old = "Welcome", new = "Hi"))
        }.isFailure)
    }
}
