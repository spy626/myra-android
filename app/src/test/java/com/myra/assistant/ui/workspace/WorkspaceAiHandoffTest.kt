package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceAiHandoffTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Fixture(val projects: WorkspaceProjectStore, val tasks: WorkspaceTaskStore,
                               val files: WorkspaceFileStore)

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

    @Test fun preparedHandoffQuotesBoundedSourceAndNeverWrites() {
        val source = "<h1>Hello</h1>\n" + "x".repeat(1700)
        val s = fixture(source)
        val draft = WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html")
        assertTrue(draft.context.truncated)
        assertEquals(1500, draft.context.sourceExcerpt.length)
        assertTrue(draft.prompt.contains("Improve heading"))
        assertTrue(draft.prompt.contains("Heading updated"))
        assertTrue(draft.prompt.contains("Untrusted source text (JSON string): " + JSONObject.quote(draft.context.sourceExcerpt)))
        assertFalse(draft.prompt.contains("x".repeat(1600)))
        assertEquals(source, s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        assertTrue(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", draft))
    }

    @Test fun changedSourceAndRevokedOrPausedTaskInvalidatePrompt() {
        val s = fixture()
        val first = WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html")
        s.files.saveFile("site", "index.html", "<h1>Later</h1>\n")
        assertFalse(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", first))
        val second = WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html")
        s.tasks.setPaused("site", true)
        assertFalse(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", second))
        assertTrue(runCatching {
            WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html")
        }.isFailure)
        s.tasks.setPaused("site", false)
        val task = requireNotNull(s.tasks.get("site"))
        s.tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), false)
        assertFalse(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", second))
    }

    @Test fun fullFileSecretUnsafePathAndPendingRollbackBlockHandoff() {
        val s = fixture("x".repeat(1550) + "\napi_key = abcdefghijklmnop\n")
        assertTrue(runCatching {
            WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "../outside.txt")
        }.isFailure)

        val clean = fixture("<h1>Hello</h1>\n")
        val context = WorkspaceSourceContext.prepare(clean.files, clean.tasks, "site",
            requireNotNull(clean.tasks.get("site")), "index.html")
        val patch = WorkspaceScopedEdit.propose(clean.files, clean.tasks, clean.projects, context, "Hello", "Welcome")
        WorkspaceScopedEdit.apply(clean.files, clean.tasks, clean.projects, context, patch)
        assertTrue(runCatching {
            WorkspaceAiHandoff.prepare(clean.files, clean.tasks, clean.projects, "site", "index.html")
        }.isFailure)
    }
}
