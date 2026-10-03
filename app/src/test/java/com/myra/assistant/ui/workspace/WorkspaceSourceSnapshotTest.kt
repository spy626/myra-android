package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ArrayDeque

class WorkspaceSourceSnapshotTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun snapshotFingerprintsWholeFileAndChangesBeyondPreviewLimit() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val savedTask = tasks.create("site", "Build site", rawAcceptanceCriteria = "Works on phone")
        val taskFile = File(projects.projectRoot("site"), ".lyra/task.json")
        val beforeTaskBytes = taskFile.readBytes()
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        val original = "a".repeat(WorkspaceSourcePreview.MAX_PREVIEW_CHARS) + "original-tail"
        files.saveFile("site", "index.html", original)

        val first = WorkspaceSourcePreview.read(files, "site", "index.html", nowMillis = { 1_700_000_000_000L })
        assertEquals("site", first.projectId)
        assertEquals("index.html", first.path)
        assertEquals(64, first.fullFileSha256.length)
        assertTrue(first.fullFileSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.truncated)
        assertEquals(original.take(1500), first.excerpt)
        assertTrue(first.displayText().contains("Full-file SHA-256: ${first.fullFileSha256}"))
        assertTrue(first.displayText().contains("Local read: 2023-11-14 22:13:20 UTC"))
        assertTrue(first.displayText().contains("not a live freshness check"))
        assertEquals(WorkspaceSourcePreview.ContentFreshness.SAME_CONTENT,
            WorkspaceSourcePreview.compareCurrentContent(files, "site", first))

        val changed = "a".repeat(WorkspaceSourcePreview.MAX_PREVIEW_CHARS) + "changed-tail"
        files.saveFile("site", "index.html", changed)
        val second = WorkspaceSourcePreview.read(files, "site", "index.html", nowMillis = { 1_700_000_100_000L })
        assertEquals(first.excerpt, second.excerpt)
        assertNotEquals(first.fullFileSha256, second.fullFileSha256)
        assertEquals(WorkspaceSourcePreview.ContentFreshness.CHANGED_CONTENT,
            WorkspaceSourcePreview.compareCurrentContent(files, "site", first))
        assertEquals(WorkspaceSourcePreview.ContentFreshness.SAME_CONTENT,
            WorkspaceSourcePreview.compareCurrentContent(files, "site", second))
        assertArrayEquals(beforeTaskBytes, taskFile.readBytes())
        assertEquals(savedTask.taskId, tasks.get("site")!!.taskId)
        assertFalse(WorkspaceTaskContract.isSpecApproved(tasks.get("site")!!))
    }

    @Test fun missingOrCrossProjectSourcesNeverReportSameContent() {
        val ids = ArrayDeque(listOf("site", "other"))
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { ids.removeFirst() })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        projects.createProject("Other", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "hello")
        files.createFile("other", "index.html")
        files.saveFile("other", "index.html", "hello")
        val captured = WorkspaceSourcePreview.read(files, "site", "index.html")
        assertEquals(WorkspaceSourcePreview.ContentFreshness.UNAVAILABLE,
            WorkspaceSourcePreview.compareCurrentContent(files, "other", captured))
        files.rename("site", "index.html", "renamed.html")
        assertEquals(WorkspaceSourcePreview.ContentFreshness.UNAVAILABLE,
            WorkspaceSourcePreview.compareCurrentContent(files, "site", captured))
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "hello\u0000binary")
        assertEquals(WorkspaceSourcePreview.ContentFreshness.UNAVAILABLE,
            WorkspaceSourcePreview.compareCurrentContent(files, "site", captured))
    }
}
