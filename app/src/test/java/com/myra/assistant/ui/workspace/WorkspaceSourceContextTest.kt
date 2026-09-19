package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ArrayDeque

class WorkspaceSourceContextTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun explicitlySelectedApprovedContextIsLocalBoundedAndDoesNotWrite() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val saved = tasks.create("site", "Improve homepage", rawAcceptanceCriteria = "Works on phone")
        val approved = tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        val taskFile = File(projects.projectRoot("site"), ".lyra/task.json")
        val taskBefore = taskFile.readBytes()
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        val fullText = "<main>hello</main>" + "x".repeat(1600)
        files.saveFile("site", "index.html", fullText)
        val fileBefore = files.readFile("site", "index.html")
        val draft = WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html") { 123456L }
        assertEquals("site", draft.projectId)
        assertEquals(saved.taskId, draft.taskId)
        assertEquals("index.html", draft.path)
        assertEquals(64, draft.fileSha256.length)
        assertEquals(1500, draft.sourceExcerpt.length)
        assertTrue(draft.truncated)
        assertEquals(123456L, draft.observedAtMs)
        assertTrue(draft.displayText().contains("NOT SENT"))
        assertTrue(draft.displayText().contains("UNTRUSTED SOURCE TEXT"))
        assertTrue(draft.displayText().contains("No model upload"))
        assertEquals(fileBefore, files.readFile("site", "index.html"))
        assertArrayEquals(taskBefore, taskFile.readBytes())
        assertTrue(WorkspaceTaskContract.isSpecApproved(tasks.get("site")!!))
    }

    @Test fun missingApprovalPausedOrStaleSpecCannotPrepareContext() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val saved = tasks.create("site", "Build site", rawAcceptanceCriteria = "Looks correct")
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "<p>Ok</p>")
        assertTrue(runCatching { WorkspaceSourceContext.prepare(files, tasks, "site", saved, "index.html") }.isFailure)
        val approved = tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        tasks.setPaused("site", true)
        assertTrue(runCatching { WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html") }.isFailure)
        tasks.setPaused("site", false)
        assertEquals("index.html", WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html").path)
        tasks.updateAcceptanceCriteria("site", "Changed criteria")
        assertTrue(runCatching { WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html") }.isFailure)
    }

    @Test fun fullFileSecretScanBlocksValuesOutsideVisibleExcerptWithoutLeakingThem() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val saved = tasks.create("site", "Build site", rawAcceptanceCriteria = "Looks correct")
        val approved = tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        val secret = "sk-abcdefgh123456789XYZ"
        files.saveFile("site", "index.html", "x".repeat(1500) + "\nconst apiKey = '$secret'")
        val error = runCatching { WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html") }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains(secret))
        assertTrue(error.message.orEmpty().contains("blocked"))
        files.saveFile("site", "index.html", "<p>No secrets</p>")
        assertNotNull(WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html"))
        files.saveFile("site", "index.html", "-----BEGIN PRIVATE KEY-----\nnot really\n")
        assertTrue(runCatching { WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html") }.isFailure)
    }

    @Test fun hiddenSensitiveCrossProjectAndRemovedFilesCannotBeProjected() {
        val ids = ArrayDeque(listOf("site", "other"))
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { ids.removeFirst() })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        projects.createProject("Other", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val saved = tasks.create("site", "Build site", rawAcceptanceCriteria = "Looks correct")
        val approved = tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "safe")
        files.createFile("site", ".env")
        files.createFile("site", "credentials.json")
        files.createFile("other", "private.html")
        assertEquals(listOf("index.html"), WorkspaceSourceContext.choices(files, "site"))
        listOf(".env", "credentials.json", "../other/private.html", "private.html").forEach {
            assertTrue(it, runCatching { WorkspaceSourceContext.prepare(files, tasks, "site", approved, it) }.isFailure)
        }
        files.delete("site", "index.html")
        assertTrue(runCatching { WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html") }.isFailure)
    }
}
