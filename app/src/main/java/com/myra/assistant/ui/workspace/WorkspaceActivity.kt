package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioGroup
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
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Project-scoped chat and existing Work tools; never touches LYRA's personal memory. */
class WorkspaceActivity : AppCompatActivity() {
    private data class Attachment(val uri: Uri, val name: String, val mime: String, val size: Long)
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private val conversations by lazy {
        WorkspaceConversationStore(projects, File(noBackupFilesDir, "workspace-conversations"))
    }
    private val keys by lazy { ApiKeyStore(this) }
    private val preferences by lazy { getSharedPreferences("workspace_ui", Context.MODE_PRIVATE) }
    private val drafts = mutableMapOf<String, String>()
    private val attachments = mutableListOf<Attachment>()
    private var selectedId: String? = null
    private var workTab = false
    private var requestGeneration = 0L
    private var activeRequest: Call? = null
    private var statusMessage = "Messages stay on your phone until you approve a provider request."
    private lateinit var root: LinearLayout
    private lateinit var projectLabel: TextView
    private lateinit var chatTab: TextView
    private lateinit var workTabButton: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var composerArea: LinearLayout
    private lateinit var composer: EditText
    private lateinit var sendButton: TextView
    private lateinit var providerButton: TextView
    private lateinit var attachmentList: LinearLayout

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addAttachment(it, photo = true) }
    }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addAttachment(it, photo = false) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(223, 245, 227))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun control(value: String, action: () -> Unit) = label(value, 14f).apply {
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
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = preferences.getString("selected_project", null)
            ?.takeIf { projects.getProject(it) != null }
        workTab = savedInstanceState?.getBoolean("workspace_work_tab") ?: false
        buildUi()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("workspace_work_tab", workTab)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) {
            if (selectedId != null && projects.getProject(selectedId!!) == null) {
                selectedId = null
                preferences.edit().remove("selected_project").apply()
                attachments.clear()
                statusMessage = "The previous project is unavailable. Choose another project."
            }
            render()
        }
    }

    override fun onStop() {
        requestGeneration++
        activeRequest?.cancel()
        activeRequest = null
        super.onStop()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(2, 6, 9))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        heading.addView(control("‹") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        projectLabel = label("Workspace", 18f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { showMenu() }
        }
        heading.addView(projectLabel, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(control("⋮") { showMenu() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(heading)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chatTab = control("Chat") { workTab = false; render() }
        workTabButton = control("Work") { workTab = true; render() }
        tabs.addView(chatTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(workTabButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(tabs)
        scroll = ScrollView(this).apply { fillViewport = true; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        composerArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        attachmentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        composerArea.addView(attachmentList)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(control("📷") { photoPicker.launch(arrayOf("image/jpeg", "image/png")) },
            LinearLayout.LayoutParams(dp(50), dp(48)))
        controls.addView(control("＋ File") {
            documentPicker.launch(arrayOf("text/plain", "text/html", "text/css", "application/json",
                "application/javascript", "application/pdf"))
        }, LinearLayout.LayoutParams(dp(86), dp(48)).apply { marginStart = dp(6) })
        providerButton = control("Choose provider") { showProviders() }
        controls.addView(providerButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        composerArea.addView(controls)
        val entry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        composer = EditText(this).apply {
            hint = "Ask anything or describe a website change…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 171, 153))
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            textSize = 15f
            minLines = 1
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(4_000))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        entry.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = control("Send") { sendMessage() }
        entry.addView(sendButton, LinearLayout.LayoutParams(dp(70), dp(50)).apply { marginStart = dp(6) })
        composerArea.addView(entry)
        root.addView(composerArea)
        setContentView(root)
    }

    private fun project() = selectedId?.let(projects::getProject)
    private fun render() {
        if (!::root.isInitialized) return
        val current = project()
        projectLabel.text = current?.name ?: "Workspace · No project"
        chatTab.setTextColor(if (workTab) Color.GRAY else Color.rgb(168, 255, 178))
        workTabButton.setTextColor(if (workTab) Color.rgb(168, 255, 178) else Color.GRAY)
        composerArea.visibility = if (workTab) View.GONE else View.VISIBLE
        providerButton.text = when (selectedProvider()) {
            WorkspaceChatGateway.Provider.OPENROUTER_FREE -> "OpenRouter free ▾"
            WorkspaceChatGateway.Provider.GEMINI_FREE_TIER -> "Gemini · free key ▾"
            null -> "Choose provider ▾"
        }
        sendButton.isEnabled = activeRequest == null
        content.removeAllViews()
        content.addView(label(statusMessage, 12f))
        if (workTab) renderWork(current) else renderChat(current)
        renderAttachments()
    }

    private fun renderChat(current: WorkspaceProject?) {
        if (current == null) {
            content.addView(label("Start a conversation. Your first message can create a private General chat project, or choose New Project from ⋮.", 16f))
            addControl("＋ New Project") { showNewProjectDialog() }
            return
        }
        val messages = runCatching { conversations.read(current.projectId) }
            .getOrElse {
                content.addView(label("Conversation storage needs attention. No other project's messages will be shown."))
                return
            }
        if (messages.isEmpty()) content.addView(label("What would you like to build or ask about?", 18f))
        messages.forEach { message ->
            val card = label((if (message.role == "user") "You" else "LYRA · Workspace") +
                "\n\n" + message.text, 14f).apply {
                setBackgroundResource(R.drawable.bg_workspace_project_card)
                setTextIsSelectable(true)
            }
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderWork(current: WorkspaceProject?) {
        if (current == null) {
            content.addView(label("Create or select a project to open its Work tools."))
            addControl("＋ New Project") { showNewProjectDialog() }
            return
        }
        content.addView(label("${current.name} · ${current.type.displayName}\nFiles, approvals and preview belong only to this project.", 15f))
        addControl("Task brief & approve scope") {
            startActivity(WorkspaceTaskActivity.intent(this, current.projectId))
        }
        addControl("AI suggestion · review and approve (one file)") {
            startActivity(WorkspaceStructuredEditActivity.intent(this, current.projectId))
        }
        addControl("Safe Edit · Undo / Keep") {
            startActivity(WorkspaceScopedEditActivity.intent(this, current.projectId))
        }
        addControl("Project files & editor") {
            startActivity(WorkspaceEditorActivity.intent(this, current.projectId))
        }
        if (current.type == WorkspaceProjectType.WEBSITE) {
            addControl("Website preview") {
                startActivity(WorkspacePreviewActivity.intent(this, current.projectId))
            }
        }
        val latest = runCatching { conversations.read(current.projectId).lastOrNull { it.role == "user" } }
            .getOrNull()
        if (latest != null) {
            content.addView(label("Latest chat request (project-local):\n${latest.text}", 13f).apply {
                setTextIsSelectable(true)
            })
        }
        content.addView(label("PROJECT FILES", 12f))
        runCatching { files.list(current.projectId) }
            .onSuccess { entries ->
                if (entries.isEmpty()) content.addView(label("No files yet. Open the project editor to create files or add the website starter."))
                entries.filterNot { it.folder }.take(80).forEach { entry ->
                    addControl("${entry.path}  ›") {
                        runCatching {
                            files.rememberActive(current.projectId, entry.path)
                            startActivity(WorkspaceEditorActivity.intent(this, current.projectId))
                        }.onFailure { toast(it.message ?: "File is unavailable") }
                    }
                }
            }.onFailure { content.addView(label("File explorer unavailable: ${it.message.orEmpty()}")) }
    }

    private fun showMenu() {
        val popup = PopupMenu(this, projectLabel)
        popup.menu.add(0, 1, 0, "Plugins")
        popup.menu.add(0, 2, 1, "New Project")
        popup.menu.add(0, 3, 2, "API & Cloud Settings")
        val available = projects.listProjects()
        available.forEachIndexed { index, item ->
            popup.menu.add(0, 100 + index, index + 3,
                "${if (item.projectId == selectedId) "✓ " else ""}${item.name}")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showPlugins()
                2 -> showNewProjectDialog()
                3 -> startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
                else -> available.getOrNull(item.itemId - 100)?.let { selectProject(it.projectId) }
            }
            true
        }
        popup.show()
    }

    private fun showPlugins() {
        AlertDialog.Builder(this)
            .setTitle("Workspace Plugins")
            .setMessage("No validated Workspace plugin connection is registered for this project. " +
                "The app's API provider keys are not plugin permissions. No project file, chat or attachment " +
                "will be shared with an integration from this menu. Configure providers separately in API & Cloud Settings.")
            .setNegativeButton("Close", null)
            .setPositiveButton("API settings") { _, _ ->
                startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
            }.show()
    }

    private fun selectProject(id: String) {
        if (projects.getProject(id) == null) { toast("Project unavailable"); return }
        val old = selectedId
        if (old != null) drafts[old] = composer.text.toString()
        requestGeneration++
        activeRequest?.cancel()
        activeRequest = null
        selectedId = id
        projects.markOpened(id)
        preferences.edit().putString("selected_project", id).apply()
        attachments.clear()
        composer.setText(drafts[id].orEmpty())
        statusMessage = "${projects.getProject(id)?.name} selected. Other projects' conversations stay separate."
        render()
    }

    private fun showNewProjectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_workspace_project, null)
        val name = view.findViewById<EditText>(R.id.projectNameInput)
        val typeGroup = view.findViewById<RadioGroup>(R.id.projectTypeGroup)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(0),
                WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        view.findViewById<TextView>(R.id.cancelProjectButton).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.createProjectButton).setOnClickListener {
            val type = if (typeGroup.checkedRadioButtonId == R.id.projectTypeAndroid)
                WorkspaceProjectType.ANDROID_APP else WorkspaceProjectType.WEBSITE
            runCatching { projects.createProject(name.text.toString(), type) }
                .onSuccess { created -> dialog.dismiss(); selectProject(created.projectId) }
                .onFailure { name.error = it.message ?: "Project could not be created" }
        }
    }

    private fun selectedProvider(): WorkspaceChatGateway.Provider? = when (
        preferences.getString("workspace_provider", null)
    ) {
        "openrouter_free" -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        "gemini_free" -> WorkspaceChatGateway.Provider.GEMINI_FREE_TIER
        else -> null
    }

    private fun keyFor(provider: WorkspaceChatGateway.Provider): String =
        keys.get(if (provider == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
            ApiKeyStore.OPENROUTER else ApiKeyStore.GEMINI)

    private fun showProviders() {
        val configured = runCatching {
            WorkspaceChatGateway.Provider.values().filter { keyFor(it).isNotBlank() }
        }.getOrElse { toast("Secure key storage is unavailable: ${it.javaClass.simpleName}"); return }
        if (configured.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No Workspace provider key")
                .setMessage("Save a Gemini or OpenRouter key in API & Cloud Settings. Voice model selection does not configure Workspace coding.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
                }.show()
            return
        }
        val names = configured.map {
            if (it == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
                "OpenRouter · $0 free router (quota may apply)" else
                "Gemini 2.5 Flash · requires your own free-tier key"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose Workspace provider")
            .setItems(names) { _, index ->
                val provider = configured[index]
                preferences.edit().putString("workspace_provider",
                    if (provider == WorkspaceChatGateway.Provider.OPENROUTER_FREE) "openrouter_free" else "gemini_free").apply()
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addAttachment(uri: Uri, photo: Boolean) {
        if (selectedId == null) { toast("Select a project before attaching files"); return }
        if (attachments.size >= 3) { toast("Maximum three local attachments"); return }
        if (uri.scheme != "content") { toast("Only Android document-provider files are accepted"); return }
        val details = runCatching {
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
            require(size >= 0L) { "Cannot verify file size" }
            if (photo) {
                require(mime == "image/jpeg" || mime == "image/png") { "Only JPEG and PNG photos are supported" }
                require(size in 1..2_000_000) { "Photos must be 2 MB or smaller" }
                require(attachments.none { it.mime.startsWith("image/") }) { "Attach one photo per message" }
            } else {
                require(mime in setOf("text/plain", "text/html", "text/css", "application/json",
                    "application/javascript")) {
                    "PDF and this document type are not supported by Workspace chat yet. Nothing was uploaded."
                }
                require(size in 1..3_000) { "Text documents must be 3 KB or smaller" }
            }
            Attachment(uri, name, mime, size)
        }.getOrElse { toast(it.message ?: "Cannot inspect attachment"); return }
        attachments.add(details)
        renderAttachments()
    }

    private fun renderAttachments() {
        if (!::attachmentList.isInitialized) return
        attachmentList.removeAllViews()
        attachments.forEach { attachment ->
            attachmentList.addView(control("${attachment.name} · ${attachment.size} bytes  ✕") {
                attachments.remove(attachment)
                renderAttachments()
            }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(4) })
        }
    }

    private fun readAttachmentText(attachment: Attachment): String {
        val bytes = contentResolver.openInputStream(attachment.uri)?.use { it.readNBytes(3_001) }
            ?: throw IllegalArgumentException("Cannot read the selected document")
        require(bytes.isNotEmpty() && bytes.size <= 3_000) { "Document changed or is too large" }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        require(text.isNotBlank()) { "Document is empty" }
        return text
    }

    private fun sendMessage() {
        if (activeRequest != null) { toast("One request is already in progress"); return }
        val text = composer.text.toString().trim()
        if (text.isEmpty()) { toast("Write a message first"); return }
        if (selectedId == null) {
            AlertDialog.Builder(this).setTitle("Start a private conversation?")
                .setMessage("Create a General chat Workspace project for this message and its history?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create & continue") { _, _ ->
                    runCatching { projects.createProject("General chat", WorkspaceProjectType.WEBSITE) }
                        .onSuccess { selectProject(it.projectId); composer.setText(text); sendMessage() }
                        .onFailure { toast(it.message ?: "Project creation failed") }
                }.show()
            return
        }
        val id = selectedId ?: return
        val selected = selectedProvider()
        val picked = attachments.toList()
        val stored = runCatching { conversations.append(id, "user", text) }
            .getOrElse { toast(it.message ?: "Cannot save the message"); return }
        composer.text.clear()
        drafts.remove(id)
        attachments.clear()
        render()
        if (selected == null) {
            statusMessage = "Message saved locally. Select a provider to request an AI response."
            render()
            showProviders()
            return
        }
        val providerName = if (selected == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
            "OpenRouter's $0 free-model router" else "Gemini 2.5 Flash"
        val list = picked.joinToString { "${it.name} (${it.mime})" }
        val warning = if (selected == WorkspaceChatGateway.Provider.GEMINI_FREE_TIER)
            "Only proceed if this Gemini API project is on the free tier with billing disabled; LYRA cannot inspect your billing account. " else
            "Free quota or a privacy-compatible route may be unavailable. "
        AlertDialog.Builder(this).setTitle("Send to $providerName?")
            .setMessage("Send up to eight recent messages from ${project()?.name} to $providerName. " +
                "${if (list.isEmpty()) "No attachments selected." else "Also send the selected attachments: $list."} " +
                "No project source is read or sent automatically. $warning" +
                "No automatic provider switching or paid fallback. Cancel keeps the message local.")
            .setNegativeButton("Keep local", null)
            .setPositiveButton("Send once") { _, _ -> requestReply(id, stored.id, selected, picked) }
            .show()
    }

    private fun requestReply(id: String, messageId: String, provider: WorkspaceChatGateway.Provider,
                             picked: List<Attachment>) {
        if (selectedId != id || activeRequest != null) return
        val history = runCatching { conversations.read(id) }.getOrElse { toast("Conversation unavailable"); return }
        if (history.lastOrNull()?.id != messageId) { toast("Conversation changed; request cancelled"); return }
        val enriched = runCatching {
            val additions = picked.filterNot { it.mime.startsWith("image/") }
                .joinToString("\n\n") { "Document ${it.name}:\n${readAttachmentText(it)}" }
            val last = history.last()
            val expanded = last.text + if (additions.isBlank()) "" else "\n\n$additions"
            require(expanded.length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
                "Attachments exceed the private request limit"
            }
            history.dropLast(1) + last.copy(text = expanded)
        }.getOrElse { toast(it.message ?: "Attachment is unavailable"); return }
        val image = picked.firstOrNull { it.mime.startsWith("image/") }?.let { attachment ->
            runCatching {
                val bytes = contentResolver.openInputStream(attachment.uri)?.use { it.readNBytes(2_000_001) }
                    ?: throw IllegalArgumentException("Cannot read photo")
                require(bytes.isNotEmpty() && bytes.size <= 2_000_000) { "Photo changed or exceeds 2 MB" }
                WorkspaceChatGateway.Image(attachment.mime,
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }.getOrElse { toast(it.message ?: "Photo is unavailable"); return }
        }
        val request = runCatching {
            WorkspaceChatGateway.request(provider, keyFor(provider), enriched, image)
        }.getOrElse { toast(it.message ?: "Provider is unavailable"); return }
        val generation = ++requestGeneration
        val call = WorkspaceChatGateway.client.newCall(request)
        activeRequest = call
        statusMessage = "Requesting one response from ${provider.name}. No file write authorized."
        render()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                completeRequest(call, generation, id, Result.failure(
                    IllegalStateException("Connection failed or request cancelled. No retry or paid fallback.")))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { WorkspaceChatGateway.read(provider, response) }
                completeRequest(call, generation, id, result)
            }
        })
    }

    private fun completeRequest(call: Call, generation: Long, id: String, result: Result<String>) {
        runOnUiThread {
            if (isFinishing || isDestroyed || generation != requestGeneration ||
                activeRequest !== call || selectedId != id) return@runOnUiThread
            activeRequest = null
            result.onSuccess { reply ->
                runCatching { conversations.append(id, "assistant", reply) }
                    .onSuccess { statusMessage = "Response saved locally. Review code before using Work's separate write approval." }
                    .onFailure { statusMessage = "Response could not be saved; no file changed." }
            }.onFailure { statusMessage = it.message ?: "Provider failed; no file changed or paid fallback attempted." }
            render()
        }
    }
}
