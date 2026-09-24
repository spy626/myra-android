package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSelfVerificationTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Fixture(
        val projects: WorkspaceProjectStore,
        val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore,
        val task: WorkspaceTask,
    )

    private fun fixture(type: WorkspaceProjectType = WorkspaceProjectType.WEBSITE): Fixture {
        val projects = WorkspaceProjectStore(temp.newFolder(), idFactory = { "site" })
        projects.createProject("Site", type)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task" }, revisionFactory = { "rev" })
        val task = tasks.create("site", "Change the heading",
            rawAcceptanceCriteria = "Requested change is saved and existing work is preserved")
        tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), true)
        return Fixture(projects, tasks, WorkspaceFileStore(projects), requireNotNull(tasks.get("site")))
    }

    private fun scopedDraft(s: Fixture): Pair<WorkspaceSourceContext.Draft, WorkspaceScopedEdit.Proposal> {
        s.files.createFile("site", "index.html")
        s.files.saveFile("site", "index.html", "<h1>Hello</h1>\n")
        val context = WorkspaceSourceContext.prepare(
            s.files, s.tasks, "site", requireNotNull(s.tasks.get("site")), "index.html"
        )
        val proposal = WorkspaceScopedEdit.propose(
            s.files, s.tasks, s.projects, context, "Hello", "Welcome"
        )
        return context to proposal
    }

    @Test fun scopedEditPassRequiresCurrentTaskRollbackAndSavedHash() {
        val s = fixture()
        val (context, proposal) = scopedDraft(s)
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, context, proposal)

        val result = WorkspaceSelfVerification.verifyScopedEdit(
            s.files, s.tasks, s.projects, proposal
        )

        assertEquals(WorkspaceVerificationStatus.PASS, result.status)
        assertEquals(WorkspaceVerificationFailure.NONE, result.failure)
        assertTrue(result.passed)
        assertTrue(result.evidence.contains("saved_file_hash_matches"))
    }

    @Test fun newerUnexpectedSourceNeverBecomesPass() {
        val s = fixture()
        val (context, proposal) = scopedDraft(s)
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, context, proposal)
        s.files.saveFile("site", "index.html", "<h1>Manual newer work</h1>\n")

        val result = WorkspaceSelfVerification.verifyScopedEdit(
            s.files, s.tasks, s.projects, proposal
        )

        assertEquals(WorkspaceVerificationStatus.BLOCKED, result.status)
        assertEquals(WorkspaceVerificationFailure.SOURCE_CHANGED_AFTER_WRITE, result.failure)
        assertFalse(result.passed)
    }

    @Test fun exactOriginalWithRollbackIsClassifiedFixableButNotAutoApplied() {
        val s = fixture()
        val (context, proposal) = scopedDraft(s)
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, context, proposal)
        s.files.saveFile("site", "index.html", "<h1>Hello</h1>\n")

        val result = WorkspaceSelfVerification.verifyScopedEdit(
            s.files, s.tasks, s.projects, proposal
        )

        assertEquals(WorkspaceVerificationStatus.FAIL_FIXABLE, result.status)
        assertEquals(WorkspaceVerificationFailure.EXPECTED_WRITE_NOT_OBSERVED, result.failure)
        assertEquals("<h1>Hello</h1>\n", s.files.readFile("site", "index.html"))
    }

    @Test fun taskRevisionAfterWriteBlocksCompletion() {
        val s = fixture()
        val (context, proposal) = scopedDraft(s)
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, context, proposal)
        val now = requireNotNull(s.tasks.get("site"))
        s.tasks.create("site", "Different task", replaceExisting = true,
            rawAcceptanceCriteria = now.acceptanceCriteria)

        val result = WorkspaceSelfVerification.verifyScopedEdit(
            s.files, s.tasks, s.projects, proposal
        )

        assertEquals(WorkspaceVerificationStatus.BLOCKED, result.status)
        assertEquals(WorkspaceVerificationFailure.TASK_CHANGED, result.failure)
    }

    @Test fun websitePassRequiresAllThreeSavedFilesAndMatchingRollback() {
        val s = fixture()
        val snapshot = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        val generated = mapOf(
            "index.html" to "<!doctype html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1>Welcome</h1><script src=\"script.js\"></script></body></html>",
            "style.css" to "body { background: #fff; }",
            "script.js" to "console.log('ready');",
        )
        WorkspaceWebsiteGeneration.apply(s.files, s.tasks, s.projects, snapshot, generated)

        val result = WorkspaceSelfVerification.verifyWebsite(
            s.files, s.tasks, s.projects, snapshot, generated
        )

        assertEquals(WorkspaceVerificationStatus.PASS, result.status)
        assertTrue(result.evidence.contains("all_saved_files_match"))
    }

    @Test fun unknownEvidenceIsOnlyReobservedWithinBound() {
        val unknown = WorkspaceVerificationResult(
            WorkspaceVerificationStatus.UNKNOWN,
            WorkspaceVerificationFailure.SOURCE_UNREADABLE,
            "temporary read uncertainty"
        )
        var calls = 0
        val settled = WorkspaceSelfVerification.settleUnknown(unknown, reobserve = {
            calls++
            unknown
        })

        assertEquals(WorkspaceVerificationStatus.UNKNOWN, settled.status)
        assertEquals(WorkspaceSelfVerification.MAX_UNKNOWN_REOBSERVATIONS, calls)

        calls = 0
        val pass = WorkspaceVerificationResult(
            WorkspaceVerificationStatus.PASS,
            WorkspaceVerificationFailure.NONE,
            "pass"
        )
        val untouched = WorkspaceSelfVerification.settleUnknown(pass, reobserve = {
            calls++
            unknown
        })
        assertEquals(WorkspaceVerificationStatus.PASS, untouched.status)
        assertEquals(0, calls)
    }
}
