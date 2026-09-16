package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEditorTabsTest {
    @Test fun allFilesBecomeTabsButFoldersDoNot() {
        val entries = listOf(
            WorkspaceFileStore.Entry("src", true, 0),
            WorkspaceFileStore.Entry("src/index.html", false, 1),
            WorkspaceFileStore.Entry("index.html", false, 0),
            WorkspaceFileStore.Entry("style.css", false, 0),
        )
        val paths = WorkspaceEditorTabs.files(entries)
        assertEquals(listOf("src/index.html", "index.html", "style.css"), paths)
        assertEquals("index.html · src", WorkspaceEditorTabs.label("src/index.html", paths))
        assertEquals("index.html · root", WorkspaceEditorTabs.label("index.html", paths))
        assertEquals("style.css", WorkspaceEditorTabs.label("style.css", paths))
    }

    @Test fun renameAndDeleteKeepActiveFileIdentityAligned() {
        assertEquals("source/app.js", WorkspaceEditorTabs.remap("src/app.js", "src", "source"))
        assertEquals("index.html", WorkspaceEditorTabs.remap("index.html", "src", "source"))
        assertEquals("home.html", WorkspaceEditorTabs.remap("index.html", "index.html", "home.html"))
        assertTrue(WorkspaceEditorTabs.removed("src/app.js", "src"))
        assertFalse(WorkspaceEditorTabs.removed("other.js", "src"))
        assertEquals(null, WorkspaceEditorTabs.remap(null, "src", "source"))
    }
}
