package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceAiSuggestionDraftStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private var time = 1_800_000_000_000L

    private data class Fixture(
        val projects: WorkspaceProjectStore,
        val tasks: WorkspaceTaskStore,
        val files: WorkspaceFileStore,
        val privateDir: File,
        val store: WorkspaceAiSuggestionDraftStore,
    )

    private fun fixture(source: String = "<h1>Hello</h1>\n"): Fixture {
        val projects = WorkspaceProjectStore(temp.newFolder(), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_1" })
        val task = tasks.create("site", "Improve heading", rawAcceptanceCriteria = "Heading updated")
        tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), true)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.saveFile("site", "index.html", source)
        val privateDir = temp.newFolder()
        return Fixture(projects, tasks, files, privateDir,
            WorkspaceAiSuggestionDraftStore(privateDir) { time })
    }

    private fun json(new: String = "Welcome") =
        """{"schemaVersion":1,"operation":"replace_exact_once","path":"index.html","oldText":"Hello","newText":"$new","rationale":"matches accepted task"}"""

    private fun save(s: Fixture, reply: String = json()) {
        val validated = WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", reply)
        s.store.save(s.files, s.tasks, s.projects, "site", reply, validated)
    }

    private fun recover(s: Fixture) = s.store.recover(s.files, s.tasks, s.projects, "site")

    @Test fun savedPatchReopensOfflineWithoutWritingOrPersistingKeyOrPrompt() {
        val s = fixture()
        val original = s.files.readFile("site", "index.html")
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Missing, recover(s))
        save(s)
        val stored = s.privateDir.listFiles().orEmpty().single()
        val onDisk = stored.readText()
        assertEquals(json(), JSONObject(onDisk).getString("reply"))
        assertFalse(onDisk.contains("Bearer "))
        assertFalse(onDisk.contains("OpenRouter"))
        assertFalse(onDisk.contains("Untrusted source text"))
        val reopened = WorkspaceAiSuggestionDraftStore(s.privateDir) { time }
        val restored = reopened.recover(s.files, s.tasks, s.projects, "site")
        assertTrue(restored is WorkspaceAiSuggestionDraftStore.Recovery.Ready)
        val ready = restored as WorkspaceAiSuggestionDraftStore.Recovery.Ready
        assertEquals(json(), ready.reply)
        assertEquals("Hello", ready.draft.proposal.oldText)
        assertEquals(original, s.files.readFile("site", "index.html"))
        assertNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        reopened.discard("site")
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Missing, recover(s))
    }

    @Test fun sourceChangeExpiresAndTamperingAllDiscardWithoutWrites() {
        val s = fixture()
        save(s)
        s.files.saveFile("site", "index.html", "<h1>Later</h1>\n")
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Rejected, recover(s))
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Missing, recover(s))
        assertEquals("<h1>Later</h1>\n", s.files.readFile("site", "index.html"))

        s.files.saveFile("site", "index.html", "<h1>Hello</h1>\n")
        save(s)
        time += 24 * 60 * 60 * 1000L + 1
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Rejected, recover(s))
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Missing, recover(s))
        save(s)
        val file = s.privateDir.listFiles().orEmpty().single()
        val tampered = JSONObject(file.readText()).put("reply", json("Stolen"))
        file.writeText(tampered.toString())
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Rejected, recover(s))
        assertEquals("<h1>Hello</h1>\n", s.files.readFile("site", "index.html"))
    }

    @Test fun pausedRevokedTaskAndPendingRollbackDoNotRestorePatch() {
        val paused = fixture()
        save(paused)
        paused.tasks.setPaused("site", true)
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Rejected, recover(paused))
        paused.tasks.setPaused("site", false)
        save(paused)
        val task = requireNotNull(paused.tasks.get("site"))
        paused.tasks.setSpecificationApproved("site", task.taskId, WorkspaceTaskContract.specToken(task), false)
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Rejected, recover(paused))

        val s = fixture()
        save(s)
        val patch = WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json())
        WorkspaceScopedEdit.apply(s.files, s.tasks, s.projects, patch.context, patch.proposal)
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Rejected, recover(s))
        assertTrue(s.files.readFile("site", "index.html").contains("Welcome"))
        assertNotNull(WorkspaceScopedEdit.pending(s.projects, "site"))
        WorkspaceScopedEdit.undo(s.files, s.projects, "site")
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Missing, recover(s))
    }

    @Test fun wrongValidatedProposalAndInvalidRawCannotBePersisted() {
        val s = fixture()
        val correct = WorkspaceStructuredEdit.prepare(s.files, s.tasks, s.projects, "site", json())
        assertTrue(runCatching {
            s.store.save(s.files, s.tasks, s.projects, "site", json("Different"), correct)
        }.isFailure)
        assertTrue(runCatching {
            s.store.save(s.files, s.tasks, s.projects, "site", json() + json(), correct)
        }.isFailure)
        assertEquals(WorkspaceAiSuggestionDraftStore.Recovery.Missing, recover(s))
        assertEquals("<h1>Hello</h1>\n", s.files.readFile("site", "index.html"))
    }
}
