package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspacePlanReviewNoteTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Setup(
        val projects: WorkspaceProjectStore,
        val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore,
        val context: WorkspaceSourceContext.Draft,
        val plan: WorkspaceProjectPlanDraft.Draft,
    )

    private fun setup(): Setup {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val task = tasks.create("site", "Improve homepage", rawAcceptanceCriteria = "Fits on a phone")
        val approved = tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "a".repeat(1500) + "original tail")
        val context = WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html")
        val plan = WorkspaceProjectPlanDraft.prepare(files, tasks, "site", WorkspaceProjectType.WEBSITE, context)
        return Setup(projects, tasks, files, context, plan)
    }

    private fun review(s: Setup, text: String, plan: WorkspaceProjectPlanDraft.Draft = s.plan) =
        WorkspacePlanReviewNote.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, s.context, plan, text)

    @Test fun aReviewNoteIsBoundedAndDoesNotWriteOrClaimApproval() {
        val s = setup()
        val taskFile = File(s.projects.projectRoot("site"), ".lyra/task.json")
        val before = taskFile.readBytes()
        val sourceBefore = s.files.readFile("site", "index.html")
        val note = review(s, "  Change only   the hero title  ")
        assertEquals("Change only the hero title", note.note)
        assertEquals(s.plan.specToken, note.specToken)
        assertEquals(s.plan.sourceSha256, note.sourceSha256)
        assertEquals("index.html", note.sourcePath)
        assertTrue(note.displayText().contains("SCREEN ONLY / NOT SAVED"))
        assertTrue(note.displayText().contains("No AI request, plan approval, file edit"))
        assertFalse(note.displayText().contains("original tail"))
        assertArrayEquals(before, taskFile.readBytes())
        assertEquals(sourceBefore, s.files.readFile("site", "index.html"))
    }

    @Test fun blankTooLongAndPotentialSecretNotesAreRejected() {
        val s = setup()
        listOf(" ", "x".repeat(501), "const API_KEY = FAKE_TEST_ONLY_123", "sk-abcDEF1234567890").forEach {
            assertTrue(it.take(30), runCatching { review(s, it) }.isFailure)
        }
        assertEquals(500, review(s, "x".repeat(500)).note.length)
    }

    @Test fun changedTailRevokedApprovalPauseAndMismatchedPlanFailClosed() {
        val s = setup()
        val different = s.plan.copy(sourceSha256 = "0".repeat(64))
        assertTrue(runCatching { review(s, "Only title", different) }.isFailure)
        s.files.saveFile("site", "index.html", "a".repeat(1500) + "changed tail")
        assertTrue(runCatching { review(s, "Only title") }.isFailure)
        s.files.saveFile("site", "index.html", "a".repeat(1500) + "original tail")
        s.tasks.setPaused("site", true)
        assertTrue(runCatching { review(s, "Only title") }.isFailure)
        s.tasks.setPaused("site", false)
        val task = s.tasks.get("site")!!
        s.tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), false)
        assertTrue(runCatching { review(s, "Only title") }.isFailure)
    }
}
