package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceTaskSpecificationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun acceptanceCriteriaPersistAndCriteriaOnlyUpdatePreservesTaskAndPause() {
        val root = temp.newFolder("projects")
        val projects = WorkspaceProjectStore(root, idFactory = { "site_one" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val store = WorkspaceTaskStore(projects, nowMillis = { 100L }, idFactory = { "task_one" })
        val first = store.create("site_one", "Build site", rawAcceptanceCriteria = "  Search   works ")
        assertEquals("Search works", first.acceptanceCriteria)
        assertEquals(3, JSONObject(File(root, "site_one/.lyra/task.json").readText()).getInt("schemaVersion"))
        val paused = store.setPaused("site_one", true)
        val updated = store.updateAcceptanceCriteria("site_one", "Cards fit on phone")
        assertEquals(first.taskId, updated.taskId)
        assertEquals(first.createdAtMs, updated.createdAtMs)
        assertEquals(paused.status, updated.status)
        assertEquals("Build site", updated.goal)
        assertEquals("Cards fit on phone", WorkspaceTaskStore(WorkspaceProjectStore(root)).get("site_one")!!.acceptanceCriteria)
    }

    @Test fun legacyV1TaskRemainsReadableUntilExplicitWrite() {
        val root = temp.newFolder("legacy")
        val projects = WorkspaceProjectStore(root, idFactory = { "site_one" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val file = File(root, "site_one/.lyra/task.json")
        file.writeText(JSONObject().put("schemaVersion", 1).put("projectId", "site_one")
            .put("taskId", "old_task").put("goal", "Existing goal").put("status", "PAUSED")
            .put("createdAtMs", 10).put("updatedAtMs", 20).toString())
        val store = WorkspaceTaskStore(projects)
        assertEquals("", store.get("site_one")!!.acceptanceCriteria)
        assertEquals("Existing goal", store.get("site_one")!!.goal)
        assertEquals(1, JSONObject(file.readText()).getInt("schemaVersion"))
        store.updateAcceptanceCriteria("site_one", "Visible result")
        assertEquals(3, JSONObject(file.readText()).getInt("schemaVersion"))
        assertEquals("old_task", store.get("site_one")!!.taskId)
        assertEquals(WorkspaceTaskStatus.PAUSED, store.get("site_one")!!.status)
    }

    @Test fun rejectedCriteriaCannotReplaceExistingTask() {
        val root = temp.newFolder("reject")
        val projects = WorkspaceProjectStore(root, idFactory = { "site_one" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val store = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        store.create("site_one", "Original")
        assertThrows(IllegalArgumentException::class.java) {
            store.updateAcceptanceCriteria("site_one", "x".repeat(501))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.create("site_one", "Replacement", replaceExisting = true, rawAcceptanceCriteria = "bad\u0000value")
        }
        assertEquals("Original", store.get("site_one")!!.goal)
        assertEquals("", store.get("site_one")!!.acceptanceCriteria)
    }

    @Test fun guidanceIncludesCriteriaButNeverClaimsVerification() {
        val task = WorkspaceTask("site", "task", "Build site", WorkspaceTaskStatus.DRAFT,
            1L, 1L, "Search works")
        val guidance = WorkspaceTaskContract.boundedGuidance(task, WorkspaceProjectType.WEBSITE)
        assertTrue(guidance.contains("Acceptance criteria: Search works"))
        assertTrue(guidance.contains("No task is complete without trusted evidence"))
        assertTrue(guidance.length <= 1500)
        assertTrue(WorkspaceTaskContract.boundedGuidance(task.copy(acceptanceCriteria = ""),
            WorkspaceProjectType.WEBSITE).contains("ask the user before implementation"))
    }
}
