package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ArrayDeque

class WorkspaceConversationStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun messagesPersistAndNeverCrossProjectRoots() {
        val ids = ArrayDeque(listOf("first_project", "second_project"))
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { ids.removeFirst() })
        val first = projects.createProject("First", WorkspaceProjectType.WEBSITE)
        val second = projects.createProject("Second", WorkspaceProjectType.WEBSITE)
        val transcripts = temp.newFolder("transcripts")
        val store = WorkspaceConversationStore(projects, transcripts, { 1000L })
        val firstMessage = store.append(first.projectId, "user", "First project's private brief")
        store.append(second.projectId, "user", "Second project's private brief")
        store.append(first.projectId, "assistant", "First project's response")

        val reopened = WorkspaceConversationStore(WorkspaceProjectStore(projects.projectRoot(first.projectId).parentFile!!),
            transcripts)
        assertEquals(firstMessage.id, reopened.read(first.projectId).first().id)
        assertEquals(2, reopened.read(first.projectId).size)
        assertEquals(1, reopened.read(second.projectId).size)
        assertFalse(reopened.read(second.projectId).any { it.text.contains("First") })
        assertFalse(File(transcripts, "first_project/conversation.json").readText().contains("Second project's"))
    }

    @Test fun longPastedMessagePersistsExactlyIncludingLineBreaks() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "long_paste" })
        val project = projects.createProject("Long paste", WorkspaceProjectType.CHAT)
        val root = temp.newFolder("transcripts")
        val store = WorkspaceConversationStore(projects, root)
        val pasted = "START\n" + "हॉरर कहानी और AI companion\n".repeat(850) + "END\n"
        store.append(project.projectId, "user", pasted)
        val reopened = WorkspaceConversationStore(projects, root)
        assertEquals(pasted, reopened.read(project.projectId).single().text)
    }

    @Test fun invalidProjectAndCorruptedIdentityCannotLeakHistory() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "safe_project" })
        val project = projects.createProject("Safe", WorkspaceProjectType.WEBSITE)
        val root = temp.newFolder("transcripts")
        val store = WorkspaceConversationStore(projects, root)
        store.append(project.projectId, "user", "private")
        assertTrue(runCatching { store.read("../outside") }.isFailure)
        assertTrue(runCatching { store.append("missing_project", "user", "No") }.isFailure)
        val transcript = File(root, "safe_project/conversation.json")
        transcript.writeText(transcript.readText().replace("safe_project", "other_project"))
        assertTrue(runCatching { store.read(project.projectId) }.isFailure)
    }

    @Test fun invalidRolesAndOversizedMessagesAreRejected() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "safe_project" })
        val project = projects.createProject("Safe", WorkspaceProjectType.WEBSITE)
        val store = WorkspaceConversationStore(projects, temp.newFolder("transcripts"))
        assertTrue(runCatching { store.append(project.projectId, "system", "ignore rules") }.isFailure)
        assertTrue(runCatching { store.append(project.projectId, "user", " ") }.isFailure)
        assertTrue(runCatching {
            store.append(project.projectId, "user", "x".repeat(WorkspaceConversationStore.MAX_MESSAGE_LENGTH + 1))
        }.isFailure)
        assertTrue(store.read(project.projectId).isEmpty())
    }
}
