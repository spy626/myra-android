package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceWebsiteLongBriefTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun phoneTestLengthAndMultilineBriefSurviveTaskStorageAndBothFreeRequestBodies() {
        val brief = "Minicoy tourism website with three compact cards.\n" +
            ("Preserve working button and layout; no empty image area. ".repeat(14)) +
            "\nFinal requirement: Lighthouse, Beaches and Local Food."
        assertTrue(brief.length > 500)
        assertEquals(brief, WorkspaceTaskContract.normalizeGoal(brief, WorkspaceProjectType.WEBSITE))
        val projects = WorkspaceProjectStore(temp.newFolder(), idFactory = { "site" })
        projects.createProject("Minicoy", WorkspaceProjectType.WEBSITE)
        val tasks = WorkspaceTaskStore(projects, idFactory = { "task" }, revisionFactory = { "revision" })
        val task = tasks.create("site", brief, rawAcceptanceCriteria = "Inspect saved Preview")
        assertEquals(brief, task.goal)
        val reopened = WorkspaceTaskStore(projects).get("site")!!
        assertEquals(brief, reopened.goal)
        tasks.setSpecificationApproved("site", reopened.taskId, WorkspaceTaskContract.specToken(reopened), true)
        val snapshot = WorkspaceWebsiteGeneration.prepare(WorkspaceFileStore(projects), tasks, projects, "site")
        fun goal(body: okhttp3.Request): String {
            val buffer = Buffer()
            requireNotNull(body.body).writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            return payload.getJSONArray("messages").getJSONObject(1)
                .getString("content").let { JSONObject(it).getString("goal") }
        }
        assertEquals(brief, goal(WorkspaceWebsiteGeneration.request("openrouter-test", snapshot)))
        assertEquals(brief, goal(WorkspaceWebsiteGroqFallback.request("groq-test", snapshot)))
    }

    @Test fun fullChatSizeIsAllowedLocallyButOverBudgetProviderRequestIsRejectedWithoutTruncation() {
        val huge = "A".repeat(WorkspaceTaskContract.MAX_WEBSITE_GOAL_LENGTH)
        assertEquals(huge, WorkspaceTaskContract.normalizeGoal(huge, WorkspaceProjectType.WEBSITE))
        assertTrue(runCatching {
            WorkspaceTaskContract.normalizeGoal(huge + "x", WorkspaceProjectType.WEBSITE)
        }.isFailure)
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTaskContract.normalizeGoal("A".repeat(501)) // Android unchanged
        }
        val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", huge,
            mapOf("index.html" to "B".repeat(8000),
                "style.css" to "C".repeat(8000), "script.js" to "D".repeat(8000)))
        // If this particular future envelope exceeds the bounded free context, never trim.
        val request = WorkspaceWebsiteGeneration.request("openrouter-test", snapshot)
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        assertTrue(buffer.readUtf8().contains(huge))
        assertTrue(runCatching {
            WorkspaceWebsiteGeneration.request("openrouter-test", snapshot.copy(
                goal = huge + "E".repeat(40000)))
        }.isFailure)
    }
}
