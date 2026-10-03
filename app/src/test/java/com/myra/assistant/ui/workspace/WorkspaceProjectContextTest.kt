package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceProjectContextTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Fixture(
        val projects: WorkspaceProjectStore,
        val files: WorkspaceFileStore,
    )

    private fun fixture(): Fixture {
        val projects = WorkspaceProjectStore(temp.newFolder(), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        listOf("index.html", "style.css", "script.js", "notes.md").forEach {
            files.createFile("site", it)
        }
        files.saveFile("site", "index.html",
            """<link rel="stylesheet" href="style.css"><main id="hero">Hi</main><script src="script.js"></script>""")
        files.saveFile("site", "style.css", "body { margin: 0 } .hero { color: white }\n".repeat(30))
        files.saveFile("site", "script.js", "function explore(){ return true }\n".repeat(30))
        files.saveFile("site", "notes.md", "unrelated private design notes but no secret")
        return Fixture(projects, files)
    }

    @Test fun buildsBoundedStructureAndProjectsNoNeighborContents() {
        val s = fixture()
        val projection = WorkspaceProjectContext.build(
            s.files, s.projects, "site", "index.html", "Improve hero button and style")
        assertTrue(projection.indexed.size <= 12)
        assertTrue(projection.selected.any { it.path == "style.css" })
        assertTrue(projection.selected.any { it.path == "script.js" })
        assertFalse(projection.selected.any { it.path == "notes.md" })
        val note = projection.promptNote()
        assertTrue(note.contains("metadata only"))
        assertTrue(note.contains("style.css"))
        assertTrue(note.contains("script.js"))
        assertFalse(note.contains("margin: 0"))
        assertFalse(note.contains("function explore"))
        assertTrue(projection.rawCharsRead > projection.projectedChars)
        assertTrue(projection.rawCharsAvoided > 0)
    }

    @Test fun sensitiveNeighborIsIndexedForFreshnessButNeverProjected() {
        val s = fixture()
        s.files.saveFile("site", "script.js", "const api_key = 'abcdefghijklmnop'; function explore(){}")
        val projection = WorkspaceProjectContext.build(
            s.files, s.projects, "site", "index.html", "Fix Explore button")
        val script = projection.indexed.first { it.path == "script.js" }
        assertTrue(script.blockedSensitive)
        assertFalse(projection.selected.any { it.path == "script.js" })
        assertFalse(projection.promptNote().contains("abcdefghijklmnop"))
    }

    @Test fun fileHashCatchesMutationEvenWhenProjectClockDoesNotAdvance() {
        val projects = WorkspaceProjectStore(temp.newFolder("fixed-clock"), nowMillis = { 1000L },
            idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        files.createFile("site", "style.css")
        files.saveFile("site", "index.html", """<link href="style.css">""")
        files.saveFile("site", "style.css", "body{}")
        val projection = WorkspaceProjectContext.build(
            files, projects, "site", "index.html", "Improve style")
        assertTrue(WorkspaceProjectContext.stillCurrent(files, projects, projection))
        files.saveFile("site", "style.css", "body{margin:1rem}")
        assertFalse(WorkspaceProjectContext.stillCurrent(files, projects, projection))
    }

    @Test fun projectMutationInvalidatesPreparedContextWithoutSecondStore() {
        val s = fixture()
        val projection = WorkspaceProjectContext.build(
            s.files, s.projects, "site", "index.html", "Improve homepage")
        assertTrue(WorkspaceProjectContext.stillCurrent(s.files, s.projects, projection))
        s.files.saveFile("site", "style.css", "body { margin: 1rem }")
        assertFalse(WorkspaceProjectContext.stillCurrent(s.files, s.projects, projection))
    }
}
