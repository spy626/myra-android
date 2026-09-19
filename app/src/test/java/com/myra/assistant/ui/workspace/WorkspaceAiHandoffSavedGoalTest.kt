package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceAiHandoffSavedGoalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun longInitialWebsiteGoalUsesApprovedTaskInsteadOfDuplicatingFollowUp() {
        val rawGoal = "Build a simple Minicoy landing page.\n" +
            "Add a Welcome to Minicoy heading, a short introduction and an Explore Minicoy button. " +
            "Keep existing files and use English code and comments. " +
            "Do not remove anything that already works."
        assertTrue(rawGoal.length > 180)
        assertTrue(rawGoal.length <= WorkspaceTaskContract.MAX_GOAL_LENGTH)
        val projects = WorkspaceProjectStore(temp.newFolder(), idFactory = { "site" })
        projects.createProject("Minicoy", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", "<h1>Hello, Workspace!</h1>\n")
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_1" })
        val task = tasks.create("site", rawGoal, rawAcceptanceCriteria = "Page contains heading and button")
        tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), true)

        // The chat passes the same raw initial request through the coding path.
        val draft = WorkspaceAiHandoff.prepare(files, tasks, projects, "site", "index.html", rawGoal)
        assertEquals("", draft.followUp)
        assertTrue(draft.prompt.contains("User goal (JSON string): ${JSONObject.quote(task.goal)}"))
        assertFalse(draft.prompt.contains("Current user follow-up for THIS one edit"))
        assertTrue(WorkspaceAiHandoff.stillCurrent(files, tasks, projects, "site", draft))
        assertEquals("<h1>Hello, Workspace!</h1>\n", files.readFile("site", "index.html"))

        val shortNote = WorkspaceAiHandoff.prepare(files, tasks, projects, "site", "index.html", "Make the heading clearer")
        assertEquals("Make the heading clearer", shortNote.followUp)
        assertTrue(runCatching {
            WorkspaceAiHandoff.prepare(files, tasks, projects, "site", "index.html", "x".repeat(181))
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAiHandoff.prepare(files, tasks, projects, "site", "index.html", "api_key = not_a_real_key")
        }.isFailure)
    }
}
