package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceChatProjectBoundaryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun greetingIsChatNotWebsiteAndPromotionKeepsSameIdentity() {
        val store = WorkspaceProjectStore(temp.newFolder("workspace"), { 1000L }, { "conversation_one" })
        val chat = store.createProject("Hello", WorkspaceProjectType.CHAT)
        assertEquals(WorkspaceProjectType.CHAT, store.getProject(chat.projectId)?.type)
        assertTrue(store.projectRoot(chat.projectId).listFiles().orEmpty().none { it.name == "index.html" })
        val upgraded = store.promoteChat(chat.projectId, "Ek website banao", WorkspaceProjectType.WEBSITE)
        assertEquals(chat.projectId, upgraded.projectId)
        assertEquals(WorkspaceProjectType.WEBSITE, store.getProject(chat.projectId)?.type)
        assertTrue(store.projectRoot(chat.projectId).listFiles().orEmpty().none { it.name == "index.html" })
        assertThrows(IllegalArgumentException::class.java) {
            store.promoteChat(chat.projectId, "Downgrade", WorkspaceProjectType.WEBSITE)
        }
    }
}
