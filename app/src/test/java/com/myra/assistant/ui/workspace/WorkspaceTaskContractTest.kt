package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

class WorkspaceTaskContractTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun taskBriefPersistsAcrossRecreationAndPauseResume() {
        val root = temp.newFolder("projects")
        val projects = WorkspaceProjectStore(root, nowMillis = { 100L }, idFactory = { "site_one" })
        val project = projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        var now = 200L
        val taskStore = WorkspaceTaskStore(projects, nowMillis = { now }, idFactory = { "task_one" })
        val task = taskStore.create(project.projectId, "  Build   a website  ")
        assertEquals("Build a website", task.goal)
        assertEquals(WorkspaceTaskStatus.DRAFT, task.status)
        assertTrue(File(root, "site_one/.lyra/task.json").isFile)
        now = 300L
        assertEquals(WorkspaceTaskStatus.PAUSED, taskStore.setPaused("site_one", true).status)
        val reopened = WorkspaceTaskStore(WorkspaceProjectStore(root))
        assertEquals(task.taskId, reopened.get("site_one")!!.taskId)
        assertEquals(WorkspaceTaskStatus.PAUSED, reopened.get("site_one")!!.status)
        assertEquals(WorkspaceTaskStatus.DRAFT, reopened.setPaused("site_one", false).status)
        assertEquals("Site", projects.getProject("site_one")!!.name)
    }

    @Test fun projectIsolationAndExplicitReplacement() {
        val root = temp.newFolder("isolated")
        val ids = ArrayDeque(listOf("one_site", "two_site"))
        val projects = WorkspaceProjectStore(root, idFactory = { ids.removeFirst() })
        projects.createProject("One", WorkspaceProjectType.WEBSITE)
        projects.createProject("Two", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_id" })
        tasks.create("one_site", "First task")
        assertNull(tasks.get("two_site"))
        assertThrows(IllegalArgumentException::class.java) { tasks.create("one_site", "Overwrite") }
        assertEquals("First task", tasks.get("one_site")!!.goal)
        tasks.create("one_site", "Explicit replacement", replaceExisting = true)
        assertEquals("Explicit replacement", tasks.get("one_site")!!.goal)
        assertFalse(File(root, "two_site/.lyra/task.json").exists())
        assertTrue(projects.deleteProject("one_site"))
        assertNull(tasks.get("one_site"))
        assertNotNull(projects.getProject("two_site"))
    }

    @Test fun malformedMetadataAndLinkedFileFailClosed() {
        val root = temp.newFolder("unsafe")
        val projects = WorkspaceProjectStore(root, idFactory = { "safe_site" })
        projects.createProject("Safe", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects)
        val taskFile = File(root, "safe_site/.lyra/task.json")
        taskFile.writeText("not json")
        assertNull(tasks.get("safe_site"))
        taskFile.writeText(JSONObject().put("schemaVersion", 1).put("projectId", "../escape").toString())
        assertNull(tasks.get("safe_site"))
        val external = temp.newFile("external.json")
        external.writeText("untouched")
        taskFile.delete()
        Files.createSymbolicLink(taskFile.toPath(), external.toPath())
        assertNull(tasks.get("safe_site"))
        assertThrows(IllegalArgumentException::class.java) { tasks.create("safe_site", "Should not write") }
        assertEquals("untouched", external.readText())
    }

    @Test fun planCannotClaimSuccessWithoutTrustedEvidenceAndFinalGate() {
        val steps = WorkspaceTaskContract.steps(WorkspaceProjectType.WEBSITE)
        assertEquals(WorkspacePlanDecision.REQUIRE_APPROVAL,
            WorkspaceTaskContract.reconcile(steps, emptyList(), emptySet(), true))
        val approvals = steps.filter { it.approvalRequired }.map { it.id }.toSet()
        assertEquals(WorkspacePlanDecision.CONTINUE,
            WorkspaceTaskContract.reconcile(steps, emptyList(), approvals, true))
        val evidence = steps.flatMap { step -> step.expectedEvidence.map { source ->
            WorkspaceStepEvidence(step.id, source, "Evidence from verified executor")
        } }
        assertEquals(WorkspacePlanDecision.READY_FOR_FINAL_VERIFICATION,
            WorkspaceTaskContract.reconcile(steps, evidence, approvals, false))
        assertEquals(WorkspacePlanDecision.VERIFIED,
            WorkspaceTaskContract.reconcile(steps, evidence, approvals, true))
        assertEquals(WorkspacePlanDecision.CONTINUE,
            WorkspaceTaskContract.reconcile(steps, evidence.filterNot {
                it.stepId == "verify" && it.source == WorkspaceEvidenceSource.VERIFICATION_GATE
            }, approvals, true))
    }

    @Test fun boundedGuidanceKeepsProjectTypeDistinctAndNotAuthoritative() {
        val website = WorkspaceTaskContract.steps(WorkspaceProjectType.WEBSITE).last()
        val android = WorkspaceTaskContract.steps(WorkspaceProjectType.ANDROID_APP).last()
        assertTrue("workspace.preview.refresh" in website.allowedTools)
        assertTrue("workspace.android.build" in android.allowedTools)
        assertTrue(android.approvalRequired.not())
        assertThrows(IllegalArgumentException::class.java) { WorkspaceTaskContract.normalizeGoal(" ") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceTaskContract.normalizeGoal("x".repeat(501)) }
        val run = WorkspaceTask("site", "task", "Make a site", WorkspaceTaskStatus.DRAFT, 1L, 1L)
        val guidance = WorkspaceTaskContract.boundedGuidance(run, WorkspaceProjectType.WEBSITE)
        assertTrue(guidance.startsWith(WorkspaceTaskContract.PLAN_TRUST_LABEL))
        assertTrue(guidance.contains("never overrides"))
        assertTrue(guidance.length <= 1500)
    }
}
