package com.myra.assistant.ui.workspace

import com.myra.assistant.data.memory.MemoryEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceContextProjectionTest {
    private fun user(value: String) = WorkspaceConversationStore.Message(value, "user", value, 1L)
    private fun assistant(value: String) = WorkspaceConversationStore.Message(value, "assistant", value, 1L)
    private fun card(value: String, category: String = "PROJECT", explicit: Boolean = true,
                     temporal: String = "CURRENT") = MemoryEntity(
        "id:$value", "project:lyra", category, value, .95, "FINAL_USER_TURN", 1L, 2L,
        explicit = explicit, temporalScope = temporal)

    @Test fun relevantOldUserTopicIsAvailableBeyondEightTurnsWithoutAssistantGuesses() {
        val older = user("I am building an Android companion with memory and voice")
        val filler = (1..13).flatMap { listOf(user("Unrelated food topic $it"), assistant("Guess: buy a phone $it")) }
        val history = listOf(older) + filler + user("Can you improve that Android companion memory?")
        val note = WorkspaceContextProjection.earlierUserContext(history)
        assertTrue(note.contains(older.text))
        assertFalse(note.contains("Guess:"))
        val sent = JSONObject(WorkspaceChatGateway.openRouterBody(history)).getJSONArray("messages")
        assertEquals(25, sent.length()) // one bounded system note, last 24 raw messages
        assertEquals("system", sent.getJSONObject(0).getString("role"))
        assertTrue(sent.getJSONObject(0).getString("content").contains("SAME chat"))
        assertEquals(history.last().text, sent.getJSONObject(24).getString("content"))
        assertFalse(sent.getJSONObject(1).getString("content").contains(older.text))
    }

    @Test fun unrelatedOrSensitiveOldTurnsAreNotReplayedAsMemory() {
        val filler = (1..13).flatMap { listOf(user("Gardening flowers number $it"), assistant("Okay $it")) }
        assertEquals("", WorkspaceContextProjection.earlierUserContext(
            listOf(user("My API key is secret123 and I have an Android companion")) + filler +
                user("Improve that Android companion")))
        assertEquals("", WorkspaceContextProjection.earlierUserContext(
            listOf(user("I like gardening")) + filler + user("Hello bro")))
        assertEquals("", WorkspaceContextProjection.earlierUserContext(listOf(user("Hi"))))
    }

    @Test fun savedFactsAreRelevantCurrentExplicitAndNonSensitiveOnly() {
        val eligible = card("I am building a LYRA Android companion")
        val private = card("My API key is sk-test-123")
        val person = card("My friend is Kareem", "PERSON")
        val historical = card("I used to build Android companion", temporal = "HISTORICAL")
        val inferred = card("Android companion is my hobby", explicit = false)
        val facts = WorkspaceContextProjection.shareableMemoryFacts(
            listOf(private, person, historical, inferred, eligible), "Help my Android companion app")
        assertEquals(listOf(eligible.fact), facts)
        assertTrue(WorkspaceContextProjection.shareableMemoryFacts(listOf(eligible),
            "My password is 12345678").isEmpty())
        assertTrue(WorkspaceContextProjection.shareableMemoryFacts(listOf(eligible), "Hello").isEmpty())
    }

    @Test fun memoryEnvelopePreservesProviderAndOriginalUserTurn() {
        val original = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(user("Help with my companion"))))
        val enriched = WorkspaceMemoryInterceptor.enrich(original, listOf("I am building LYRA companion"))
        assertEquals("openrouter/free", enriched.getString("model"))
        assertFalse(enriched.getJSONObject("provider").getBoolean("allow_fallbacks"))
        val messages = enriched.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("Saved long-term memories"))
        assertEquals("Help with my companion", messages.getJSONObject(1).getString("content"))
        assertEquals(2, JSONObject(WorkspaceChatGateway.openRouterBody(listOf(user("Hi"))))
            .getJSONArray("messages").length())
    }
}
