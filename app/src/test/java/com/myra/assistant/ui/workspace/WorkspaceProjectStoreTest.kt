package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ArrayDeque

class WorkspaceProjectStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createPersistReopenKeepsStableIdentity() {
        val root = temporaryFolder.newFolder("projects")
        var now = 1_000L
        val id = "project_12345678"
        val store = WorkspaceProjectStore(
            projectsRoot = root,
            nowMillis = { now },
            idFactory = { id },
        )

        val created = store.createProject("  Minicoy   Market  ", WorkspaceProjectType.WEBSITE)
        assertEquals(id, created.projectId)
        assertEquals("Minicoy Market", created.name)
        assertEquals(WorkspaceProjectType.WEBSITE, created.type)
        assertEquals(id, created.rootRelativePath)
        assertTrue(File(root, "$id/.lyra/project.json").isFile)

        val reloadedStore = WorkspaceProjectStore(root, nowMillis = { now + 1_000L })
        val reloaded = reloadedStore.getProject(id)
        assertNotNull(reloaded)
        assertEquals(created, reloaded)

        now = 3_000L
        val openedStore = WorkspaceProjectStore(root, nowMillis = { now })
        val opened = openedStore.markOpened(id)
        assertNotNull(opened)
        assertEquals(3_000L, opened!!.lastOpenedAtMs)
        assertEquals(created.updatedAtMs, opened.updatedAtMs)
        assertEquals(id, openedStore.listProjects().single().projectId)
    }

    @Test
    fun listProjectsUsesManifestTruthAndRecentOrder() {
        val root = temporaryFolder.newFolder("ordered-projects")
        var now = 100L
        val ids = ArrayDeque(listOf("web_project_1", "android_project_2"))
        val store = WorkspaceProjectStore(
            projectsRoot = root,
            nowMillis = { now },
            idFactory = { ids.removeFirst() },
        )

        val website = store.createProject("Website", WorkspaceProjectType.WEBSITE)
        now = 200L
        val android = store.createProject("Android", WorkspaceProjectType.ANDROID_APP)
        assertEquals(listOf(android.projectId, website.projectId), store.listProjects().map { it.projectId })

        now = 300L
        store.markOpened(website.projectId)
        assertEquals(listOf(website.projectId, android.projectId), store.listProjects().map { it.projectId })
    }

    @Test
    fun corruptedOrEscapingManifestIsIgnored() {
        val root = temporaryFolder.newFolder("corrupt-projects")
        val id = "safe_project_1"
        val store = WorkspaceProjectStore(root, nowMillis = { 1_000L }, idFactory = { id })
        store.createProject("Safe", WorkspaceProjectType.WEBSITE)

        val manifest = File(root, "$id/.lyra/project.json")
        val json = JSONObject(manifest.readText())
            .put("rootRelativePath", "../outside")
        manifest.writeText(json.toString())

        assertNull(store.getProject(id))
        assertTrue(store.listProjects().isEmpty())
        assertFalse(store.deleteProject(id))
        assertTrue(File(root, id).exists())
    }

    @Test
    fun deleteRemovesOnlyValidatedWorkspaceProject() {
        val root = temporaryFolder.newFolder("delete-projects")
        val id = "delete_project_1"
        val store = WorkspaceProjectStore(root, nowMillis = { 1_000L }, idFactory = { id })
        store.createProject("Delete Me", WorkspaceProjectType.ANDROID_APP)
        File(root, "$id/example.txt").writeText("project file")

        assertTrue(store.deleteProject(id))
        assertFalse(File(root, id).exists())
        assertTrue(store.listProjects().isEmpty())
    }

    @Test
    fun blankProjectNameIsRejectedBeforeCreatingRoot() {
        val root = temporaryFolder.newFolder("invalid-name")
        val store = WorkspaceProjectStore(root, nowMillis = { 1_000L }, idFactory = { "unused_project" })

        var thrown = false
        try {
            store.createProject("   ", WorkspaceProjectType.WEBSITE)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }

        assertTrue(thrown)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
}
