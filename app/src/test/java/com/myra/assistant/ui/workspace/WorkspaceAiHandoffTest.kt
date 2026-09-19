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
        assertTrue(draft.prompt.contains("COPY oldText literally from the source JSON string"))
        assertTrue(draft.prompt.contains("Output exactly ONE compact JSON object"))
        assertTrue(draft.prompt.contains("do not assume unseen text"))
        assertFalse(draft.prompt.contains("exact unique existing text"))
        assertFalse(draft.prompt.contains("Current user follow-up"))
        assertTrue("Instruction envelope should stay small", draft.prompt.length <= 3_200)
        assertFalse(draft.prompt.contains("x".repeat(1600)))
        assertEquals(source, s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        assertTrue(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", draft))
    }

    @Test fun followUpIsBoundedQuotedAndStillRequiresApprovedCurrentFile() {
        val s = fixture()
        val followUp = "  Make  the heading say \"Welcome\"  "
        val draft = WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html", followUp)
        assertEquals("Make the heading say \"Welcome\"", draft.followUp)
        assertTrue(draft.prompt.contains("Current user follow-up for THIS one edit (JSON string): " +
            JSONObject.quote(draft.followUp)))
        assertTrue(draft.prompt.contains("Follow-up narrows the approved goal only"))
        assertTrue(draft.prompt.contains("Acceptance criteria (JSON string): \"Heading updated\""))
        assertTrue(draft.prompt.contains("Untrusted source text (JSON string): " + JSONObject.quote(draft.context.sourceExcerpt)))
        assertTrue(draft.prompt.length <= 3_600)
        assertEquals("<h1>Hello</h1>\n", s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        assertTrue(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", draft))
        s.files.saveFile("site", "index.html", "<h1>Newer work</h1>\n")
        assertFalse(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", draft))
    }

    @Test fun unsafeAndOversizeFollowUpAreRefusedBeforeSharing() {
        val s = fixture()
        for (note in listOf("x".repeat(181), "api_key = not_a_real_key", "sk-abcdefghijklmnopq", "Line one\nLine two")) {
            assertTrue("Unsafe follow-up must fail: $note", runCatching {
                WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html", note)
            }.isFailure)
        }
        assertEquals("<h1>Hello</h1>\n", s.files.readFile("site", "index.html"))
        assertEquals("", WorkspaceAiHandoff.normalizeFollowUp("   "))
    }

    @Test fun changedSourceAndRevokedOrPausedTaskInvalidatePrompt() {
        val s = fixture()
        val first = WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html")
        s.files.saveFile("site", "index.html", "<h1>Later</h1>\n")
        assertFalse(WorkspaceAiHandoff.stillCurrent(s.files, s.tasks, s.projects, "site", first))
        val second = WorkspaceAiHandoff.prepare(s.files, s.tasks, s.projects, "site", "index.html", "Make heading shorter")
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
            WorkspaceAiHandoff.prepare(clean.files, clean.tasks, clean.projects, "site", "index.html", "Change heading again")
        }.isFailure)
    }
}
