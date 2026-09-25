package com.myra.assistant.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ui.settings.ApiCloudSettingsActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Private general Chat; typed coding project only after an explicit request. Voice is untouched. */
class WorkspaceActivity : AppCompatActivity() {
    private data class Attachment(val uri: Uri, val name: String, val mime: String, val size: Long)
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private val conversations by lazy {
        WorkspaceConversationStore(projects, File(noBackupFilesDir, "workspace-conversations"))
    }
    private val tasks by lazy { WorkspaceTaskStore(projects) }
    private val suggestions by lazy {
        WorkspaceAiSuggestionDraftStore(File(noBackupFilesDir, "workspace-ai-drafts"))
    }
    private val keys by lazy { ApiKeyStore(this) }
    private val preferences by lazy { getSharedPreferences("workspace_ui", Context.MODE_PRIVATE) }
    private val localDrafts = mutableMapOf<String, String>()
    // Display-only state: never alters persisted messages, Copy or provider requests.
    private val expandedMessageIds = mutableSetOf<String>()
    private val attachments = mutableListOf<Attachment>()
    private var selectedId: String? = null
    private var workTab = false
    private var requestGeneration = 0L
    private var activeRequest: Call? = null
    private var agentReachActive = false
    private var agentReachTarget: WorkspaceAgentReachPolicy.Target? = null
    private var agentReachProjectId: String? = null
    private var agentReachMessageId: String? = null
    private var agentReachRunner: WorkspaceAgentReachGitHubRunner? = null
    private var statusMessage = ""
    private val workTrace = WorkspaceWorkTrace()
    private var workTraceExpanded = true
    // The live receipt belongs inside the exact user turn that started the work.
    private var workTraceMessageId: String? = null
    private data class LiveWorkRow(
        val event: WorkspaceWorkEvent,
        val icon: WorkspaceMiniLyraView,
        val title: TextView,
    )
    // Display-only references. Keeping the live rows mounted lets entrance animations finish
    // while the next real stage continues; no work is delayed for animation.
    private var liveWorkTranscript: LinearLayout? = null
    private var liveWorkTranscriptMessageId: String? = null
    private val liveWorkRows = mutableListOf<LiveWorkRow>()
    private var liveWorkDurationView: TextView? = null
    // Latest saved user turn only; Retry never appends a duplicate message.
    private var codingRetryTarget: Pair<String, String>? = null
    private val coding by lazy {
        WorkspaceChatCodingFlow(this, projects, files, tasks, suggestions, keys,
            activeProject = { selectedId },
            onCompleted = { id, userId, summary ->
                conversations.completeCodingTurn(id, userId, summary)
            }, report = { message ->
                statusMessage = message
                if (::root.isInitialized) {
                    // Active work is already updating the inline transcript directly. Rebuilding
                    // the whole chat here would cancel its entrance animation.
                    if (workTrace.snapshot().active) updateSendButton() else render()
                }
            }, workEvent = { phase, label, detail ->
                recordWorkEvent(phase, label, detail)
            })
    }
    private lateinit var root: LinearLayout
    private lateinit var chatTab: TextView
    private lateinit var workTabButton: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var composerArea: LinearLayout
    private lateinit var statusBanner: TextView
    private lateinit var composer: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachmentList: LinearLayout

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addAttachment(it, true) }
    }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addAttachment(it, false) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(223, 245, 227))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun control(value: String, action: () -> Unit) = label(value).apply {
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_workspace_dialog_input)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }
    private fun addControl(value: String, action: () -> Unit) {
        content.addView(control(value, action), LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(10)
        })
    }

    /** Compact transcript action for pending review. Unlike Work-tab controls, this should read
     * like part of the conversation instead of a full-width dashboard button. */
    private fun addChatAction(value: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(dp(10), 0, 0, 0)
        }
        val chip = label(value, 12.5f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(205, 225, 211))
            setPadding(dp(13), 0, dp(13), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 25, 21))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(55, 78, 63))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(chip, LinearLayout.LayoutParams(-2, dp(36)))
        content.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(2)
            bottomMargin = dp(4)
        })
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString("workspace_selected_id")
            ?.takeIf { projects.getProject(it) != null }
        workTab = savedInstanceState?.getBoolean("workspace_work_tab") ?: false
        buildUi()
        render()
    }

    private fun githubReachRunner(): WorkspaceAgentReachGitHubRunner {
        agentReachRunner?.let { return it }
        return WorkspaceAgentReachGitHubRunner(
            currentTarget = { if (agentReachActive) agentReachTarget else null },
            listener = object : WorkspaceAgentReachGitHubRunner.Listener {
                override fun onEvent(
                    phase: WorkspaceWorkPhase,
                    label: String,
                    detail: String?,
                ) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !agentReachActive) return@runOnUiThread
                        recordWorkEvent(phase, label, detail)
                    }
                }

                override fun onComplete(evidence: WorkspaceAgentReachEvidence.Evidence) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !agentReachActive) return@runOnUiThread
                        val id = agentReachProjectId
                        val messageId = agentReachMessageId
                        clearAgentReachState(cancel = false)
                        if (id == null || messageId == null || selectedId != id) return@runOnUiThread
                        runCatching {
                            require(conversations.read(id).lastOrNull()?.id == messageId) {
                                "Conversation changed; GitHub read receipt was not saved"
                            }
                            conversations.append(
                                id, "assistant", WorkspaceAgentReachReceipt.github(evidence))
                        }.onSuccess {
                            statusMessage = ""
                        }.onFailure {
                            statusMessage = it.message ?: "GitHub read receipt could not be saved."
                            recordWorkEvent(
                                WorkspaceWorkPhase.ERROR,
                                "GitHub receipt not saved",
                                statusMessage,
                            )
                        }
                        render()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !agentReachActive) return@runOnUiThread
                        clearAgentReachState(cancel = false)
                        statusMessage = message
                        render()
                    }
                }
            },
        ).also { agentReachRunner = it }
    }

    private fun clearAgentReachState(cancel: Boolean = true) {
        if (cancel) agentReachRunner?.cancel()
        agentReachActive = false
        agentReachTarget = null
        agentReachProjectId = null
        agentReachMessageId = null
    }

    private fun startGitHubReach(
        id: String,
        messageId: String,
        target: WorkspaceAgentReachPolicy.Target,
    ) {
        clearAgentReachState()
        agentReachActive = true
        agentReachTarget = target
        agentReachProjectId = id
        agentReachMessageId = messageId
        statusMessage = ""
        render()
        githubReachRunner().start(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("workspace_work_tab", workTab)
        outState.putString("workspace_selected_id", selectedId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) {
            if (selectedId != null && projects.getProject(selectedId!!) == null) {
                selectedId = null
                attachments.clear()
                statusMessage = "Selected conversation is unavailable. Choose another chat."
            }
            render()
        }
    }

    override fun onStop() {
        requestGeneration++
        activeRequest?.cancel()
        activeRequest = null
        clearAgentReachState()
        coding.cancel()
        super.onStop()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(2, 6, 9))
        }
        val heading = FrameLayout(this).apply {
            minimumHeight = dp(52)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        heading.addView(label("⋮", 23f).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            contentDescription = "Open Workspace navigation"
            isClickable = true
            isFocusable = true
            setOnClickListener { showMenu() }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL))
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(18, 28, 24), 24)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        chatTab = label("Chat", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { workTab = false; render() }
        }
        workTabButton = label("Work", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { workTab = true; render() }
        }
        tabs.addView(chatTab, LinearLayout.LayoutParams(0, dp(34), 1f))
        tabs.addView(workTabButton, LinearLayout.LayoutParams(0, dp(34), 1f))
        heading.addView(tabs, FrameLayout.LayoutParams(dp(154), dp(40), Gravity.CENTER))
        heading.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            imageTintList = ColorStateList.valueOf(Color.rgb(223, 245, 227))
            background = rounded(Color.TRANSPARENT, 20)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "New Chat"
            setOnClickListener { newChat() }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(52)))

        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        composerArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        // Status banner is reserved for validation/setup errors that are not part of live work.
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
        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 28, 24))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(72, 101, 79))
            }
        }
        val plusButton = label("+", 27f).apply {
            gravity = Gravity.CENTER
            contentDescription = "Add photo or file"
            isClickable = true
            isFocusable = true
            setOnClickListener { showAttachmentMenu(this) }
        }
        entry.addView(plusButton, LinearLayout.LayoutParams(dp(43), dp(50)))
        composer = EditText(this).apply {
            hint = "Ask LYRA"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 171, 153))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 15f
            minLines = 1
            maxLines = 8
            isVerticalScrollBarEnabled = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // Never silently truncate a pasted prompt. Check the full text on Send.
            filters = emptyArray<InputFilter>()
            setPadding(dp(2), dp(10), dp(6), dp(10))
        }
        entry.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = rounded(Color.rgb(41, 65, 48), 22)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "Send message"
            setOnClickListener { if (isBusy()) stopReply() else sendMessage() }
        }
        entry.addView(sendButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            rightMargin = dp(5)
        })
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSendButton()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        composerArea.addView(entry, LinearLayout.LayoutParams(-1, -2))
        root.addView(composerArea)
        setContentView(root)
        updateSendButton()
    }

    private fun isBusy(): Boolean =
        activeRequest != null || coding.isRunning || agentReachActive

    private fun stopReply() {
        if (!isBusy()) return
        val normalChatWasRunning = activeRequest != null
        val githubReadWasRunning = agentReachActive
        requestGeneration++
        activeRequest?.cancel()
        activeRequest = null
        if (normalChatWasRunning) {
            workTrace.finishError("Stopped", "Request cancelled; no partial reply was saved.")
        }
        if (githubReadWasRunning) {
            clearAgentReachState()
            workTrace.finishError(
                "Stopped",
                "GitHub read cancelled; no content was installed, executed, or sent to a provider.")
        }
        coding.cancel()
        codingRetryTarget = null
        statusMessage = "Stopped. No partial reply was saved."
        render()
        if (!workTab) composer.requestFocus()
    }

    private fun recordWorkEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null) {
        val before = workTrace.snapshot()
        when (phase) {
            WorkspaceWorkPhase.DONE -> {
                if (before.events.isEmpty()) workTrace.begin(WorkspaceWorkPhase.DONE, label, detail)
                else workTrace.finishSuccess(label, detail)
            }
            WorkspaceWorkPhase.ERROR -> {
                if (before.events.isEmpty()) workTrace.begin(WorkspaceWorkPhase.ERROR, label, detail)
                else workTrace.finishError(label, detail)
            }
            else -> {
                if (before.events.isEmpty()) workTrace.begin(phase, label, detail)
                else workTrace.add(phase, label, detail)
            }
        }
        if (!::root.isInitialized) return
        val host = liveWorkTranscript
        if (!workTab && host != null && liveWorkTranscriptMessageId == workTraceMessageId) {
            syncLiveWorkTranscript(animateNew = true)
            updateSendButton()
        } else {
            render()
        }
    }

    private fun providerLabel(provider: WorkspaceChatGateway.Provider): String = when (provider) {
        WorkspaceChatGateway.Provider.OPENROUTER_FREE -> "OpenRouter Free"
        WorkspaceChatGateway.Provider.GROQ_FREE -> "Groq Free"
        WorkspaceChatGateway.Provider.LLM7_FREE -> "LLM7 Free"
    }

    private fun completedWorkColor(phase: WorkspaceWorkPhase): Int = when (phase) {
        WorkspaceWorkPhase.DONE -> Color.rgb(137, 220, 166)
        WorkspaceWorkPhase.ERROR -> Color.rgb(245, 150, 150)
        else -> Color.rgb(168, 178, 191)
    }

    private fun settlePreviousLiveWorkRow() {
        val previous = liveWorkRows.lastOrNull() ?: return
        previous.icon.setPhase(previous.event.phase, animate = false)
        previous.icon.layoutParams = (previous.icon.layoutParams as LinearLayout.LayoutParams).apply {
            width = dp(20)
            height = dp(20)
            topMargin = dp(2)
            rightMargin = dp(12)
        }
        previous.title.setTextColor(completedWorkColor(previous.event.phase))
    }

    private fun createWorkEventRow(
        event: WorkspaceWorkEvent,
        isCurrent: Boolean,
        active: Boolean,
        animateEntry: Boolean,
    ): LiveWorkRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(2), 0, dp(3))
        }
        val iconSize = if (isCurrent) 24 else 20
        val icon = WorkspaceMiniLyraView(this).apply {
            setPhase(event.phase, animate = isCurrent && active)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply {
            topMargin = if (iconSize < 24) dp(2) else 0
            rightMargin = if (iconSize < 24) dp(12) else dp(8)
        })

        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = label(event.label, 13.5f).apply {
            setTextColor(if (isCurrent && active) Color.rgb(226, 233, 242)
                else completedWorkColor(event.phase))
            setPadding(0, 0, dp(4), 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textColumn.addView(title, LinearLayout.LayoutParams(-1, -2))
        event.detail?.let { detail ->
            textColumn.addView(label(detail, 11.25f).apply {
                setTextColor(Color.rgb(125, 138, 154))
                setPadding(0, dp(1), dp(4), 0)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))

        if (animateEntry) {
            // Visual-only animation. It never gates, schedules or slows the real work stage.
            row.alpha = 0f
            row.translationY = dp(5).toFloat()
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        return LiveWorkRow(event, icon, title).also {
            row.tag = it
        }
    }

    private fun appendWorkEventRow(
        host: LinearLayout,
        event: WorkspaceWorkEvent,
        snapshot: WorkspaceWorkSnapshot,
        animateEntry: Boolean,
    ) {
        settlePreviousLiveWorkRow()
        val liveRow = createWorkEventRow(
            event = event,
            isCurrent = true,
            active = snapshot.active,
            animateEntry = animateEntry,
        )
        val rowView = liveRow.title.parent?.parent as? View
            ?: error("LYRA work row could not be created")
        host.addView(rowView, LinearLayout.LayoutParams(-1, -2))
        liveWorkRows.add(liveRow)
        while (liveWorkRows.size > 14) {
            liveWorkRows.removeAt(0)
            host.removeViewAt(0)
        }
    }

    private fun addWorkDuration(host: LinearLayout, snapshot: WorkspaceWorkSnapshot, animateEntry: Boolean) {
        if (snapshot.active || snapshot.startedAtMs == null || liveWorkDurationView != null) return
        val end = snapshot.endedAtMs ?: System.currentTimeMillis()
        val seconds = ((end - snapshot.startedAtMs).coerceAtLeast(0L) / 1_000L).coerceAtLeast(1L)
        val duration = label("Worked for ${seconds}s", 11.25f).apply {
            setTextColor(Color.rgb(120, 133, 149))
            setPadding(dp(32), dp(3), dp(4), dp(2))
            isClickable = true
            isFocusable = true
            contentDescription = if (workTraceExpanded) "Hide work details" else "Show work details"
            setOnClickListener {
                workTraceExpanded = !workTraceExpanded
                render()
            }
            if (animateEntry) {
                alpha = 0f
                translationY = dp(3).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(140L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        liveWorkDurationView = duration
        host.addView(duration, LinearLayout.LayoutParams(-1, -2))
    }

    private fun syncLiveWorkTranscript(animateNew: Boolean) {
        val host = liveWorkTranscript ?: return
        val snapshot = workTrace.snapshot()
        val visibleEvents = if (!snapshot.active && !workTraceExpanded)
            snapshot.events.takeLast(1) else snapshot.events.takeLast(14)

        // Incremental updates are used only while expanded/live. Collapse uses a full render.
        if (visibleEvents.size < liveWorkRows.size ||
            liveWorkRows.indices.any { liveWorkRows[it].event != visibleEvents[it] }) {
            render()
            return
        }

        visibleEvents.drop(liveWorkRows.size).forEach { event ->
            appendWorkEventRow(host, event, snapshot, animateEntry = animateNew)
        }
        addWorkDuration(host, snapshot, animateEntry = animateNew)
        scroll.post { scroll.scrollTo(0, content.height) }
    }

    private fun createInlineWorkTranscript(): LinearLayout? {
        val snapshot = workTrace.snapshot()
        if (snapshot.current == null) return null
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Deliberately transparent: this is part of the chat transcript, not a status card.
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }
        liveWorkTranscript = host
        liveWorkTranscriptMessageId = workTraceMessageId
        liveWorkRows.clear()
        liveWorkDurationView = null

        val visibleEvents = if (!snapshot.active && !workTraceExpanded)
            snapshot.events.takeLast(1) else snapshot.events.takeLast(14)
        visibleEvents.forEachIndexed { index, event ->
            val isNewest = index == visibleEvents.lastIndex
            val liveRow = createWorkEventRow(
                event = event,
                isCurrent = isNewest,
                active = isNewest && snapshot.active,
                animateEntry = isNewest && snapshot.active,
            )
            val rowView = liveRow.title.parent?.parent as? View
                ?: error("LYRA work row could not be created")
            host.addView(rowView, LinearLayout.LayoutParams(-1, -2))
            liveWorkRows.add(liveRow)
        }
        addWorkDuration(host, snapshot, animateEntry = false)
        return host
    }

    private fun updateSendButton() {
        if (!::sendButton.isInitialized || !::composer.isInitialized) return
        val busy = isBusy()
        val ready = !workTab && (busy || composer.text.toString().isNotBlank())
        sendButton.isEnabled = ready
        sendButton.alpha = if (ready) 1f else .5f
        sendButton.setImageResource(if (busy) R.drawable.ic_workspace_stop else android.R.drawable.ic_menu_send)
        sendButton.contentDescription = if (busy) "Stop LYRA reply" else "Send message"
        sendButton.background = rounded(if (ready) Color.rgb(168, 255, 178) else Color.rgb(41, 65, 48), 22)
        sendButton.imageTintList = ColorStateList.valueOf(if (ready) Color.rgb(20, 30, 22) else Color.WHITE)
    }

    private fun showAttachmentMenu(anchor: View) {
        if (workTab || isBusy()) return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Photos")
            menu.add(0, 2, 1, "Files")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> photoPicker.launch(arrayOf("image/jpeg", "image/png"))
                    2 -> documentPicker.launch(arrayOf("text/plain", "text/html", "text/css",
                        "application/json", "application/javascript", "application/pdf"))
                }
                true
            }
            show()
        }
    }

    private fun project() = selectedId?.let(projects::getProject)
    private fun chatTitle(project: WorkspaceProject) =
        preferences.getString("chat_title_${project.projectId}", null)?.takeIf { it.isNotBlank() }
            ?: project.name

    private fun render() {
        if (!::root.isInitialized) return
        val current = project()
        chatTab.background = rounded(if (workTab) Color.TRANSPARENT else Color.rgb(41, 65, 48), 21)
        workTabButton.background = rounded(if (workTab) Color.rgb(41, 65, 48) else Color.TRANSPARENT, 21)
        chatTab.setTextColor(if (workTab) Color.rgb(148, 171, 153) else Color.rgb(223, 245, 227))
        workTabButton.setTextColor(if (workTab) Color.rgb(223, 245, 227) else Color.rgb(148, 171, 153))
        composerArea.visibility = if (workTab) View.GONE else View.VISIBLE
        updateSendButton()
        statusBanner.text = statusMessage
        statusBanner.visibility = if (!workTab && statusMessage.isNotBlank() &&
            workTrace.snapshot().current == null) View.VISIBLE else View.GONE
        liveWorkTranscript = null
        liveWorkTranscriptMessageId = null
        liveWorkRows.clear()
        liveWorkDurationView = null
        content.removeAllViews()
        if (workTab) renderWork(current) else renderChat(current)
        renderAttachments()
    }

    private fun copyMessage(text: String) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LYRA message", text))
        }.onSuccess { toast("Message copied") }
            .onFailure { toast("Could not copy message") }
    }

    private fun messageIcon(resource: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(resource)
            background = rounded(Color.TRANSPARENT, 18)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun showUserMessageMenu(anchor: View, id: String, message: WorkspaceConversationStore.Message) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Copy")
            menu.add(0, 2, 1, "Edit message")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> copyMessage(message.text)
                    2 -> editUserMessage(id, message)
                }
                true
            }
            show()
        }
    }

    private fun editUserMessage(id: String, message: WorkspaceConversationStore.Message) {
        if (selectedId != id || workTab || isBusy()) {
            toast("Wait for the current request before editing")
            return
        }
        if (projects.getProject(id)?.type != WorkspaceProjectType.CHAT) {
            toast("For coding projects, send a new instruction in Chat; existing edits stay intact")
            return
        }
        val history = runCatching { conversations.read(id) }
            .getOrElse { toast("Conversation unavailable"); return }
        val index = history.indexOfLast { it.role == "user" }
        if (index < 0 || history[index].id != message.id ||
            (index != history.lastIndex && (index != history.lastIndex - 1 || history.last().role != "assistant"))) {
            toast("Only the newest user message can be edited without changing later messages")
            return
        }
        val input = EditText(this).apply {
            setText(message.text)
            setSelection(text.length)
            minLines = 2
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // Never silently truncate a pasted prompt. Check the full text on Send.
            filters = emptyArray<InputFilter>()
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Edit message")
            .setMessage("Save and resend this latest message? Its previous reply is removed. No files are changed.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save edit") { _, _ ->
                val revisedText = input.text.toString()
                if (!WorkspaceLongInputPolicy.sendable(revisedText)) {
                    toast("Message must contain 1–${WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS} characters; edit remains open if too long")
                } else if (WorkspaceChatIntent.requestedProjectType(revisedText) != null) {
                    toast("Send a new coding request in Chat instead of editing a private message")
                } else if (selectedId != id || isBusy() ||
                    projects.getProject(id)?.type != WorkspaceProjectType.CHAT) {
                    toast("Conversation changed; edit cancelled")
                } else {
                    runCatching { conversations.reviseNewestUser(id, message.id, revisedText) }
                        .onSuccess { revised ->
                            statusMessage = ""
                            render()
                            sendEditedMessage(id, revised.id)
                        }.onFailure { toast(it.message ?: "Edit could not be saved") }
                }
            }.show()
    }

    private fun sendEditedMessage(id: String, messageId: String) {
        val provider = runCatching { selectedProvider() }
            .getOrElse { statusMessage = "Secure provider key storage unavailable. Edit saved locally."; render(); return }
        if (provider == null) {
            statusMessage = "Edit saved locally. Add a free OpenRouter key to request a reply."
            render()
            return
        }
        requestReply(id, messageId, provider, emptyList())
    }

    private fun presentCodingFailure(reason: String) {
        val target = codingRetryTarget ?: return
        val id = target.first
        if (selectedId != id || workTab || isBusy()) return
        val history = runCatching { conversations.read(id) }.getOrNull() ?: return
        val latest = history.lastOrNull() ?: return
        val original = if (latest.role == "user") latest else
            history.getOrNull(history.lastIndex - 1)?.takeIf { latest.role == "assistant" }
        if (original == null || original.role != "user" || original.id != target.second) return
        // Never retry into a pending edit; canonical source freshness checks still apply.
        if (runCatching { WorkspaceScopedEdit.pending(projects, id) }.getOrNull() != null ||
            runCatching { WorkspaceWebsiteGeneration.pending(projects, id) }.getOrNull() != null) return
        val note = if (reason.contains("output-token limit"))
            "The free model ran out of reply tokens. The same retry may fail again; " +
                "for larger edits, ask for one smaller change at a time."
        else "The original task and source will be checked again. No paid fallback."
        AlertDialog.Builder(this).setTitle("LYRA couldn't finish the request")
            .setMessage("$reason\n\n$note")
            .setNegativeButton("Later", null)
            .setPositiveButton("Retry once") { _, _ ->
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
        val retry = WorkspaceChatRetryPolicy.decision()
        val dialog = AlertDialog.Builder(this).setTitle("LYRA couldn't reply")
            .setMessage("$reason\n\n" + retry.note(picked.isNotEmpty()))
            .setNegativeButton("Later", null)
        if (retry.allowImmediateRetry) {
            dialog.setPositiveButton("Retry once") { _, _ ->
                if (selectedId == id && !workTab && !isBusy())
                    requestReply(id, messageId, provider, picked, replacingAssistantId)
            }
        } else dialog.setPositiveButton("Close", null)
        dialog.show()
    }

    private fun retryAssistant(id: String, assistantId: String) {
        if (selectedId != id || workTab || isBusy()) {
            toast("Wait for the current request before retrying")
            return
        }
        if (projects.getProject(id)?.type != WorkspaceProjectType.CHAT) {
            toast("Coding changes must use the existing Safe Edit flow in Chat")
            return
        }
        val history = runCatching { conversations.read(id) }
            .getOrElse { toast("Conversation unavailable"); return }
        val last = history.lastOrNull()
        val user = history.getOrNull(history.lastIndex - 1)
        if (last?.role != "assistant" || last.id != assistantId || user?.role != "user") {
            toast("Retry is available for the latest LYRA reply only")
            return
        }
        if (WorkspaceCustomProviderRoutePolicy.useManualTextChat(
                WorkspaceCustomProviderStore.chatEnabled(this),
                projects.getProject(id)?.type,
                hasAttachments = false)) {
            requestCustomReply(id, user.id, assistantId)
            return
        }
        val provider = runCatching { selectedProvider() }
            .getOrElse { toast("Secure provider key storage unavailable"); return }
        if (provider == null) {
            toast("Add a free OpenRouter key in API & Cloud Settings to retry")
            return
        }
        requestReply(id, user.id, provider, emptyList(), assistantId)
    }

    private fun renderChat(current: WorkspaceProject?) {
        if (current == null) return
        val messages = runCatching { conversations.read(current.projectId) }
            .getOrElse {
                content.addView(label("Conversation storage requires attention. No other chat's messages will be shown."))
                return
            }
        var latestUserPrompt = ""
        messages.forEach { message ->
            val mine = message.role == "user"
            if (mine) latestUserPrompt = message.text
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val story = if (!mine && current.type == WorkspaceProjectType.CHAT)
                WorkspaceStoryScript.card(latestUserPrompt, message.text) else null
            val codeParts = if (!mine && story == null) WorkspaceCodeBlocks.parse(message.text)
                else emptyList()
            if (story != null) {
                item.addView(WorkspaceStoryCardView.create(this, story) {
                    copyMessage(story.copyText)
                }, LinearLayout.LayoutParams(-1, -2))
            } else if (codeParts.any { it is WorkspaceCodeBlocks.Part.Code }) {
                codeParts.forEach { part ->
                    when (part) {
                        is WorkspaceCodeBlocks.Part.Prose -> item.addView(label("", 15f).apply {
                            text = WorkspaceMarkdownText.render(part.text)
                            setTextIsSelectable(true)
                            setPadding(dp(14), dp(9), dp(14), dp(9))
                        }, LinearLayout.LayoutParams(-1, -2))
                        is WorkspaceCodeBlocks.Part.Code -> item.addView(
                            WorkspaceCodeCardView.create(this, part) { copyMessage(part.source) },
                            LinearLayout.LayoutParams(-1, -2).apply {
                                topMargin = dp(7)
                                bottomMargin = dp(9)
                            })
                    }
                }
            } else {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (mine) Gravity.END else Gravity.START
                }
                val bubble = label(message.text, 15f).apply {
                    if (!mine) text = WorkspaceMarkdownText.render(message.text)
                    maxWidth = resources.displayMetrics.widthPixels - dp(72)
                    setTextIsSelectable(!mine)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    if (mine) {
                        background = rounded(Color.rgb(28, 46, 37), 18)
                        isLongClickable = true
                        setOnLongClickListener {
                            showUserMessageMenu(this, current.projectId, message)
                            true
                        }
                    }
                }
                line.addView(bubble, LinearLayout.LayoutParams(-2, -2))
                item.addView(line, LinearLayout.LayoutParams(-1, -2))
            if (mine && WorkspaceMessageDisplayPolicy.shouldCollapse(message.text)) {
                    val messageKey = "${current.projectId}:${message.id}"
                    val toggle = label("Show more", 12f).apply {
                        gravity = Gravity.END
                        setTextColor(Color.rgb(168, 255, 178))
                        setPadding(dp(8), dp(2), dp(12), dp(8))
                        isClickable = true
                        isFocusable = true
                    }
                    fun display(expanded: Boolean) {
                        bubble.maxLines = if (expanded) Int.MAX_VALUE else
                            WorkspaceMessageDisplayPolicy.COLLAPSED_LINES
                        bubble.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
                        toggle.text = if (expanded) "Show less" else "Show more"
                        toggle.contentDescription = if (expanded) "Show less of your message" else
                            "Show full message"
                    }
                    display(messageKey in expandedMessageIds)
                    toggle.setOnClickListener {
                        if (!expandedMessageIds.add(messageKey)) expandedMessageIds.remove(messageKey)
                        display(messageKey in expandedMessageIds)
                    }
                    item.addView(toggle, LinearLayout.LayoutParams(-1, -2))
                }
            }
            if (!mine) {
                val actionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                }
                actionRow.addView(messageIcon(R.drawable.ic_workspace_copy, "Copy LYRA reply") {
                    copyMessage(message.text)
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                actionRow.addView(messageIcon(R.drawable.ic_workspace_retry, "Retry LYRA reply") {
                    retryAssistant(current.projectId, message.id)
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                item.addView(actionRow, LinearLayout.LayoutParams(-1, dp(40)))
            }
            content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            if (mine && message.id == workTraceMessageId) {
                createInlineWorkTranscript()?.let { traceView ->
                    content.addView(traceView, LinearLayout.LayoutParams(-1, -2).apply {
                        leftMargin = dp(10)
                        rightMargin = dp(6)
                        bottomMargin = dp(8)
                    })
                }
            }
        }
        if (current.type != WorkspaceProjectType.CHAT) {
            val id = current.projectId
            val websitePending = if (current.type == WorkspaceProjectType.WEBSITE)
                runCatching { WorkspaceWebsiteGeneration.pending(projects, id) }.getOrNull() else null
            val pending = runCatching { WorkspaceScopedEdit.pending(projects, id) }.getOrNull()
            val savedProposal = if (websitePending == null && pending == null)
                runCatching { suggestions.recover(files, tasks, projects, id) }.getOrNull() is
                    WorkspaceAiSuggestionDraftStore.Recovery.Ready
            else false
            // Never treat a saved instruction as a completed or resumable file edit.
            // A new instruction is sent through the chat composer; review requires a real backup.
            when (WorkspaceCodingActionPolicy.next(websitePending != null, pending != null, savedProposal)) {
                WorkspaceCodingActionPolicy.Action.REVIEW_WEBSITE ->
                    addChatAction("Review website") { coding.reviewPending(id) }
                WorkspaceCodingActionPolicy.Action.REVIEW_EDIT ->
                    addChatAction("Review edit") { coding.reviewPending(id) }
                WorkspaceCodingActionPolicy.Action.REVIEW_SAVED_PROPOSAL ->
                    addChatAction("Review saved change") { coding.reviewSaved(id) }
                WorkspaceCodingActionPolicy.Action.NONE -> Unit
            }
        }
        // Scrolling by coordinates must not move input focus to the last selectable reply.
        scroll.post { scroll.scrollTo(0, content.height) }
    }

    private fun renderWork(current: WorkspaceProject?) {
        if (current == null || current.type == WorkspaceProjectType.CHAT) {
            content.addView(label("No coding project yet. Ask LYRA to build a website or app in Chat.", 16f))
            return
        }
        content.addView(label("${current.name} · ${current.type.displayName}", 16f))
        addControl("Project Files & Editor") {
            startActivity(WorkspaceEditorActivity.intent(this, current.projectId))
        }
        if (current.type == WorkspaceProjectType.WEBSITE) addControl("Preview") {
            startActivity(WorkspacePreviewActivity.intent(this, current.projectId))
        } else addControl("Preview · Not available for Android builds") {
            toast("Android build preview is not implemented; no successful build is claimed")
        }
    }

    private fun showMenu() {
        val all = projects.listProjects()
        val available = all.filter { project -> project.type != WorkspaceProjectType.CHAT ||
            runCatching { conversations.read(project.projectId).isNotEmpty() }.getOrDefault(false) }
        val chats = available.filter {
            runCatching { conversations.read(it.projectId).isNotEmpty() }.getOrDefault(false)
        }
        val ids = chats.map { it.projectId }.toSet()
        val pinned = chats.filter { preferences.getBoolean("chat_pinned_${it.projectId}", false) }
            .map { it.projectId }.toSet()
        WorkspaceNavigationDrawer.show(
            activity = this,
            projects = available,
            chatProjectIds = ids,
            selectedProjectId = selectedId,
            pinnedChatIds = pinned,
            titleFor = ::chatTitle,
            onNewChat = { newChat() },
            onPlugins = { showPlugins() },
            onApiSettings = { startActivity(Intent(this, ApiCloudSettingsActivity::class.java)) },
            onSelectProject = { selectProject(it) },
            onTogglePin = { id ->
                preferences.edit().putBoolean("chat_pinned_$id", id !in pinned).apply()
            },
            onRenameChat = { id, name ->
                val updated = name.trim().replace(Regex("\\s+"), " ")
                if (projects.getProject(id) == null || updated.isBlank() || updated.length > 80 ||
                    updated.any(Char::isISOControl)) {
                    toast("Chat name must contain 1–80 safe characters")
                } else {
                    preferences.edit().putString("chat_title_$id", updated).apply()
                    render()
                }
            },
            onDeleteChat = { id -> confirmDeleteChat(id) },
            onBeforeDeleteProject = { id ->
                if (selectedId == id) {
                    requestGeneration++
                    activeRequest?.cancel()
                    activeRequest = null
                    clearAgentReachState()
                    coding.cancel()
                    codingRetryTarget = null
                }
            }
        )
    }

    private fun confirmDeleteChat(id: String) {
        val target = projects.getProject(id) ?: return
        val chatOnly = target.type == WorkspaceProjectType.CHAT
        AlertDialog.Builder(this).setTitle("Delete Chat?")
            .setMessage(if (chatOnly) "Delete ${chatTitle(target)}? This private chat has no project source. This cannot be undone."
                else "Delete the conversation for ${chatTitle(target)}? Project source, task briefs, approvals and Undo/Keep remain intact. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (selectedId == id) coding.cancel()
                runCatching {
                    if (chatOnly) {
                        require(projects.getProject(id)?.type == WorkspaceProjectType.CHAT) {
                            "Chat became a coding project; deletion cancelled"
                        }
                        require(files.list(id).isEmpty() && tasks.get(id) == null &&
                            WorkspaceScopedEdit.pending(projects, id) == null) {
                            "This chat has project work. Delete Chat only; keep the project."
                        }
                    }
                    conversations.deleteChat(id)
                    if (chatOnly) check(projects.deleteProject(id)) { "Chat-only metadata could not be removed" }
                }.onSuccess {
                    preferences.edit().remove("chat_pinned_$id").remove("chat_title_$id").apply()
                    localDrafts.remove(id)
                    if (selectedId == id) newChat() else render()
                }.onFailure { toast(it.message ?: "Chat could not be deleted") }
            }.show()
    }

    private fun newChat() {
        selectedId?.let { localDrafts[it] = composer.text.toString() }
        requestGeneration++
        activeRequest?.cancel()
        activeRequest = null
        clearAgentReachState()
        coding.cancel()
        codingRetryTarget = null
        selectedId = null
        workTab = false
        attachments.clear()
        composer.text.clear()
        statusMessage = ""
        workTrace.clear()
        workTraceExpanded = true
        workTraceMessageId = null
        render()
    }

    private fun showPlugins() {
        AlertDialog.Builder(this).setTitle("Workspace Plugins")
            .setMessage("No validated Workspace plugin connection is registered for this project. " +
                "API provider keys do not grant plugin permissions. Nothing is shared through this menu. " +
                "Provider configuration is available in API & Cloud Settings.")
            .setNegativeButton("Close", null)
            .setPositiveButton("API settings") { _, _ ->
                startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
            }.show()
    }

    private fun selectProject(id: String) {
        if (projects.getProject(id) == null) { toast("Project unavailable"); return }
        selectedId?.let { localDrafts[it] = composer.text.toString() }
        requestGeneration++
        activeRequest?.cancel()
        activeRequest = null
        clearAgentReachState()
        coding.cancel()
        codingRetryTarget = null
        selectedId = id
        projects.markOpened(id)
        attachments.clear()
        composer.setText(localDrafts[id].orEmpty())
        statusMessage = ""
        workTrace.clear()
        workTraceExpanded = true
        workTraceMessageId = null
        render()
    }

    /** Single Workspace selection point; full same-chat request budget, never only latest text.
     * Attachments use the existing OpenRouter route or stay local; no auto retry or paid route.
     * Groq Free/ZDR opt-in does not certify an account that is later upgraded to paid.
     */
    private fun selectedProvider(hasAttachments: Boolean = false): WorkspaceChatGateway.Provider? {
        val openRouterAvailable = keys.get(ApiKeyStore.OPENROUTER).isNotBlank()
        val groqAvailable = keys.get(ApiKeyStore.GROQ).isNotBlank()
        val groqApproved = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        val llm7Key = keys.get(ApiKeyStore.LLM7)
        val llm7Available = WorkspaceLlm7Free.validKey(llm7Key)
        val llm7Approved = preferences.getBoolean(WorkspaceLlm7Free.PREFERENCE_KEY, false)
        val history = selectedId?.let { runCatching { conversations.read(it) }.getOrNull() }
        // Retry of an existing assistant reply uses its preceding user turn.
        val candidate = if (history?.lastOrNull()?.role == "assistant") history.dropLast(1) else history
        val groqFits = candidate?.takeIf { it.lastOrNull()?.role == "user" }
            ?.let { WorkspaceGroqFree.withinBudget(it) } ?: false
        val llm7Fits = candidate?.takeIf { it.lastOrNull()?.role == "user" }
            ?.let { WorkspaceLlm7Free.withinBudget(it) } ?: false
        return WorkspaceFreeProviderSelection.choose(
            openRouterAvailable, groqAvailable, groqApproved, groqFits, hasAttachments,
            llm7Available, llm7Approved, llm7Fits)
    }

    private fun keyFor(provider: WorkspaceChatGateway.Provider): String = when (provider) {
        WorkspaceChatGateway.Provider.OPENROUTER_FREE -> keys.get(ApiKeyStore.OPENROUTER)
        WorkspaceChatGateway.Provider.GROQ_FREE -> keys.get(ApiKeyStore.GROQ)
        WorkspaceChatGateway.Provider.LLM7_FREE -> keys.get(ApiKeyStore.LLM7)
    }

    private fun addAttachment(uri: Uri, photo: Boolean) {
        if (selectedId == null) { toast("Send a first message before attaching files"); return }
        if (workTab || isBusy()) { toast("Wait for the current reply before adding files"); return }
        if (attachments.size >= 3) { toast("Maximum three local attachments"); return }
        if (uri.scheme != "content") { toast("Only Android document-provider files are accepted"); return }
        val item = runCatching {
            val mime = contentResolver.getType(uri).orEmpty().lowercase()
            var name = "attachment"
            var size = -1L
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        name = cursor.getString(it) ?: name
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                        if (!cursor.isNull(it)) size = cursor.getLong(it)
                    }
                }
            }
            if (size < 0L) contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            require(name.length in 1..120 && name.none(Char::isISOControl)) { "Invalid attachment name" }
            require(size >= 0L) { "Cannot verify file size; choose a local file" }
            if (photo) {
                require(mime == "image/jpeg" || mime == "image/png") { "Only JPEG and PNG are supported" }
                require(size in 1..2_000_000) { "Photo must be 2 MB or smaller" }
                require(attachments.none { it.mime.startsWith("image/") }) { "Only one photo per request" }
            } else {
                require(mime in setOf("text/plain", "text/html", "text/css", "application/json",
                    "application/javascript")) {
                    "PDF or this document format is not supported yet. Nothing was uploaded."
                }
                require(size in 1..3_000) { "Text document must be 3 KB or smaller" }
            }
            Attachment(uri, name, mime, size)
        }.getOrElse { toast(it.message ?: "Attachment unavailable"); return }
        attachments.add(item)
        renderAttachments()
    }

    private fun renderAttachments() {
        if (!::attachmentList.isInitialized) return
        attachmentList.removeAllViews()
        attachments.toList().forEach { attachment ->
            attachmentList.addView(control("${attachment.name} · ${attachment.size} bytes  ✕") {
                attachments.remove(attachment)
                renderAttachments()
            }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(4) })
        }
    }

    private fun InputStream.readBounded(max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (output.size() <= max) {
            val count = read(buffer, 0, minOf(buffer.size, max + 1 - output.size()))
            if (count == -1) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        require(output.size() <= max) { "Attachment changed or exceeds size limit" }
        return output.toByteArray()
    }

    private fun readAttachmentText(item: Attachment): String {
        val bytes = contentResolver.openInputStream(item.uri)?.use { it.readBounded(3_000) }
            ?: throw IllegalArgumentException("Cannot read document")
        require(bytes.isNotEmpty()) { "Document is empty" }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().also { require(it.isNotBlank()) { "Document is empty" } }
    }

    private fun sendMessage() {
        if (workTab) return
        if (isBusy()) { stopReply(); return }
        val text = composer.text.toString()
        if (text.isBlank()) { toast("Write a message first"); return }
        if (!WorkspaceLongInputPolicy.sendable(text)) {
            statusMessage = "The complete pasted draft is still in the chat box. " +
                "This app supports up to ${WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS} characters per message; nothing was sent."
            render()
            return
        }
        val intent = WorkspaceChatIntent.requestedProjectType(text)
        if (selectedId == null) {
            val title = text.lineSequence().firstOrNull().orEmpty()
                .replace(Regex("\\s+"), " ").trim().take(72).trim().ifBlank { "New chat" }
            val created = runCatching { projects.createProject(title, intent ?: WorkspaceProjectType.CHAT) }
                .getOrElse { toast(it.message ?: "Cannot start chat"); return }
            selectedId = created.projectId
        }
        val id = selectedId ?: return
        val current = projects.getProject(id) ?: return
        if (intent != null && current.type != WorkspaceProjectType.CHAT && current.type != intent) {
            toast("This is a different project type. Start a New Chat for that request.")
            return
        }
        val picked = attachments.toList()
        // Text-only provider opt-ins never silently reroute attachments to another company.
        if (intent == null && current.type == WorkspaceProjectType.CHAT && picked.isNotEmpty()) {
            val llm7TextOnly = preferences.getBoolean(WorkspaceLlm7Free.PREFERENCE_KEY, false) &&
                WorkspaceLlm7Free.validKey(keys.get(ApiKeyStore.LLM7))
            val groqTextOnly = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false) &&
                keys.get(ApiKeyStore.GROQ).isNotBlank()
            val customTextOnly = WorkspaceCustomProviderStore.chatEnabled(this)
            if (llm7TextOnly || groqTextOnly || customTextOnly) {
                statusMessage = "The enabled text route does not accept attachments in LYRA. Remove the attachment or turn that text route OFF; nothing was sent."
                render()
                return
            }
        }
        val stored = runCatching { conversations.append(id, "user", text) }
            .getOrElse { toast(it.message ?: "Cannot save message"); return }
        workTrace.clear()
        workTraceExpanded = true
        workTraceMessageId = stored.id
        composer.text.clear()
        // Keep the keyboard's typing target after Send; opening the keyboard is still user-driven.
        composer.requestFocus()
        localDrafts.remove(id)
        attachments.clear()
        if (intent != null && current.type == WorkspaceProjectType.CHAT) {
            runCatching { projects.promoteChat(id, text.take(72), intent) }
                .onFailure { statusMessage = "Request saved, but project creation failed: ${it.message}"; render(); return }
        }
        val codingRequest = intent != null ||
            (projects.getProject(id)?.type != WorkspaceProjectType.CHAT &&
                WorkspaceChatIntent.isCodingFollowUp(text))
        if (codingRequest) {
            codingRetryTarget = id to stored.id
            if (picked.isNotEmpty()) {
                statusMessage = "Coding instruction saved. Attachments are not automatically included in project source."
            } else statusMessage = ""
            render()
            coding.continueRequest(id, text, stored.id)
            return
        }
        if (picked.isEmpty() && projects.getProject(id)?.type == WorkspaceProjectType.CHAT) {
            WorkspaceAgentReachChatIntent.decide(text)?.let { decision ->
                decision.localError?.let { reason ->
                    statusMessage = reason
                    recordWorkEvent(WorkspaceWorkPhase.ERROR, "GitHub read not started", reason)
                    render()
                    return
                }
                decision.target?.let { target ->
                    startGitHubReach(id, stored.id, target)
                    return
                }
            }
        }
        // User-authored recall is answered from the exact selected-chat transcript.
        // Never let a model's earlier guess become evidence; do not spend another Free call.
        if (picked.isEmpty() && projects.getProject(id)?.type == WorkspaceProjectType.CHAT) {
            val grounded = runCatching {
                val selectedChat = conversations.read(id)
                WorkspaceChatRecallGrounding.answer(selectedChat)
                    ?: WorkspaceChatPlanStatus.answer(selectedChat)
            }.getOrNull()
            if (grounded != null) {
                runCatching {
                    require(conversations.read(id).lastOrNull()?.id == stored.id) {
                        "Conversation changed; grounded answer not saved"
                    }
                    conversations.append(id, "assistant", grounded)
                }.onFailure { statusMessage = it.message ?: "Grounded answer could not be saved" }
                render()
                return
            }
        }
        if (WorkspaceCustomProviderRoutePolicy.useManualTextChat(
                WorkspaceCustomProviderStore.chatEnabled(this),
                projects.getProject(id)?.type,
                picked.isNotEmpty())) {
            statusMessage = ""
            render()
            requestCustomReply(id, stored.id)
            return
        }
        val provider = runCatching { selectedProvider(picked.isNotEmpty()) }
            .getOrElse { statusMessage = "Secure key storage unavailable. Message saved locally."; render(); return }
        if (provider == null) {
            statusMessage = "Message saved locally. Configure a free route in Settings; no request was sent."
            render()
            AlertDialog.Builder(this).setTitle("No eligible Workspace free route")
                .setMessage("Save a valid OpenRouter Free key, enable Groq Free/ZDR with a valid Groq key, or enable LLM7 Free with a valid free token. Z.ai is coding-only. No paid fallback.")
                .setNegativeButton("Close", null)
                .setPositiveButton("API settings") { _, _ ->
                    startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
                }.show()
            return
        }
        statusMessage = ""
        render()
        requestReply(id, stored.id, provider, picked)
    }

    private fun requestCustomReply(
        id: String,
        messageId: String,
        replacingAssistantId: String? = null,
    ) {
        if (selectedId != id || isBusy() || workTab) return
        if (!WorkspaceCustomProviderStore.chatEnabled(this)) {
            statusMessage = "Custom provider manual Chat route is OFF. Message remains local."
            render()
            return
        }
        val profile = WorkspaceCustomProviderStore.load(this)
        if (profile == null) {
            statusMessage = "Custom provider profile is missing or invalid. Message remains local; no fallback was sent."
            render()
            return
        }
        val history = runCatching { conversations.read(id) }
            .getOrElse {
                statusMessage = "Conversation unavailable; no Custom provider request was sent."
                render()
                return
            }
        val transcript = if (replacingAssistantId == null) history else {
            if (history.size < 2 || history.last().id != replacingAssistantId ||
                history.last().role != "assistant" ||
                history[history.lastIndex - 1].id != messageId ||
                history[history.lastIndex - 1].role != "user") {
                statusMessage = "Conversation changed; Custom provider retry cancelled."
                render()
                return
            }
            history.dropLast(1)
        }
        if (transcript.lastOrNull()?.id != messageId || transcript.lastOrNull()?.role != "user") {
            statusMessage = "Conversation changed; Custom provider request cancelled."
            render()
            return
        }

        val key = runCatching { keys.get(profile.encryptedKeySlot) }
            .getOrElse {
                statusMessage = "Custom provider key storage unavailable; no request was sent."
                render()
                return
            }
        val outgoing = runCatching {
            WorkspaceCustomProviderChat.request(profile, key, transcript)
        }.getOrElse {
            statusMessage = it.message ?: "Custom provider request is unavailable."
            render()
            return
        }
        val serial = ++requestGeneration
        val call = WorkspaceCustomProviderConnection.client(profile).newCall(outgoing)
        activeRequest = call
        workTrace.clear()
        workTraceExpanded = true
        workTraceMessageId = messageId
        workTrace.begin(
            WorkspaceWorkPhase.THINKING,
            "Thinking",
            "${profile.displayName} · Custom manual",
        )
        statusMessage = ""
        render()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                completeCustomReply(
                    call, serial, id, messageId, replacingAssistantId,
                    Result.failure(IllegalStateException(
                        WorkspaceCustomProviderChat.networkFailure(error))),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                completeCustomReply(
                    call, serial, id, messageId, replacingAssistantId,
                    runCatching { WorkspaceCustomProviderChat.read(response) },
                )
            }
        })
    }

    private fun completeCustomReply(
        call: Call,
        serial: Long,
        id: String,
        userMessageId: String,
        replacingAssistantId: String?,
        result: Result<String>,
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed || serial != requestGeneration ||
                activeRequest !== call || selectedId != id) return@runOnUiThread
            activeRequest = null

            val checked = result.mapCatching { reply ->
                if (projects.getProject(id)?.type == WorkspaceProjectType.CHAT) {
                    val saved = conversations.read(id)
                    val actual = if (replacingAssistantId != null &&
                        saved.lastOrNull()?.id == replacingAssistantId) saved.dropLast(1) else saved
                    WorkspaceChatTurnFrame.verify(actual, reply)
                } else reply
            }
            val failure = checked.exceptionOrNull()
            checked.onSuccess { reply ->
                runCatching {
                    if (replacingAssistantId == null) {
                        require(conversations.read(id).lastOrNull()?.id == userMessageId) {
                            "Conversation changed; response was not applied"
                        }
                        conversations.append(id, "assistant", reply)
                    } else {
                        conversations.replaceNewestAssistant(
                            id, replacingAssistantId, userMessageId, reply)
                    }
                }.onSuccess {
                    statusMessage = ""
                    workTrace.finishSuccess("Reply ready")
                }.onFailure {
                    statusMessage = it.message ?: "Custom provider reply could not be saved."
                    workTrace.finishError("Reply not saved", statusMessage)
                }
            }.onFailure {
                statusMessage = it.message ?: "Custom provider failed; no fallback was sent."
                workTrace.finishError("Reply failed", statusMessage)
            }
            render()
            if (failure != null) {
                showCustomChatFailure(
                    id, userMessageId, replacingAssistantId, statusMessage)
            }
        }
    }

    private fun showCustomChatFailure(
        id: String,
        messageId: String,
        replacingAssistantId: String?,
        reason: String,
    ) {
        if (selectedId != id || workTab || isBusy() ||
            !WorkspaceCustomProviderStore.chatEnabled(this)) return
        val history = runCatching { conversations.read(id) }.getOrNull() ?: return
        val eligible = if (replacingAssistantId == null) {
            history.lastOrNull()?.let { it.role == "user" && it.id == messageId } == true
        } else {
            history.size >= 2 && history.last().role == "assistant" &&
                history.last().id == replacingAssistantId &&
                history[history.lastIndex - 1].id == messageId
        }
        if (!eligible) return

        AlertDialog.Builder(this)
            .setTitle("Custom provider couldn't reply")
            .setMessage(
                "$reason\n\nNo retry, paid fallback, or other provider was sent this chat. " +
                    "Check Custom API settings or send a new message when ready.")
            .setNegativeButton("Close", null)
            .setPositiveButton("API settings") { _, _ ->
                startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
            }
            .show()
    }

    private fun requestReply(id: String, messageId: String, provider: WorkspaceChatGateway.Provider,
                             picked: List<Attachment>, replacingAssistantId: String? = null) {
        if (selectedId != id || isBusy() || workTab) return
        val cooldown = WorkspaceProviderSessionHealth.cooldownMessage(
            WorkspaceProviderRegistry.id(provider))
        if (cooldown.isNotBlank()) {
            statusMessage = cooldown
            render()
            return
        }
        workTrace.clear()
        workTraceExpanded = true
        workTraceMessageId = messageId
        val history = runCatching { conversations.read(id) }
            .getOrElse { toast("Conversation unavailable"); return }
        val transcript = if (replacingAssistantId == null) history else {
            if (history.size < 2 || history.last().id != replacingAssistantId ||
                history.last().role != "assistant" || history[history.lastIndex - 1].id != messageId ||
                history[history.lastIndex - 1].role != "user") {
                toast("Conversation changed; retry cancelled")
                return
            }
            history.dropLast(1)
        }
        if (transcript.lastOrNull()?.id != messageId || transcript.lastOrNull()?.role != "user") {
            toast("Conversation changed; request cancelled")
            return
        }
        if ((provider == WorkspaceChatGateway.Provider.GROQ_FREE ||
                provider == WorkspaceChatGateway.Provider.LLM7_FREE) && picked.isNotEmpty()) {
            statusMessage = "Selected free route is text-only; no selected photo/file was sent."
            render()
            return
        }
        if (picked.isNotEmpty()) {
            workTrace.begin(WorkspaceWorkPhase.READING, "Reading attachment",
                "${picked.size} selected item${if (picked.size == 1) "" else "s"}")
            if (::root.isInitialized) render()
        }
        val enriched = runCatching {
            val addition = picked.filterNot { it.mime.startsWith("image/") }
                .joinToString("\n\n") { "Document ${it.name}:\n${readAttachmentText(it)}" }
            val last = transcript.last()
            val expanded = last.text + if (addition.isBlank()) "" else "\n\n$addition"
            require(expanded.length <= WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS) {
                "Attachments exceed the private request limit"
            }
            transcript.dropLast(1) + last.copy(text = expanded)
        }.getOrElse { statusMessage = it.message ?: "Document unavailable"; render(); return }
        val image = picked.firstOrNull { it.mime.startsWith("image/") }?.let { attachment ->
            runCatching {
                val bytes = contentResolver.openInputStream(attachment.uri)?.use { it.readBounded(2_000_000) }
                    ?: throw IllegalArgumentException("Cannot read photo")
                require(bytes.isNotEmpty()) { "Photo is empty" }
                WorkspaceChatGateway.Image(attachment.mime,
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }.getOrElse { statusMessage = it.message ?: "Photo unavailable"; render(); return }
        }
        val outgoing = runCatching {
            WorkspaceChatGateway.request(provider, keyFor(provider), enriched, image)
        }.getOrElse { statusMessage = it.message ?: "Provider unavailable"; render(); return }
        val serial = ++requestGeneration
        val call = WorkspaceChatGateway.client(provider).newCall(outgoing)
        activeRequest = call
        if (workTrace.snapshot().events.isEmpty()) {
            workTrace.begin(WorkspaceWorkPhase.THINKING, "Thinking", providerLabel(provider))
        } else {
            workTrace.add(WorkspaceWorkPhase.THINKING, "Thinking", providerLabel(provider))
        }
        statusMessage = ""
        render()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                WorkspaceProviderSessionHealth.recordUncertainNetworkFailure(
                    WorkspaceProviderRegistry.id(provider))
                complete(call, serial, id, messageId, replacingAssistantId, provider, picked,
                    Result.failure(IllegalStateException(WorkspaceChatGateway.networkFailure(provider, error))))
            }
            override fun onResponse(call: Call, response: Response) {
                WorkspaceProviderSessionHealth.recordResponse(response)
                complete(call, serial, id, messageId, replacingAssistantId, provider, picked,
                    runCatching { WorkspaceChatGateway.read(provider, response) })
            }
        })
    }

    private fun complete(call: Call, serial: Long, id: String, userMessageId: String,
                         replacingAssistantId: String?, provider: WorkspaceChatGateway.Provider,
                         picked: List<Attachment>, result: Result<String>) {
        runOnUiThread {
            if (isFinishing || isDestroyed || serial != requestGeneration ||
                activeRequest !== call || selectedId != id) return@runOnUiThread
            activeRequest = null
            // Check the completed visible draft against the actual USER topic before
            // saving it. This is deliberately conservative and makes NO new AI call.
            val checked = result.mapCatching { reply ->
                if (projects.getProject(id)?.type == WorkspaceProjectType.CHAT && picked.isEmpty()) {
                    val saved = conversations.read(id)
                    val actual = if (replacingAssistantId != null &&
                        saved.lastOrNull()?.id == replacingAssistantId) saved.dropLast(1) else saved
                    WorkspaceChatTurnFrame.verify(actual, reply)
                } else reply
            }
            val failure = checked.exceptionOrNull()
            checked.onSuccess { reply ->
                runCatching {
                    if (replacingAssistantId == null) {
                        require(conversations.read(id).lastOrNull()?.id == userMessageId) {
                            "Conversation changed; response was not applied"
                        }
                        conversations.append(id, "assistant", reply)
                    } else conversations.replaceNewestAssistant(id, replacingAssistantId, userMessageId, reply)
                }.onSuccess {
                    statusMessage = ""
                    workTrace.finishSuccess("Reply ready")
                }.onFailure {
                    statusMessage = it.message ?: "Response could not be saved; no source changed."
                    workTrace.finishError("Reply not saved", statusMessage)
                }
            }.onFailure {
                statusMessage = it.message ?: "Provider failed; original reply preserved."
                workTrace.finishError("Reply failed", statusMessage)
            }
            render()
            if (failure != null) showChatFailure(id, userMessageId, replacingAssistantId,
                provider, picked, statusMessage)
        }
    }
}
