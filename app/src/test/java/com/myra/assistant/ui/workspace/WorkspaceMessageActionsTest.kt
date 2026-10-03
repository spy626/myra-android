package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceMessageActionsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun createStore(): Pair<WorkspaceConversationStore, String> {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "chat_project" })
        val chat = projects.createProject("Hi", WorkspaceProjectType.CHAT)
        return WorkspaceConversationStore(projects, temp.newFolder("transcripts")) to chat.projectId
    }

    @Test fun editNewestUserReplacesOnlyItsAnswer() {
        val (store, id) = createStore()
        val earlier = store.append(id, "user", "First question")
        store.append(id, "assistant", "First answer")
        val newest = store.append(id, "user", "How are you")
        store.append(id, "assistant", "I am fine")
        store.reviseNewestUser(id, newest.id, "How are you today")
        assertEquals(listOf("First question", "First answer", "How are you today"),
            store.read(id).map { it.text })
        assertEquals(earlier.id, store.read(id).first().id)
        assertEquals(newest.id, store.read(id).last().id)
    }

    @Test fun editingOlderUserDoesNotDestroyLaterMessages() {
        val (store, id) = createStore()
        val old = store.append(id, "user", "Old question")
        store.append(id, "assistant", "Old answer")
        store.append(id, "user", "New question")
        assertTrue(runCatching { store.reviseNewestUser(id, old.id, "Changed") }.isFailure)
        assertEquals(listOf("Old question", "Old answer", "New question"),
            store.read(id).map { it.text })
    }

    @Test fun retryReplacesOnlyLatestReplyAndPreservesItsIdentity() {
        val (store, id) = createStore()
        val user = store.append(id, "user", "Hello")
        val reply = store.append(id, "assistant", "First reply")
        val replacement = store.replaceNewestAssistant(id, reply.id, user.id, "Retried reply")
        assertEquals(reply.id, replacement.id)
        assertEquals(listOf("Hello", "Retried reply"), store.read(id).map { it.text })
    }

    @Test fun staleRetryCannotOverwriteLaterMessagesOrTheOriginal() {
        val (store, id) = createStore()
        val user = store.append(id, "user", "Hello")
        val reply = store.append(id, "assistant", "Original")
        store.append(id, "user", "Another question")
        assertTrue(runCatching {
            store.replaceNewestAssistant(id, reply.id, user.id, "Stale replacement")
        }.isFailure)
        assertEquals(listOf("Hello", "Original", "Another question"),
            store.read(id).map { it.text })
    }
}
