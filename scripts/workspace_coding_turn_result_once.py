#!/usr/bin/env python3
"""Narrow phase-branch change: persist coding outcomes and summarize verified file writes."""
from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace')

def edit(file, old, new):
    p = ROOT / file
    s = p.read_text(encoding='utf-8')
    if s.count(old) != 1:
        raise SystemExit(f'Unexpected source for {file}: {old[:60]!r}; abort')
    p.write_text(s.replace(old, new), encoding='utf-8')

edit('WorkspaceConversationStore.kt',
'''    @Synchronized fun append(projectId: String, role: String, text: String): Message {
''',
'''    /** Attach a coding outcome only to its exact user turn; never overwrite newer work.
     * Reattempts of the same turn replace only that turn's preceding assistant outcome.
     */
    @Synchronized fun completeCodingTurn(projectId: String, expectedUserId: String, text: String): Message {
        require(projects.getProject(projectId)?.type != WorkspaceProjectType.CHAT) {
            "Coding result requires an existing project"
        }
        val content = checkedText(text)
        val history = read(projectId)
        val last = history.lastOrNull()
        if (last?.role == "user" && last.id == expectedUserId) {
            val saved = Message(UUID.randomUUID().toString(), "assistant", content, now())
            write(projectId, (history + saved).takeLast(MAX_MESSAGES))
            return saved
        }
        if (last?.role == "assistant" && history.getOrNull(history.lastIndex - 1)?.let {
                it.role == "user" && it.id == expectedUserId
            } == true) {
            val updated = last.copy(text = content)
            write(projectId, history.dropLast(1) + updated)
            return updated
        }
        error("Coding turn changed; stale result was not added to chat")
    }

    @Synchronized fun append(projectId: String, role: String, text: String): Message {
''')

edit('WorkspaceChatCodingFlow.kt',
'''    private val activeProject: () -> String?,
    private val report: (String) -> Unit,
''',
'''    private val activeProject: () -> String?,
    private val report: (String) -> Unit,
    private val onCompleted: (String, String, String) -> Unit,
''')
edit('WorkspaceChatCodingFlow.kt',
'''    private var request: Call? = null
    val isRunning: Boolean get() = request != null
''',
'''    private var request: Call? = null
    private var activeTurn: Pair<String, String>? = null
    val isRunning: Boolean get() = request != null
''')
edit('WorkspaceChatCodingFlow.kt',
'''        request?.cancel()
        request = null
    }

    private fun error(message: String) = report(message)
''',
'''        request?.cancel()
        request = null
        activeTurn = null
    }

    /** Source writes are finished before the result enters the private transcript. */
    private fun terminal(message: String, status: String = message) {
        val turn = activeTurn
        activeTurn = null
        val result = if (turn == null) Result.success(Unit)
            else runCatching { onCompleted(turn.first, turn.second, message) }
        if (result.isFailure) report("Coding result could not be saved in Chat: " +
            "${result.exceptionOrNull()?.message}. Check Work files before retrying.")
        else report(status)
    }
    private fun error(message: String) = terminal(WorkspaceCodingResult.failure(message), message)
''')
edit('WorkspaceChatCodingFlow.kt',
'''    fun continueRequest(id: String, instruction: String) {
''',
'''    fun continueRequest(id: String, instruction: String, userMessageId: String? = null) {
''')
edit('WorkspaceChatCodingFlow.kt',
'''        if (isRunning) { report("A coding request is already running."); return }
        if (project.type == WorkspaceProjectType.WEBSITE) {
''',
'''        if (isRunning) { report("A coding request is already running."); return }
        activeTurn = userMessageId?.let { id to it }
        if (project.type == WorkspaceProjectType.WEBSITE) {
''')
edit('WorkspaceChatCodingFlow.kt',
'''                    report("Saved index.html, style.css and script.js. Preview shows the saved project; " +
                        "Undo is available in Chat. Visual correctness still needs your check.")
                    activity.startActivity(WorkspacePreviewActivity.intent(activity, id))
''',
'''                    terminal(WorkspaceCodingResult.websiteSuccess(snapshot.original, generated))
                    activity.startActivity(WorkspacePreviewActivity.intent(activity, id))
''')
edit('WorkspaceChatCodingFlow.kt',
'''                    report("One scoped file changed with protected rollback. Check Preview and use Undo / Keep in Chat. A complete website or verified build is not claimed.")
''',
'''                    terminal("Updated ${prepared.context.path} in your existing project. " +
                        "Review the file and use Undo / Keep in Chat. Preview/build is not verified.")
''')

