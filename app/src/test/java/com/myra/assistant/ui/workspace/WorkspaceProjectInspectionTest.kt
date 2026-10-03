package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

class WorkspaceProjectInspectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun inspectsOnlyProjectLocalNamesWithoutChangingFiles() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "one" })
        projects.createProject("One", WorkspaceProjectType.WEBSITE)
        WorkspaceTaskStore(projects, idFactory = { "task_one" }).create("one", "Make site", rawAcceptanceCriteria = "Cards fit")
        val files = WorkspaceFileStore(projects)
        files.createFolder("one", "src")
        files.createFile("one", "src/app.js")
        files.saveFile("one", "src/app.js", "original code")
        val metadata = File(projects.projectRoot("one"), ".lyra/task.json")
        val metadataBefore = metadata.readBytes()

        val snapshot = WorkspaceProjectInspection.scan(files, "one")
        assertEquals(1, snapshot.fileCount)
        assertEquals(1, snapshot.folderCount)
        assertEquals(listOf("DIR   src", "FILE  src/app.js"), snapshot.shownPaths)
        assertEquals(0, snapshot.hiddenListedPaths)
        assertTrue(snapshot.displayText().contains("No contents read, code edited, AI run or result verified"))
        assertFalse(snapshot.displayText().contains(".lyra"))
        assertEquals("original code", files.readFile("one", "src/app.js"))
        assertArrayEquals(metadataBefore, metadata.readBytes())
    }

    @Test fun doesNotShowOtherProjectsMetadataOrSymlinkTargets() {
        val ids = ArrayDeque(listOf("one", "two"))
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { ids.removeFirst() })
        projects.createProject("One", WorkspaceProjectType.WEBSITE)
        projects.createProject("Two", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        files.createFile("two", "private.txt")
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")
        Files.createSymbolicLink(File(projects.projectRoot("one"), "link").toPath(), outside.toPath())
        val snapshot = WorkspaceProjectInspection.scan(files, "one")
        assertEquals(0, snapshot.fileCount)
        assertTrue(snapshot.shownPaths.isEmpty())
        assertFalse(snapshot.displayText().contains("secret"))
        assertFalse(snapshot.displayText().contains("private.txt"))
        assertEquals("secret", File(outside, "secret.txt").readText())
    }

    @Test fun largeListingIsBoundedForPhoneDisplay() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "one" })
        projects.createProject("One", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        repeat(25) { files.createFile("one", "file_$it.txt") }
        val snapshot = WorkspaceProjectInspection.scan(files, "one")
        assertEquals(25, snapshot.fileCount)
        assertEquals(20, snapshot.shownPaths.size)
        assertEquals(5, snapshot.hiddenListedPaths)
        assertTrue(snapshot.displayText().contains("+5 other listed paths"))
        assertTrue(snapshot.displayText().contains("additional or deeper paths may exist"))
    }
}
