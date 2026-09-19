package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceScopedEditTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Fixture(
        val projects: WorkspaceProjectStore, val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore, val context: WorkspaceSourceContext.Draft,
    )

    private fun fixture(): Fixture {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Trial", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_1" })
        val saved = tasks.create("site", "Improve header", rawAcceptanceCriteria = "Header updated on phone")
        val approved = tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "<h1>Hello</h1>\n" + "x".repeat(1600))
        val context = WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html")
        return Fixture(projects, tasks, files, context)
    }

    @Test fun oneUniqueHumanApprovedLiteralEditPersistsBackupAndRestoresOriginalAcrossObjectReads() {
        val s = fixture()
        val original = s.files.readFile("site", "index.html")
        val proposal = WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Hello", "Welcome")
        assertEquals(original, s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        val backup = WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, s.context, proposal)
        assertEquals("index.html", backup.path)
        assertEquals(original.replace("Hello", "Welcome"), s.files.readFile("site", "index.html"))
        assertEquals(backup.afterSha256, WorkspaceScopedEdit.pending(s.projects, "site")?.afterSha256)
        assertTrue(WorkspaceScopedEdit.undo(s.files, s.projects, "site"))
        assertEquals(original, s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
    }

    @Test fun blocksAmbiguousEditAndChangeBeyondPreviewOrAfterPreview() {
        val s = fixture()
        assertTrue(runCatching {
            WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "xx", "yy")
        }.isFailure)
        val proposal = WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Hello", "Welcome")
        s.files.saveFile("site", "index.html", s.files.readFile("site", "index.html") + "tail")
        assertTrue(runCatching {
            WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, s.context, proposal)
        }.isFailure)
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        assertFalse(s.files.readFile("site", "index.html").contains("Welcome"))
    }

    @Test fun pauseRevocationAndCriteriaRevisionBlockEditButNotSafeUndo() {
        val s = fixture()
        val p = WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Hello", "Welcome")
        s.tasks.setPaused("site", true)
        assertTrue(runCatching { WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, s.context, p) }.isFailure)
        s.tasks.setPaused("site", false)
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, s.context, p)
        s.tasks.setSpecificationApproved("site", s.context.taskId, s.context.specToken, false)
        assertTrue(runCatching { WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Welcome", "Hi") }.isFailure)
        assertTrue(WorkspaceScopedEdit.undo(s.files, s.projects, "site"))
        s.tasks.updateAcceptanceCriteria("site", "Another goal")
        assertTrue(runCatching { WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, s.context, p) }.isFailure)
    }

    @Test fun undoRejectsInterveningManualSaveAndRetainsPrivateBackup() {
        val s = fixture()
        val p = WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Hello", "Welcome")
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, s.context, p)
        s.files.saveFile("site", "index.html", s.files.readFile("site", "index.html") + " manual edit")
        assertTrue(runCatching { WorkspaceScopedEdit.undo(s.files, s.projects, "site") }.isFailure)
        assertNotNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        assertTrue(s.files.readFile("site", "index.html").contains("manual edit"))
    }

    @Test fun keepRequiresUnchangedResultAndAllowsNextEdit() {
        val s = fixture()
        val p = WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Hello", "Welcome")
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, s.context, p)
        assertTrue(runCatching {
            WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Welcome", "Hi")
        }.isFailure)
        WorkspaceScopedEdit.keep(s.files, s.projects, "site")
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        val latest = requireNotNull(s.tasks.get("site"))
        val fresh = WorkspaceSourceContext.prepare(s.files, s.tasks, "site", latest, "index.html")
        assertEquals("Welcome", WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, fresh,
            "Welcome", "Hi").oldText)
        val metadata = File(s.projects.projectRoot("site"), ".lyra/scoped-edit-backup.json")
        assertFalse(metadata.exists())
    }

    @Test fun corruptedOrOtherProjectBackupNeverChangesSource() {
        val s = fixture()
        val metadata = File(s.projects.projectRoot("site"), ".lyra/scoped-edit-backup.json")
        metadata.writeText("not-json")
        val before = s.files.readFile("site", "index.html")
        assertTrue(runCatching { WorkspaceScopedEdit.undo(s.files, s.projects, "site") }.isFailure)
        assertTrue(runCatching { WorkspaceScopedEdit.propose(s.files, s.tasks, s.projects, s.context, "Hello", "Hi") }.isFailure)
        assertEquals(before, s.files.readFile("site", "index.html"))
    }
}
