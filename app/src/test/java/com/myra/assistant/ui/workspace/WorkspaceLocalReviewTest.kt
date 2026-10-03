package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceLocalReviewTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Setup(
        val projects: WorkspaceProjectStore,
        val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore,
        val approved: WorkspaceTask,
    )

    private fun setup(): Setup {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val saved = tasks.create("site", "Improve homepage", rawAcceptanceCriteria = "Fits on phone")
        val approved = tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "a".repeat(1500) + "original-tail")
        return Setup(projects, tasks, files, approved)
    }

    private fun review(s: Setup, path: String = "index.html", expected: WorkspaceTask = s.approved) =
        WorkspaceLocalReview.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, expected, path)

    @Test fun oneLocalActionBuildsMatchingContextAndPlanWithoutWriting() {
        val s = setup()
        val taskJson = File(s.projects.projectRoot("site"), ".lyra/task.json")
        val originalTask = taskJson.readBytes()
        val originalSource = s.files.readFile("site", "index.html")
        val result = review(s)
        assertEquals("index.html", result.context.path)
        assertEquals(result.context.fileSha256, result.plan.sourceSha256)
        assertEquals(result.context.specToken, result.plan.specToken)
        assertEquals(result.context.taskId, result.plan.taskId)
        assertEquals("Improve homepage", result.plan.goal)
        assertTrue(result.plan.displayText().contains("FOR REVIEW ONLY"))
        assertTrue(result.context.displayText().contains("NOT SENT"))
        assertFalse(result.plan.displayText().contains("original-tail"))
        assertArrayEquals(originalTask, taskJson.readBytes())
        assertEquals(originalSource, s.files.readFile("site", "index.html"))
    }

    @Test fun sourceAndSpecChangesRequireFreshPreparation() {
        val s = setup()
        val first = review(s)
        s.files.saveFile("site", "index.html", "a".repeat(1500) + "changed-tail")
        assertNotEquals(first.context.fileSha256, review(s).context.fileSha256)
        s.tasks.updateAcceptanceCriteria("site", "New requirement")
        assertTrue(runCatching { review(s) }.isFailure)
    }

    @Test fun rejectsSensitiveFilePausedRevokedOrWrongProject() {
        val s = setup()
        s.files.createFile("site", "privacy-check.js")
        s.files.saveFile("site", "privacy-check.js", "const API_KEY = \"FAKE_TEST_ONLY_123\";")
        assertTrue(runCatching { review(s, "privacy-check.js") }.isFailure)
        s.tasks.setPaused("site", true)
        assertTrue(runCatching { review(s) }.isFailure)
        s.tasks.setPaused("site", false)
        s.tasks.setSpecificationApproved("site", s.approved.taskId,
            WorkspaceTaskContract.specToken(s.approved), false)
        assertTrue(runCatching { review(s) }.isFailure)
        assertTrue(runCatching {
            WorkspaceLocalReview.prepare(s.files, s.tasks, "other", WorkspaceProjectType.WEBSITE,
                s.approved, "index.html")
        }.isFailure)
    }
}
