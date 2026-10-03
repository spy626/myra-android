package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceDeleteAndRateLimitUxTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rateLimitReportsWaitAndNeverPromisesPaidRescueOrDailyExhaustion() {
        val message = WorkspaceCodingResult.failure(WorkspaceFreeAiSuggestion.httpFailure(429, "17"))
        assertTrue(message.contains("HTTP 429"))
        assertTrue(message.contains("17 seconds"))
        assertTrue(message.contains("try later"))
        assertTrue(message.contains("does not prove your daily quota is exhausted"))
        assertEquals(1, Regex("No paid fallback").findAll(message).count())
        assertFalse(message.contains("automatic paid"))
    }

    @Test fun projectDeletionRemainsScopedByIdAndLeavesUnrelatedProjectUntouched() {
        val root = temp.newFolder("projects")
        val ids = ArrayDeque(listOf("first_project", "second_project"))
        val projects = WorkspaceProjectStore(root, idFactory = { ids.removeFirst() })
        val first = projects.createProject("Minicoy Site", WorkspaceProjectType.WEBSITE)
        val second = projects.createProject("Keep Me", WorkspaceProjectType.WEBSITE)
        File(projects.projectRoot(first.projectId), "index.html").writeText("delete only this")
        val unrelated = File(projects.projectRoot(second.projectId), "index.html")
        unrelated.writeText("preserve this")

        assertTrue(projects.deleteProject(first.projectId))
        assertNull(projects.getProject(first.projectId))
        assertEquals("preserve this", unrelated.readText())
        assertEquals(second.projectId, projects.getProject(second.projectId)?.projectId)
        assertFalse(projects.deleteProject(first.projectId))
    }
}
