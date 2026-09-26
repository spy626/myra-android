#!/usr/bin/env python3
"""Single-use, exact-anchored integration on agent/myra-phase-1 only.
Refuse unknown source rather than silently modifying an unrelated working feature.
"""
from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')


def change(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected one exact anchor, found {count}; abort')
    path.write_text(text.replace(old, new), encoding='utf-8')


flow = ROOT / 'WorkspaceChatCodingFlow.kt'
change(flow, '''        if (isRunning) { report("A coding request is already running."); return }
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, id) }
''', '''        if (isRunning) { report("A coding request is already running."); return }
        if (project.type == WorkspaceProjectType.WEBSITE) {
            continueWebsite(id, instruction)
            return
        }
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, id) }
''')

change(flow, '''    private fun prepareSource(id: String, instruction: String, key: String) {
''', '''    /** Explicit 'build website' Send authorizes generation of the three named project files.
     * The AI proposes text; the local file owner checks snapshots and saves a durable Undo.
     * No additional per-file permission popup is needed for this requested website build.
     */
    private fun continueWebsite(id: String, instruction: String) {
        if (instruction.length > WorkspaceTaskContract.MAX_GOAL_LENGTH) {
            error("Website request exceeds 500 characters; shorten it without removing important details.")
            return
        }
        val key = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure OpenRouter key unavailable; nothing was shared."); return }
        if (key.isBlank()) {
            error("Website request saved locally. Add an OpenRouter Free key in API & Cloud Settings.")
            return
        }
        runCatching { WorkspaceWebsiteGeneration.finishPreviousForNewRequest(files, projects, id) }
            .onFailure { error("Previous edit needs attention: ${it.message}. Use Undo in Chat."); return }
        runCatching {
            val previous = tasks.get(id)
            val criteria = previous?.acceptanceCriteria?.takeIf { it.isNotBlank() }
                ?: "The three saved website files are inspectable in Work Preview; preserve existing work."
            val saved = tasks.create(id, instruction, replaceExisting = previous != null,
                rawAcceptanceCriteria = criteria)
            tasks.setSpecificationApproved(id, saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        }.onFailure { error("Website task could not be saved: ${it.message}"); return }
        val snapshot = runCatching { WorkspaceWebsiteGeneration.prepare(files, tasks, projects, id) }
            .getOrElse { error("Website source review blocked: ${it.message}"); return }
        val outgoing = runCatching { WorkspaceWebsiteGeneration.request(key, snapshot) }
            .getOrElse { error("Free website request refused: ${it.message}"); return }
        val serial = ++generation
        val call = WorkspaceWebsiteGeneration.client.newCall(outgoing)
        request = call
        report("Building index.html, style.css and script.js in this project · Stop ■ to cancel.")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val message = if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                    "Website provider timed out (up to 80 seconds). No incomplete code was saved."
                else "Website provider connection failed; no result received. No paid fallback."
                completeWebsite(call, serial, id, snapshot,
                    Result.failure(IllegalStateException(message)))
            }
            override fun onResponse(call: Call, response: Response) = completeWebsite(
                call, serial, id, snapshot, runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
        })
    }

    private fun completeWebsite(call: Call, serial: Long, id: String,
                                snapshot: WorkspaceWebsiteGeneration.Snapshot,
                                result: Result<Map<String, String>>) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== call || !current(id)) return@runOnUiThread
            request = null
            result.onSuccess { generated ->
                runCatching {
                    WorkspaceWebsiteGeneration.apply(files, tasks, projects, snapshot, generated)
                }.onSuccess {
                    report("Saved index.html, style.css and script.js. Preview shows the saved project; " +
                        "Undo is available in Chat. Visual correctness still needs your check.")
                    activity.startActivity(WorkspacePreviewActivity.intent(activity, id))
                }.onFailure {
                    error("Website files were not fully saved: ${it.message}. " +
                        "If a backup is pending, use Review website · Undo in Chat.")
                }
            }.onFailure { error(it.message ?: "Free website provider failed; project files unchanged.") }
        }
    }

    private fun prepareSource(id: String, instruction: String, key: String) {
''')

change(flow, '''    fun reviewPending(id: String) {
        if (!current(id)) return
        val backup = runCatching { WorkspaceScopedEdit.pending(projects, id) }
''', '''    fun reviewPending(id: String) {
        if (!current(id)) return
        if (projects.getProject(id)?.type == WorkspaceProjectType.WEBSITE) {
            val website = runCatching { WorkspaceWebsiteGeneration.pending(projects, id) }
                .getOrElse { error("Website backup unavailable: ${it.message}"); return }
            if (website != null) {
                AlertDialog.Builder(activity).setTitle("Review website build")
                    .setMessage("Files: index.html, style.css, script.js\\n" +
                        "Your website request already authorized these writes. Keep is optional. " +
                        "Undo restores the previous files without overwriting newer manual edits. " +
                        "Check Work Preview; build success does not prove visual correctness.")
                    .setNegativeButton("Undo") { _, _ ->
                        runCatching { WorkspaceWebsiteGeneration.undo(files, projects, id) }
                            .onSuccess { report("Previous website files restored and locally verified.") }
                            .onFailure { error("Website Undo refused: ${it.message}") }
                    }.setNeutralButton("Later", null)
                    .setPositiveButton("Keep") { _, _ ->
                        runCatching { WorkspaceWebsiteGeneration.keep(files, projects, id) }
                            .onSuccess { report("Website build kept. Check Work Preview.") }
                            .onFailure { error("Website Keep refused: ${it.message}") }
                    }.show()
                return
            }
        }
        val backup = runCatching { WorkspaceScopedEdit.pending(projects, id) }
''')

activity = ROOT / 'WorkspaceActivity.kt'
change(activity, '''            val pending = runCatching { WorkspaceScopedEdit.pending(projects, id) }.getOrNull()
            if (pending != null) addControl("Review edit · Undo / Keep") { coding.reviewPending(id) }
            else {
''', '''            val websitePending = if (current.type == WorkspaceProjectType.WEBSITE)
                runCatching { WorkspaceWebsiteGeneration.pending(projects, id) }.getOrNull() else null
            val pending = runCatching { WorkspaceScopedEdit.pending(projects, id) }.getOrNull()
            if (websitePending != null) addControl("Review website · Undo / Keep") {
                coding.reviewPending(id)
            }
            else if (pending != null) addControl("Review edit · Undo / Keep") { coding.reviewPending(id) }
            else {
''')

website = ROOT / 'WorkspaceWebsiteGeneration.kt'
change(website, '''    private const val MAX_EXISTING_CHARS = 4_000
''', '''    private const val MAX_EXISTING_CHARS = 8_000
''')
change(website, '''                Regex("[0-9a-f]{64}").matches(afterHash) && (!existed).not().let { it || before.isEmpty() }) {
''', '''                Regex("[0-9a-f]{64}").matches(afterHash) && (existed || before.isEmpty())) {
''')

print('Applied exact-anchor website build integration, review controls and size policy.')