edit('WorkspaceActivity.kt',
'''    private val coding by lazy {
        WorkspaceChatCodingFlow(this, projects, files, tasks, suggestions, keys,
            activeProject = { selectedId }, report = { message ->
''',
'''    private val coding by lazy {
        WorkspaceChatCodingFlow(this, projects, files, tasks, suggestions, keys,
            activeProject = { selectedId },
            onCompleted = { id, userId, summary ->
                conversations.completeCodingTurn(id, userId, summary)
            }, report = { message ->
''')
edit('WorkspaceActivity.kt',
'''            coding.continueRequest(id, text)
            return
''',
'''            coding.continueRequest(id, text, stored.id)
            return
''')
edit('WorkspaceActivity.kt',
'''        val latest = runCatching { conversations.read(id).lastOrNull() }.getOrNull() ?: return
        if (latest.role != "user" || latest.id != target.second) return
        // Never retry into a pending edit; canonical source freshness checks still apply.
        if (runCatching { WorkspaceScopedEdit.pending(projects, id) }.getOrNull() != null) return
''',
'''        val history = runCatching { conversations.read(id) }.getOrNull() ?: return
        val latest = history.lastOrNull() ?: return
        val original = if (latest.role == "user") latest else
            history.getOrNull(history.lastIndex - 1)?.takeIf { latest.role == "assistant" }
        if (original == null || original.role != "user" || original.id != target.second) return
        // Never retry into a pending edit; canonical source freshness checks still apply.
        if (runCatching { WorkspaceScopedEdit.pending(projects, id) }.getOrNull() != null ||
            runCatching { WorkspaceWebsiteGeneration.pending(projects, id) }.getOrNull() != null) return
''')
edit('WorkspaceActivity.kt',
'''            .setPositiveButton("Retry once") { _, _ ->
                if (selectedId == id && !workTab && !isBusy() &&
                    runCatching { conversations.read(id).lastOrNull()?.id == latest.id }.getOrDefault(false))
                    coding.continueRequest(id, latest.text)
            }.show()
''',
'''            .setPositiveButton("Retry once") { _, _ ->
                if (selectedId == id && !workTab && !isBusy() &&
                    runCatching { conversations.read(id).lastOrNull()?.id == latest.id }.getOrDefault(false)) {
                    // Explicit retry creates a new user turn and keeps the previous failure visible.
                    runCatching { conversations.append(id, "user", original.text) }
                        .onSuccess { retry ->
                            codingRetryTarget = id to retry.id
                            render()
                            coding.continueRequest(id, retry.text, retry.id)
                        }.onFailure { toast("Could not save retry; no request was sent") }
                }
            }.show()
''')

(ROOT / 'WorkspaceCodingResult.kt').write_text('''package com.myra.assistant.ui.workspace

/** Honest, source-derived summaries: no invented claims of visual or phone testing. */
internal object WorkspaceCodingResult {
    fun websiteSuccess(previous: Map<String, String?>, generated: Map<String, String>): String {
        val changed = WorkspaceWebsiteGeneration.PATHS.filter { previous[it] != generated[it] }
        val files = if (changed.isEmpty())
            "Generated website matches the previous saved files; no content changes."
        else "Website files saved: ${changed.joinToString(", ")}."
        val titles = Regex("(?is)<h[1-6]\\\\b[^>]*>(.*?)</h[1-6]\\\\s*>")
            .findAll(generated["index.html"].orEmpty()).map { match ->
                match.groupValues[1].replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\\\s+"), " ").trim().take(65)
            }.filter { it.isNotEmpty() && it.none(Char::isISOControl) }
            .distinct().take(5).toList()
        val headings = if (titles.isEmpty()) "" else "\\nSaved page headings: ${titles.joinToString(", ")}."
        return "$files$headings\\nWork → Preview mein result check karo. " +
            "Review website · Undo / Keep sirf pending change ke liye hai. " +
            "Visual aur button testing aapko phone par confirm karni hai."
    }

    fun failure(reason: String): String = "LYRA coding request complete nahi kar paayi: $reason " +
        "Work → Files mein current project check karo. Agar Review edit/website dikhe " +
        "toh Undo ya Keep ke baad same instruction dobara bhej sakte ho. " +
        "No paid fallback."
}
''', encoding='utf-8')

