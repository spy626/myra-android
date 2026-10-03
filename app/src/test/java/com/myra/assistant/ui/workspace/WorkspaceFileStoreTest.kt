package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(): Triple<WorkspaceProjectStore, WorkspaceFileStore, WorkspaceProject> {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), nowMillis = { 1000L }, idFactory = { "safe_project_1" })
        val project = projects.createProject("My Website", WorkspaceProjectType.WEBSITE)
        return Triple(projects, WorkspaceFileStore(projects), project)
    }

    @Test fun createEditSaveReopenKeepsSameProjectAndActiveFile() {
        val (projects, files, project) = fixture()
        files.createFolder(project.projectId, "src")
        files.createFile(project.projectId, "src/main.js")
        files.saveFile(project.projectId, "src/main.js", "const hello = 'Hi';\n")
        files.rememberActive(project.projectId, "src/main.js")
        val reopenedProjects = WorkspaceProjectStore(projects.projectRoot(project.projectId).parentFile)
        val reopenedFiles = WorkspaceFileStore(reopenedProjects)
        assertEquals("src/main.js", reopenedProjects.getProject(project.projectId)?.activeFilePath)
        assertEquals("const hello = 'Hi';\n", reopenedFiles.readFile(project.projectId, "src/main.js"))
        assertEquals(listOf("src", "src/main.js"), reopenedFiles.list(project.projectId).map { it.path })
    }

    @Test fun traversalAndProtectedMetadataAreRejected() {
        val (projects, files, project) = fixture()
        val id = project.projectId
        listOf("../outside", "src/../outside", "/tmp/hack", ".lyra/project.json", "src\\bad.js", "src//bad.js").forEach { invalid ->
            assertTrue(runCatching { files.createFile(id, invalid) }.isFailure)
        }
        assertTrue(File(projects.projectRoot(id), ".lyra/project.json").isFile)
        assertTrue(files.list(id).isEmpty())
    }

    @Test fun directoryRenameUpdatesActiveFileAndDeleteClearsIt() {
        val (projects, files, project) = fixture()
        val id = project.projectId
        files.createFolder(id, "src")
        files.createFile(id, "src/app.js")
        files.saveFile(id, "src/app.js", "saved")
        files.rememberActive(id, "src/app.js")
        files.rename(id, "src", "source")
        assertEquals("source/app.js", projects.getProject(id)?.activeFilePath)
        assertEquals("saved", files.readFile(id, "source/app.js"))
        files.delete(id, "source")
        assertNull(projects.getProject(id)?.activeFilePath)
        assertTrue(files.list(id).isEmpty())
    }

    @Test fun starterFilesNeverOverwriteExistingUserCode() {
        val (_, files, project) = fixture()
        val id = project.projectId
        files.addWebsiteStarter(id)
        files.saveFile(id, "index.html", "MY OWN CODE")
        assertTrue(runCatching { files.addWebsiteStarter(id) }.isFailure)
        assertEquals("MY OWN CODE", files.readFile(id, "index.html"))
        assertEquals(3, files.list(id).size)
    }

    @Test fun largeOrInvalidUtf8FilesDoNotOverwriteOnSave() {
        val (projects, files, project) = fixture()
        val id = project.projectId
        files.createFile(id, "sample.txt")
        files.saveFile(id, "sample.txt", "original")
        assertTrue(runCatching { files.saveFile(id, "sample.txt", "x".repeat(WorkspaceFileStore.MAX_FILE_BYTES + 1)) }.isFailure)
        assertEquals("original", files.readFile(id, "sample.txt"))
        File(projects.projectRoot(id), "sample.txt").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(runCatching { files.readFile(id, "sample.txt") }.isFailure)
    }

    @Test fun symlinkCannotEscapeTheProjectRoot() {
        val (projects, files, project) = fixture()
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("private")
        val link = File(projects.projectRoot(project.projectId), "outside-link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(runCatching { files.readFile(project.projectId, "outside-link/secret.txt") }.isFailure)
        assertTrue(runCatching { files.delete(project.projectId, "outside-link") }.isFailure)
        assertFalse(files.list(project.projectId).any { it.path == "outside-link" })
        assertEquals("private", File(outside, "secret.txt").readText())
    }
}
