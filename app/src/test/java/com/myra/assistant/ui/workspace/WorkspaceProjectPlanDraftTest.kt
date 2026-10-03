package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceProjectPlanDraftTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Setup(
        val projects: WorkspaceProjectStore,
        val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore,
        val context: WorkspaceSourceContext.Draft,
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
        return Setup(projects, tasks, files,
            WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html"))
    }

    @Test fun approvedFreshContextProducesBoundedTemplateWithoutWritesOrClaims() {
        val s = setup()
        val taskFile = File(s.projects.projectRoot("site"), ".lyra/task.json")
        val originalTask = taskFile.readBytes()
        val originalSource = s.files.readFile("site", "index.html")
        val plan = WorkspaceProjectPlanDraft.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, s.context)
        assertEquals("site", plan.projectId)
        assertEquals("index.html", plan.sourcePath)
        assertEquals(s.context.fileSha256, plan.sourceSha256)
        assertEquals(WorkspaceTaskContract.steps(WorkspaceProjectType.WEBSITE), plan.steps)
        assertEquals(listOf("inspect", "specify", "implement", "verify"), plan.steps.map { it.id })
        assertTrue(plan.displayText().contains("FOR REVIEW ONLY"))
        assertTrue(plan.displayText().contains("NOT an AI-generated solution"))
        assertTrue(plan.displayText().contains("Other affected files: unknown"))
        assertTrue(plan.displayText().contains("Separate action approval required"))
        assertFalse(plan.displayText().contains("original-tail"))
        assertArrayEquals(originalTask, taskFile.readBytes())
        assertEquals(originalSource, s.files.readFile("site", "index.html"))
    }

    @Test fun sourceChangeBeyondExcerptAndNewSecretBlockPlanning() {
        val s = setup()
        s.files.saveFile("site", "index.html", "a".repeat(1500) + "changed-tail")
        assertTrue(runCatching {
            WorkspaceProjectPlanDraft.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, s.context)
        }.isFailure)
        s.files.saveFile("site", "index.html", "a".repeat(1500) + "\nconst API_KEY = \"FAKE_TEST_ONLY_123\";")
        assertTrue(runCatching {
            WorkspaceProjectPlanDraft.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, s.context)
        }.isFailure)
    }

    @Test fun pausedRevokedChangedSpecOrWrongProjectBlocksPlanning() {
        val s = setup()
        s.tasks.setPaused("site", true)
        assertTrue(runCatching {
            WorkspaceProjectPlanDraft.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, s.context)
        }.isFailure)
        s.tasks.setPaused("site", false)
        val saved = s.tasks.get("site")!!
        s.tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), false)
        assertTrue(runCatching {
            WorkspaceProjectPlanDraft.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, s.context)
        }.isFailure)
        s.tasks.updateAcceptanceCriteria("site", "Different requirements")
        assertTrue(runCatching {
            WorkspaceProjectPlanDraft.prepare(s.files, s.tasks, "site", WorkspaceProjectType.WEBSITE, s.context)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceProjectPlanDraft.prepare(s.files, s.tasks, "other", WorkspaceProjectType.WEBSITE, s.context)
        }.isFailure)
    }
}
