package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceCodingTurnResultTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun resultBelongsOnlyToExactCodingTurnAndCanReplaceItsFailure() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "website_chat" })
        val id = projects.createProject("Build Minicoy site", WorkspaceProjectType.WEBSITE).projectId
        val chat = WorkspaceConversationStore(projects, temp.newFolder("chats"))
        val user = chat.append(id, "user", "Build my website")
        val first = chat.completeCodingTurn(id, user.id, "Timed out. Try again")
        val replaced = chat.completeCodingTurn(id, user.id, "Website files saved")
        assertEquals(first.id, replaced.id)
        assertEquals(listOf("Build my website", "Website files saved"), chat.read(id).map { it.text })
        val newest = chat.append(id, "user", "Add a section")
        assertThrows(IllegalStateException::class.java) {
            chat.completeCodingTurn(id, user.id, "Stale result")
        }
        chat.completeCodingTurn(id, newest.id, "Updated files")
        assertEquals("Updated files", chat.read(id).last().text)
    }
    @Test fun ordinaryChatNeverAcceptsCodingOutcome() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "normal_chat" })
        val id = projects.createProject("Hi", WorkspaceProjectType.CHAT).projectId
        val chat = WorkspaceConversationStore(projects, temp.newFolder("chats"))
        val user = chat.append(id, "user", "Hi")
        assertThrows(IllegalArgumentException::class.java) {
            chat.completeCodingTurn(id, user.id, "Not a coding result")
        }
    }
}