(TEST / 'WorkspaceCodingResultTest.kt').write_text('''package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodingResultTest {
    @Test fun summaryListsOnlyActualChangedPathsAndHtmlHeadings() {
        val before = mapOf("index.html" to "<html>old</html>", "style.css" to "body{}", "script.js" to "ok()")
        val generated = mapOf("index.html" to "<html><body><h1>Welcome to Minicoy</h1>" +
            "<h2>Things to Explore</h2></body></html>", "style.css" to "body{}", "script.js" to "ok()")
        val reply = WorkspaceCodingResult.websiteSuccess(before, generated)
        assertTrue(reply.contains("index.html"))
        assertFalse(reply.contains("style.css"))
        assertFalse(reply.contains("script.js"))
        assertTrue(reply.contains("Things to Explore"))
        assertTrue(reply.contains("Preview"))
        assertFalse(reply.contains("tested successfully"))
    }
    @Test fun unchangedWebsiteDoesNotClaimNewEdit() {
        val files = WorkspaceWebsiteGeneration.PATHS.associateWith { "same" }
        assertTrue(WorkspaceCodingResult.websiteSuccess(files, files).contains("no content changes"))
    }
    @Test fun timeoutIsFailureNotSuccess() {
        val reply = WorkspaceCodingResult.failure("Website provider timed out")
        assertTrue(reply.contains("timed out"))
        assertTrue(reply.contains("dobara"))
        assertFalse(reply.contains("Website files saved"))
    }
}
''', encoding='utf-8')

(TEST / 'WorkspaceCodingTurnResultTest.kt').write_text('''package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceCodingTurnResultTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun resultBelongsOnlyToExactCodingTurnAndCanReplaceItsFailure() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "website_chat" })
        val id = projects.createProject("Build Minicoy site", WorkspaceProjectType.WEBSITE).projectId
        val chat = WorkspaceConversationStore(projects, temp.newFolder("chats"))
        val user = chat.append(id, "user", "Build my website")
        val first = chat.completeCodingTurn(id, user.id, "Timed out. Try again")
        val replaced = chat.completeCodingTurn(id, user.id, "Website files saved")
        assertEquals(first.id, replaced.id)
        assertEquals(listOf("Build my website", "Website files saved"), chat.read(id).map { it.text })
        val newest = chat.append(id, "user", "Add a section")
        assertThrows(IllegalStateException::class.java) {
            chat.completeCodingTurn(id, user.id, "Stale result")
        }
        chat.completeCodingTurn(id, newest.id, "Updated files")
        assertEquals("Updated files", chat.read(id).last().text)
    }
    @Test fun ordinaryChatNeverAcceptsCodingOutcome() {
        val projects = WorkspaceProjectStore(temp.newFolder("projects"), { 1000L }, { "normal_chat" })
        val id = projects.createProject("Hi", WorkspaceProjectType.CHAT).projectId
        val chat = WorkspaceConversationStore(projects, temp.newFolder("chats"))
        val user = chat.append(id, "user", "Hi")
        assertThrows(IllegalArgumentException::class.java) {
            chat.completeCodingTurn(id, user.id, "Not a coding result")
        }
    }
}
''', encoding='utf-8')
assert 'coding.continueRequest(id, text, stored.id)' in (ROOT / 'WorkspaceActivity.kt').read_text()
print('Applied coding-turn replies, source-derived summaries, timeout guidance, safe retry and tests.')
