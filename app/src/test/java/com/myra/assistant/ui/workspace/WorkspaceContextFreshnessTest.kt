package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceContextFreshnessTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Setup(
        val projects: WorkspaceProjectStore,
        val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore,
        val draft: WorkspaceSourceContext.Draft,
    )

    private fun setup(): Setup {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        val task = tasks.create("site", "Improve homepage", rawAcceptanceCriteria = "Responsive on phone")
        val approved = tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "a".repeat(1500) + "unchanged-tail")
        val draft = WorkspaceSourceContext.prepare(files, tasks, "site", approved, "index.html")
        return Setup(projects, tasks, files, draft)
    }

    @Test fun identicalSpecAndFullFileRecheckWithoutWrites() {
        val s = setup()
        val taskFile = File(s.projects.projectRoot("site"), ".lyra/task.json")
        val before = taskFile.readBytes()
        val source = s.files.readFile("site", "index.html")
        assertEquals(WorkspaceContextFreshness.Result.SAME_CONTENT_AND_SPEC,
            WorkspaceContextFreshness.check(s.files, s.tasks, "site", s.draft))
        assertArrayEquals(before, taskFile.readBytes())
        assertEquals(source, s.files.readFile("site", "index.html"))
    }

    @Test fun changeOutsideExcerptAndDeletionInvalidateOldDraft() {
        val s = setup()
        s.files.saveFile("site", "index.html", "a".repeat(1500) + "edited-tail")
        assertEquals(WorkspaceContextFreshness.Result.STALE_OR_BLOCKED,
            WorkspaceContextFreshness.check(s.files, s.tasks, "site", s.draft))
        s.files.delete("site", "index.html")
        assertEquals(WorkspaceContextFreshness.Result.STALE_OR_BLOCKED,
            WorkspaceContextFreshness.check(s.files, s.tasks, "site", s.draft))
    }

    @Test fun pausedRevokedAndChangedSpecInvalidateWithoutLeakingText() {
        val s = setup()
        s.tasks.setPaused("site", true)
        assertEquals(WorkspaceContextFreshness.Result.STALE_OR_BLOCKED,
            WorkspaceContextFreshness.check(s.files, s.tasks, "site", s.draft))
        s.tasks.setPaused("site", false)
        val saved = s.tasks.get("site")!!
        s.tasks.setSpecificationApproved("site", saved.taskId, WorkspaceTaskContract.specToken(saved), false)
        assertEquals(WorkspaceContextFreshness.Result.STALE_OR_BLOCKED,
            WorkspaceContextFreshness.check(s.files, s.tasks, "site", s.draft))
        s.tasks.updateAcceptanceCriteria("site", "Changed")
        assertEquals(WorkspaceContextFreshness.Result.STALE_OR_BLOCKED,
            WorkspaceContextFreshness.check(s.files, s.tasks, "site", s.draft))
        assertEquals(WorkspaceContextFreshness.Result.STALE_OR_BLOCKED,
            WorkspaceContextFreshness.check(s.files, s.tasks, "other", s.draft))
    }

    @Test fun newlyIntroducedFakeKeyBeyondExcerptInvalidates() {
        val s = setup()
        s.files.saveFile("site", "index.html", "a".repeat(1500) + "\nconst API_KEY = \"FAKE_TEST_ONLY_123\";")
        assertEquals(WorkspaceContextFreshness.Result.STALE_OR_BLOCKED,
            WorkspaceContextFreshness.check(s.files, s.tasks, "site", s.draft))
    }
}
