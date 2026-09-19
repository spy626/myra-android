"""One-time, exact-anchor Workspace UI edit; refuse unexpected sources."""
from pathlib import Path

root = Path('app/src/main/java/com/myra/assistant/ui/workspace')
activity = root / 'WorkspaceActivity.kt'
intent = root / 'WorkspaceChatIntent.kt'
tests = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceChatIntentTest.kt')

def patch(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f'{path}: expected 1 matching anchor, got {text.count(old)}: {old[:80]!r}')
    path.write_text(text.replace(old, new, 1))

patch(activity, '    private var statusMessage = ""\n', '''    private var statusMessage = ""
    // Latest saved user turn only; Retry never appends a duplicate message.
    private var codingRetryTarget: Pair<String, String>? = null
''')
patch(activity, '''            activeProject = { selectedId }, report = { message ->
                statusMessage = message
                if (::root.isInitialized) render()
            })
''', '''            activeProject = { selectedId }, report = { message ->
                statusMessage = message
                if (::root.isInitialized) {
                    render()
                    if (message.startsWith("Free AI reached its output-token limit") ||
                        message.startsWith("OpenRouter returned HTTP ") ||
                        message.startsWith("Phone/network ") ||
                        message.startsWith("Free AI provider returned an error") ||
                        message.startsWith("Free AI stopped")) presentCodingFailure(message)
                }
            })
''')
patch(activity, '    private lateinit var composerArea: LinearLayout\n', '''    private lateinit var composerArea: LinearLayout
    private lateinit var statusBanner: TextView
''')
patch(activity, '''        attachmentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        composerArea.addView(attachmentList)
''', '''        // Kept outside the transcript scroll, directly above the composer and keyboard.
        statusBanner = label("", 12f).apply {
            visibility = View.GONE
            background = rounded(Color.rgb(20, 37, 28), 12)
            setTextColor(Color.rgb(222, 241, 224))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener {
                AlertDialog.Builder(this@WorkspaceActivity).setTitle("LYRA status")
                    .setMessage(statusMessage).setPositiveButton("Close", null).show()
            }
        }
        composerArea.addView(statusBanner, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(5)
        })
        attachmentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        composerArea.addView(attachmentList)
''')
patch(activity, '''        coding.cancel()
        statusMessage = "Stopped. No partial reply is available from this non-streaming provider."
''', '''        coding.cancel()
        codingRetryTarget = null
        statusMessage = "Stopped. No partial reply is available from this non-streaming provider."
''')
patch(activity, '''        content.removeAllViews()
        if (!workTab && statusMessage.isNotBlank()) {
            content.addView(label(statusMessage, 12f).apply {
                setTextColor(Color.rgb(148, 171, 153))
                setPadding(dp(8), dp(4), dp(8), dp(12))
            })
        }
        if (workTab) renderWork(current) else renderChat(current)
''', '''        statusBanner.text = statusMessage
        statusBanner.visibility = if (!workTab && statusMessage.isNotBlank()) View.VISIBLE else View.GONE
        content.removeAllViews()
        if (workTab) renderWork(current) else renderChat(current)
''')
patch(activity, '    private fun retryAssistant(id: String, assistantId: String) {\n', '''    private fun presentCodingFailure(reason: String) {
        val target = codingRetryTarget ?: return
        val id = target.first
        if (selectedId != id || workTab || isBusy()) return
        val latest = runCatching { conversations.read(id).lastOrNull() }.getOrNull() ?: return
        if (latest.role != "user" || latest.id != target.second) return
        // Never retry into a pending edit; canonical source freshness checks still apply.
        if (runCatching { WorkspaceScopedEdit.pending(projects, id) }.getOrNull() != null) return
        val note = if (reason.contains("output-token limit"))
            "The free model ran out of reply tokens. The same retry may fail again; " +
                "for larger edits, ask for one smaller change at a time."
        else "The original task and source will be checked again. No paid fallback."
        AlertDialog.Builder(this).setTitle("LYRA couldn't finish the request")
            .setMessage("$reason\\n\\n$note")
            .setNegativeButton("Later", null)
            .setPositiveButton("Retry once") { _, _ ->
                if (selectedId == id && !workTab && !isBusy() &&
                    runCatching { conversations.read(id).lastOrNull()?.id == latest.id }.getOrDefault(false))
                    coding.continueRequest(id, latest.text)
            }.show()
    }

    private fun showChatFailure(id: String, messageId: String,
                                replacingAssistantId: String?, provider: WorkspaceChatGateway.Provider,
                                picked: List<Attachment>, reason: String) {
        if (selectedId != id || workTab || isBusy()) return
        val history = runCatching { conversations.read(id) }.getOrNull() ?: return
        val eligible = if (replacingAssistantId == null)
            history.lastOrNull()?.let { it.role == "user" && it.id == messageId } == true
        else history.size >= 2 && history.last().role == "assistant" &&
            history.last().id == replacingAssistantId &&
            history[history.lastIndex - 1].id == messageId
        if (!eligible) return
        AlertDialog.Builder(this).setTitle("LYRA couldn't reply")
            .setMessage("$reason\\n\\nRetry the same complete message? " +
                "The same selected attachments will be read again, if any. No paid fallback.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Retry once") { _, _ ->
                if (selectedId == id && !workTab && !isBusy())
                    requestReply(id, messageId, provider, picked, replacingAssistantId)
            }.show()
    }

    private fun retryAssistant(id: String, assistantId: String) {
''')
patch(activity, '''        coding.cancel()
        selectedId = null
''', '''        coding.cancel()
        codingRetryTarget = null
        selectedId = null
''')
patch(activity, '''        coding.cancel()
        selectedId = id
''', '''        coding.cancel()
        codingRetryTarget = null
        selectedId = id
''')
patch(activity, '''        if (codingRequest) {
            if (picked.isNotEmpty()) {
''', '''        if (codingRequest) {
            codingRetryTarget = id to stored.id
            if (picked.isNotEmpty()) {
''')
patch(activity, '''            override fun onFailure(call: Call, error: IOException) = complete(call, serial, id,
                messageId, replacingAssistantId,
                Result.failure(IllegalStateException(WorkspaceFreeAiSuggestion.networkFailure(error))))
            override fun onResponse(call: Call, response: Response) =
                complete(call, serial, id, messageId, replacingAssistantId,
                    runCatching { WorkspaceChatGateway.read(provider, response) })
''', '''            override fun onFailure(call: Call, error: IOException) = complete(call, serial, id,
                messageId, replacingAssistantId, provider, picked,
                Result.failure(IllegalStateException(WorkspaceFreeAiSuggestion.networkFailure(error))))
            override fun onResponse(call: Call, response: Response) =
                complete(call, serial, id, messageId, replacingAssistantId, provider, picked,
                    runCatching { WorkspaceChatGateway.read(provider, response) })
''')
patch(activity, '''    private fun complete(call: Call, serial: Long, id: String, userMessageId: String,
                         replacingAssistantId: String?, result: Result<String>) {
''', '''    private fun complete(call: Call, serial: Long, id: String, userMessageId: String,
                         replacingAssistantId: String?, provider: WorkspaceChatGateway.Provider,
                         picked: List<Attachment>, result: Result<String>) {
''')
patch(activity, '''            activeRequest = null
            result.onSuccess { reply ->
''', '''            activeRequest = null
            val failure = result.exceptionOrNull()
            result.onSuccess { reply ->
''')
patch(activity, '''            }.onFailure { statusMessage = it.message ?: "Provider failed; original reply preserved." }
            render()
        }
    }
}
''', '''            }.onFailure { statusMessage = it.message ?: "Provider failed; original reply preserved." }
            render()
            if (failure != null) showChatFailure(id, userMessageId, replacingAssistantId,
                provider, picked, statusMessage)
        }
    }
}
''')
patch(intent, '''        return !question.containsMatchIn(text) && followUp.containsMatchIn(text)
''', '''        // Writing a development prompt is not authority to edit project files.
        return !promptRequest.containsMatchIn(text) &&
            !question.containsMatchIn(text) && followUp.containsMatchIn(text)
''')
patch(tests, 'import org.junit.Assert.assertEquals\n', 'import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\n')
patch(tests, '    @Test fun explicitBuildRequestsAreTyped() {\n', '''    @Test fun writingADevelopmentPromptDoesNotExecuteProjectEdits() {
        assertNull(WorkspaceChatIntent.requestedProjectType("Build Android app ka prompt do"))
        assertFalse(WorkspaceChatIntent.isCodingFollowUp("Add hands-free to my Android app prompt"))
        assertFalse(WorkspaceChatIntent.isCodingFollowUp("Fix my AI companion development prompt"))
    }

    @Test fun explicitBuildRequestsAreTyped() {
''')
print('Workspace-only failure banner, guarded retry and prompt-routing patches applied')
