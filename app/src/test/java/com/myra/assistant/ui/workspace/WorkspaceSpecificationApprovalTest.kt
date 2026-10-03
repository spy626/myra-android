package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSpecificationApprovalTest {
    @get:Rule val temp = TemporaryFolder()
    private data class Fixture(val root: File, val store: WorkspaceTaskStore)
    private fun fixture(name: String = "projects"): Fixture {
        val root = temp.newFolder(name)
        val projects = WorkspaceProjectStore(root, idFactory = { "site_one" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        var revision = 0
        return Fixture(root, WorkspaceTaskStore(projects, nowMillis = { 100L },
            idFactory = { "task_one" }, revisionFactory = { "rev_${++revision}" }))
    }

    @Test fun explicitConsentIsVersionBoundPersistsAcrossReopenPauseAndRevoke() {
        val f = fixture()
        val first = f.store.create("site_one", "Build site", rawAcceptanceCriteria = "Search works")
        assertFalse(WorkspaceTaskContract.isSpecApproved(first))
        val token = WorkspaceTaskContract.specToken(first)
        val approved = f.store.setSpecificationApproved("site_one", first.taskId, token, true)
        assertTrue(WorkspaceTaskContract.isSpecApproved(approved))
        assertEquals("rev_1", approved.specRevision)
        val reopened = WorkspaceTaskStore(WorkspaceProjectStore(f.root))
        assertTrue(WorkspaceTaskContract.isSpecApproved(reopened.get("site_one")!!))
        val paused = reopened.setPaused("site_one", true)
        assertTrue(WorkspaceTaskContract.isSpecApproved(paused))
        assertEquals(WorkspaceTaskStatus.PAUSED, paused.status)
        assertTrue(WorkspaceTaskContract.isSpecApproved(reopened.setPaused("site_one", false)))
        val revoked = reopened.setSpecificationApproved("site_one", first.taskId, token, false)
        assertFalse(WorkspaceTaskContract.isSpecApproved(revoked))
        assertNull(reopened.get("site_one")!!.approvedSpecToken)
        assertEquals("Search works", reopened.get("site_one")!!.acceptanceCriteria)
        assertEquals(3, JSONObject(File(f.root, "site_one/.lyra/task.json").readText()).getInt("schemaVersion"))
    }

    @Test fun changedAndRevertedCriteriaRequireFreshApproval() {
        val f = fixture("changed")
        val original = f.store.create("site_one", "Build", rawAcceptanceCriteria = "A")
        val token = WorkspaceTaskContract.specToken(original)
        f.store.setSpecificationApproved("site_one", original.taskId, token, true)
        f.store.updateAcceptanceCriteria("site_one", " A ")
        assertTrue(WorkspaceTaskContract.isSpecApproved(f.store.get("site_one")!!))
        val changed = f.store.updateAcceptanceCriteria("site_one", "B")
        assertFalse(WorkspaceTaskContract.isSpecApproved(changed))
        assertNotEquals(original.specRevision, changed.specRevision)
        assertThrows(IllegalArgumentException::class.java) {
            f.store.setSpecificationApproved("site_one", original.taskId, token, true)
        }
        val reverted = f.store.updateAcceptanceCriteria("site_one", "A")
        assertFalse(WorkspaceTaskContract.isSpecApproved(reverted))
        assertNotEquals(token, WorkspaceTaskContract.specToken(reverted))
        assertEquals("A", reverted.acceptanceCriteria)
    }

    @Test fun emptyCriteriaStaleTaskAndOtherProjectCannotBeApproved() {
        val f = fixture("guard")
        val incomplete = f.store.create("site_one", "Build")
        val token = WorkspaceTaskContract.specToken(incomplete)
        assertThrows(IllegalArgumentException::class.java) {
            f.store.setSpecificationApproved("site_one", incomplete.taskId, token, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.store.setSpecificationApproved("site_one", "different_task", token, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.store.setSpecificationApproved("missing_project", incomplete.taskId, token, true)
        }
        assertNull(f.store.get("site_one")!!.approvedSpecToken)
        assertFalse(File(f.root, "missing_project").exists())
    }

    @Test fun goalReplacementOrExternalTamperCannotInheritConsent() {
        val f = fixture("replace")
        val first = f.store.create("site_one", "Original", rawAcceptanceCriteria = "Test")
        f.store.setSpecificationApproved("site_one", first.taskId, WorkspaceTaskContract.specToken(first), true)
        val taskFile = File(f.root, "site_one/.lyra/task.json")
        taskFile.writeText(JSONObject(taskFile.readText()).put("goal", "Altered outside app").toString())
        assertFalse(WorkspaceTaskContract.isSpecApproved(f.store.get("site_one")!!))
        val replaced = f.store.create("site_one", "New brief", replaceExisting = true,
            rawAcceptanceCriteria = "New test")
        assertFalse(WorkspaceTaskContract.isSpecApproved(replaced))
        assertNull(f.store.get("site_one")!!.approvedAtMs)
    }

    @Test fun legacyV1AndV2StayUnapprovedUntilExplicitConsent() {
        val f = fixture("legacy")
        val file = File(f.root, "site_one/.lyra/task.json")
        for (version in 1..2) {
            val json = JSONObject().put("schemaVersion", version).put("projectId", "site_one")
                .put("taskId", "old_task").put("goal", "Old goal").put("status", "PAUSED")
                .put("createdAtMs", 1).put("updatedAtMs", 2)
            if (version == 2) json.put("acceptanceCriteria", "Old test")
            file.writeText(json.toString())
            assertFalse(WorkspaceTaskContract.isSpecApproved(f.store.get("site_one")!!))
            assertEquals(WorkspaceTaskStatus.PAUSED, f.store.get("site_one")!!.status)
        }
        f.store.updateAcceptanceCriteria("site_one", "New test")
        assertEquals(3, JSONObject(file.readText()).getInt("schemaVersion"))
        assertFalse(WorkspaceTaskContract.isSpecApproved(f.store.get("site_one")!!))
    }
}
