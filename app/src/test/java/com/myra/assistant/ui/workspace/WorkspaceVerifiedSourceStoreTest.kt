package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceVerifiedSourceStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun storesOnlyPublicHttpsAndKeepsMessagesSeparated() {
        val store = WorkspaceVerifiedSourceStore(temp.newFolder("sources"))
        val source = WorkspaceVerifiedSourceStore.Source(
            title = "CarryMinati",
            url = "https://www.youtube.com/channel/UC1234567890123456789012",
            snippet = "Direct channel matched from a live public YouTube lookup.",
            observedAtMs = 1234L,
            verifiedLabel = "Verified channel",
        )
        store.put("chat_one", "assistant_one", listOf(source))
        val read = store.get("chat_one", "assistant_one")
        assertEquals(1, read.size)
        assertEquals("www.youtube.com", read.single().host)
        assertEquals("Verified channel", read.single().verifiedLabel)
        assertTrue(store.get("chat_one", "assistant_two").isEmpty())

        listOf(
            "http://youtube.com/",
            "https://localhost/test",
            "https://127.0.0.1/test",
            "https://example.com/?access_token=secret",
        ).forEach { unsafe ->
            assertTrue(
                unsafe,
                runCatching {
                    store.put(
                        "chat_one",
                        "assistant_two",
                        listOf(source.copy(url = unsafe)),
                    )
                }.isFailure,
            )
        }
    }

    @Test fun deleteProjectRemovesSourceTrayMetadata() {
        val store = WorkspaceVerifiedSourceStore(temp.newFolder("sources"))
        store.put(
            "chat_one",
            "assistant_one",
            listOf(
                WorkspaceVerifiedSourceStore.Source(
                    "Official page",
                    "https://example.com/",
                    "Observed public source.",
                    10L,
                )
            ),
        )
        store.deleteProject("chat_one")
        assertTrue(store.get("chat_one", "assistant_one").isEmpty())
    }
}
