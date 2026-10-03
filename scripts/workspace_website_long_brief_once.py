#!/usr/bin/env python3
"""Fix the phone-observed 500-char WEBSITE rejection without widening Android code edits."""
from pathlib import Path

BASE = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteLongBriefTest.kt')

def replace_once(path: Path, before: str, after: str):
    text = path.read_text(encoding='utf-8')
    assert text.count(before) == 1, f'{path}: expected exactly one unmodified anchor (found {text.count(before)})'
    path.write_text(text.replace(before, after, 1), encoding='utf-8')

contract = BASE / 'WorkspaceTaskContract.kt'
replace_once(contract,
    '    const val MAX_GOAL_LENGTH = 500\n    const val MAX_ACCEPTANCE_LENGTH = 500',
    '''    // Legacy Android/single-file spec contract remains conservative. Website goals use
    // the same full, untruncated text budget as the Workspace Chat message that sent them.
    const val MAX_GOAL_LENGTH = 500
    const val MAX_WEBSITE_GOAL_LENGTH = WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS
    const val MAX_ACCEPTANCE_LENGTH = 500''')
replace_once(contract,
    '''    /** An empty criterion means the specification is incomplete, never that verification passed. */''',
    '''    /** A website request is an entire design brief, not a 500-character file edit.
     * Preserve line breaks and all meaningful text for the saved spec and model request.
     * The full user message also stays in Chat. No silent summary, truncation or chunk loss.
     */
    fun normalizeGoal(raw: String, type: WorkspaceProjectType): String {
        if (type != WorkspaceProjectType.WEBSITE) return normalizeGoal(raw)
        val clean = raw.replace("\\r\\n", "\\n").replace('\\r', '\\n').trim()
        require(clean.isNotBlank()) { "Describe what you want to build or change" }
        require(clean.length <= MAX_WEBSITE_GOAL_LENGTH &&
            clean.none { it.isISOControl() && it != '\\n' && it != '\\t' }) {
            "Website brief exceeds the 64,000-character local message capacity or contains invalid controls; " +
                "full text remains in Chat and no text was shortened"
        }
        return clean
    }

    /** An empty criterion means the specification is incomplete, never that verification passed. */''')

store = BASE / 'WorkspaceTaskStore.kt'
replace_once(store,
    '''        if (projects.getProject(projectId) == null) return null
        val file = taskFile(projectId)''',
    '''        val project = projects.getProject(projectId) ?: return null
        val file = taskFile(projectId)''')
replace_once(store,
    '''        val goal = WorkspaceTaskContract.normalizeGoal(json.getString("goal"))''',
    '''        val goal = WorkspaceTaskContract.normalizeGoal(json.getString("goal"), project.type)''')
replace_once(store,
    '''        requireNotNull(projects.getProject(projectId)) { "Project is unavailable" }
        val goal = WorkspaceTaskContract.normalizeGoal(rawGoal)''',
    '''        val project = requireNotNull(projects.getProject(projectId)) { "Project is unavailable" }
        val goal = WorkspaceTaskContract.normalizeGoal(rawGoal, project.type)''')

flow = BASE / 'WorkspaceChatCodingFlow.kt'
replace_once(flow,
    '''        if (instruction.length > WorkspaceTaskContract.MAX_GOAL_LENGTH) {
            error("Website request exceeds 500 characters; shorten it without removing important details.")
            return
        }
        val openRouterKey =''',
    '''        // Validate the full website brief before changing a pending backup or task.
        // Ordinary Android single-file edits retain their separate 500-character contract.
        val fullBrief = runCatching {
            WorkspaceTaskContract.normalizeGoal(instruction, WorkspaceProjectType.WEBSITE)
        }.getOrElse {
            error("Website brief could not be accepted: ${it.message}")
            return
        }
        val openRouterKey =''')
replace_once(flow,
    '''            val saved = tasks.create(id, instruction, replaceExisting = previous != null,
                rawAcceptanceCriteria = criteria)''',
    '''            val saved = tasks.create(id, fullBrief, replaceExisting = previous != null,
                rawAcceptanceCriteria = criteria)''')

activity = BASE / 'WorkspaceTaskActivity.kt'
replace_once(activity,
    '''        project = found
        installLocalPlanUi()''',
    '''        project = found
        // XML defaults to 500 for Android tasks; long website goals must remain fully
        // editable on the Task screen too, including when reopening a saved brief.
        if (project.type == WorkspaceProjectType.WEBSITE) {
            binding.taskGoalInput.filters = arrayOf(
                InputFilter.LengthFilter(WorkspaceTaskContract.MAX_WEBSITE_GOAL_LENGTH))
        }
        installLocalPlanUi()''')
replace_once(activity,
    '''WorkspaceTaskContract.normalizeGoal(binding.taskGoalInput.text.toString())''',
    '''WorkspaceTaskContract.normalizeGoal(binding.taskGoalInput.text.toString(), project.type)''')

groq = BASE / 'WorkspaceWebsiteGroqFallback.kt'
replace_once(groq,
    '''    private const val MAX_PROMPT_CHARS = 12_000''',
    '''    // This is a bounded remote envelope, not the historical 500-character task cap.
    private const val MAX_PROMPT_CHARS = WorkspaceLongInputPolicy.MAX_REQUEST_CHARS''')

generation = BASE / 'WorkspaceWebsiteGeneration.kt'
replace_once(generation,
    '''        context.put("existingFiles", source)
        val instruction =''',
    '''        context.put("existingFiles", source)
        val instruction =''') if False else None
replace_once(generation,
    '''        val payload = JSONObject().put("model", WorkspaceFreeAiSuggestion.MODEL)''',
    '''        val contextText = context.toString()
        require(instruction.length.toLong() + contextText.length <= WorkspaceLongInputPolicy.MAX_REQUEST_CHARS) {
            "Complete website brief plus current project source exceeds the free-provider request budget. " +
                "No words were dropped and no project files changed. Reduce existing source or split into follow-ups."
        }
        val payload = JSONObject().put("model", WorkspaceFreeAiSuggestion.MODEL)''')
replace_once(generation,
    '''                .put(JSONObject().put("role", "user").put("content", context.toString())))''',
    '''                .put(JSONObject().put("role", "user").put("content", contextText)))''')

assert not TEST.exists(), 'Refusing to overwrite an existing long-brief regression test'
TEST.write_text('''package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceWebsiteLongBriefTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun phoneTestLengthAndMultilineBriefSurviveTaskStorageAndBothFreeRequestBodies() {
        val brief = "Minicoy tourism website with three compact cards.\\n" +
            ("Preserve working button and layout; no empty image area. ".repeat(14)) +
            "\\nFinal requirement: Lighthouse, Beaches and Local Food."
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
''', encoding='utf-8')
print('Staged website full-brief fix in TaskContract/Store, coding flow, task UI, both free request paths and regression tests.')
