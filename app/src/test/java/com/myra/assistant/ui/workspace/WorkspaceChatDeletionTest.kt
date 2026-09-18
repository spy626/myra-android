package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceChatDeletionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun deleteChatOnlyRemovesTranscriptNotProjectFilesOrOtherChats() {
        val projectRoot = temp.newFolder("projects")
        val projects = WorkspaceProjectStore(projectRoot)
        val first = projects.createProject("First chat", WorkspaceProjectType.WEBSITE)
        val second = projects.createProject("Another chat", WorkspaceProjectType.WEBSITE)
        val source = File(projects.projectRoot(first.projectId), "index.html")
        source.writeText("<h1>Keep this code</h1>")
        val store = WorkspaceConversationStore(projects, temp.newFolder("conversations"))
        store.append(first.projectId, "user", "Delete my chat only")
        store.append(second.projectId, "user", "Keep other chat")

        assertTrue(store.deleteChat(first.projectId))
        assertTrue(store.read(first.projectId).isEmpty())
        assertEquals("Keep other chat", store.read(second.projectId).single().text)
        assertEquals("<h1>Keep this code</h1>", source.readText())
        assertEquals(first.projectId, projects.getProject(first.projectId)?.projectId)
        assertFalse(store.deleteChat(first.projectId))
    }

    @Test fun invalidProjectNeverDeletesOutsideTheWorkspace() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"))
        val store = WorkspaceConversationStore(projects, temp.newFolder("conversations"))
        val outside = temp.newFile("outside.txt")
        outside.writeText("safe")
        assertTrue(runCatching { store.deleteChat("../outside") }.isFailure)
        assertEquals("safe", outside.readText())
    }
}
