package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTurnPublicNarrationsTest {
    @Test fun sameMilestoneUpdatesInPlaceInsteadOfSpammingParagraphs() {
        var tick = 10L
        val store = WorkspaceTurnPublicNarrations(
            maxTurns = 4,
            maxEntriesPerTurn = 4,
            now = { tick++ },
        )

        store.reset("turn-a")
        store.upsert("turn-a", "ci-running-1", "CI #1 is queued", "CI queued hai.")
        store.upsert("turn-a", "ci-running-1", "CI #1 is in progress", "CI ab run ho raha hai.")

        val entries = store.forTurn("turn-a")
        assertEquals(1, entries.size)
        assertEquals("CI #1 is in progress", entries.single().statusLabel)
        assertEquals("CI ab run ho raha hai.", entries.single().text)
        assertEquals(10L, entries.single().atMs)
    }

    @Test fun exactTurnsStayIsolatedAndEntriesAreBounded() {
        val store = WorkspaceTurnPublicNarrations(maxTurns = 3, maxEntriesPerTurn = 2)
        store.reset("first")
        store.upsert("first", "a", "A", "First A")
        store.upsert("first", "b", "B", "First B")
        store.upsert("first", "c", "C", "First C")
        store.reset("second")
        store.upsert("second", "x", "X", "Second X")

        assertEquals(listOf("b", "c"), store.forTurn("first").map { it.key })
        assertEquals(listOf("x"), store.forTurn("second").map { it.key })
    }

    @Test fun restoreKeepsOriginalOrderTimestampAndBounds() {
        val store = WorkspaceTurnPublicNarrations(maxTurns = 3, maxEntriesPerTurn = 2)
        store.restore(
            "turn",
            listOf(
                WorkspacePublicWorkMessage("a", "A", "First A", 30L),
                WorkspacePublicWorkMessage("b", "B", "First B", 10L),
                WorkspacePublicWorkMessage("c", "C", "First C", 20L),
            ),
        )

        val restored = store.forTurn("turn")
        assertEquals(listOf("c", "a"), restored.map { it.key })
        assertEquals(listOf(20L, 30L), restored.map { it.atMs })
    }

    @Test fun secretLikePublicNarrationIsDropped() {
        val store = WorkspaceTurnPublicNarrations(maxTurns = 3, maxEntriesPerTurn = 3)
        store.reset("turn")
        val saved = store.upsert(
            "turn",
            "secret",
            "Reading config",
            "api_key=sk-super-secret-123456",
        )

        assertNull(saved)
        assertTrue(store.forTurn("turn").isEmpty())
    }
}
