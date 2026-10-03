package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

class WorkspaceSourcePreviewTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun chosenSourceIsBoundedAndDoesNotMutateTaskOrProject() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task_one" })
        tasks.create("site", "Build site", rawAcceptanceCriteria = "Responsive layout")
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "index.html")
        val text = "<h1>Hello</h1>\n".repeat(200)
        files.saveFile("site", "index.html", text)
        val metadata = File(projects.projectRoot("site"), ".lyra/task.json")
        val before = metadata.readBytes()

        assertEquals(listOf("index.html"), WorkspaceSourcePreview.choices(files, "site"))
        val preview = WorkspaceSourcePreview.read(files, "site", "index.html")
        assertEquals("index.html", preview.path)
        assertTrue(preview.truncated)
        assertEquals(WorkspaceSourcePreview.MAX_PREVIEW_CHARS, preview.excerpt.length)
        assertEquals(text.take(WorkspaceSourcePreview.MAX_PREVIEW_CHARS), preview.excerpt)
        assertTrue(preview.displayText().contains("No source modified, provider upload, AI run or verification"))
        assertEquals(text, files.readFile("site", "index.html"))
        assertArrayEquals(before, metadata.readBytes())
    }

    @Test fun excludesOtherProjectsMetadataAndLinksAndRejectsStaleChoice() {
        val ids = ArrayDeque(listOf("site", "other"))
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { ids.removeFirst() })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        projects.createProject("Other", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        files.createFile("site", "app.js")
        files.saveFile("site", "app.js", "safe")
        files.createFile("other", "private.txt")
        files.saveFile("other", "private.txt", "private")
        val outside = temp.newFolder("outside")
        File(outside, "token.txt").writeText("secret")
        Files.createSymbolicLink(File(projects.projectRoot("site"), "link").toPath(), outside.toPath())

        assertEquals(listOf("app.js"), WorkspaceSourcePreview.choices(files, "site"))
        listOf(".lyra/task.json", "private.txt", "link/token.txt", "../other/private.txt").forEach { path ->
            assertTrue(path, runCatching { WorkspaceSourcePreview.read(files, "site", path) }.isFailure)
        }
        files.rename("site", "app.js", "renamed.js")
        assertTrue(runCatching { WorkspaceSourcePreview.read(files, "site", "app.js") }.isFailure)
        assertEquals("safe", WorkspaceSourcePreview.read(files, "site", "renamed.js").excerpt)
        assertEquals("secret", File(outside, "token.txt").readText())
    }

    @Test fun limitsSelectableFilesRejectsControlTextAndLargeFiles() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), idFactory = { "site" })
        projects.createProject("Site", WorkspaceProjectType.WEBSITE)
        val files = WorkspaceFileStore(projects)
        repeat(22) { files.createFile("site", "file_%02d.txt".format(it)) }
        val choices = WorkspaceSourcePreview.choices(files, "site")
        assertEquals(20, choices.size)
        val excluded = files.list("site").map { it.path }.first { it !in choices }
        assertTrue(runCatching { WorkspaceSourcePreview.read(files, "site", excluded) }.isFailure)

        files.saveFile("site", choices.first(), "hello\u0000world")
        assertTrue(runCatching { WorkspaceSourcePreview.read(files, "site", choices.first()) }.isFailure)
        File(projects.projectRoot("site"), choices[1]).writeBytes(ByteArray(WorkspaceFileStore.MAX_FILE_BYTES + 1))
        assertTrue(runCatching { WorkspaceSourcePreview.read(files, "site", choices[1]) }.isFailure)
        files.saveFile("site", choices[2], "")
        assertFalse(WorkspaceSourcePreview.read(files, "site", choices[2]).truncated)
        assertTrue(WorkspaceSourcePreview.read(files, "site", choices[2]).displayText().contains("(Empty file)"))
    }
}
