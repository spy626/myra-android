package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Project-aware Workspace chat and existing Work tools; never touches personal AIRI memory. */
class WorkspaceActivity : AppCompatActivity() {
    private data class Attachment(val uri: Uri, val name: String, val mime: String, val size: Long)
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private val conversations by lazy {
        WorkspaceConversationStore(projects, File(noBackupFilesDir, "workspace-conversations"))
    }
    private val keys by lazy { ApiKeyStore(this) }
    private val preferences by lazy { getSharedPreferences("workspace_ui", Context.MODE_PRIVATE) }
    private val localDrafts = mutableMapOf<String, String>()
    private val attachments = mutableListOf<Attachment>()
    private var selectedId: String? = null
    private var workTab = false
    private var requestGeneration = 0L
    private var activeRequest: Call? = null
    private var statusMessage = "Messages remain private until you approve one provider request."
    private lateinit var root: LinearLayout
    private lateinit var projectLabel: TextView
    private lateinit var chatTab: TextView
    private lateinit var workTabButton: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var composerArea: LinearLayout
    private lateinit var composer: EditText
    private lateinit var sendButton: TextView
    private lateinit var attachmentList: LinearLayout

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addAttachment(it, true) }
    }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { addAttachment(it, false) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
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
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

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
                statusMessage = "The selected project is unavailable. Choose another project."
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
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
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
        // A single compact composer in both Chat and Work. Attachment actions stay behind +.
        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 28, 24))
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.rgb(72, 101, 79))
            }
        }
        val plusButton = label("+", 26f).apply {
            gravity = Gravity.CENTER
            contentDescription = "Add photo or file"
            isClickable = true
            isFocusable = true
            setOnClickListener { showAttachmentMenu(this) }
        }
        entry.addView(plusButton, LinearLayout.LayoutParams(dp(48), dp(52)))
        composer = EditText(this).apply {
            hint = "Ask LYRA…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 171, 153))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 15f
            minLines = 1
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(4_000))
            setPadding(dp(2), dp(10), dp(6), dp(10))
        }
        entry.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = label("↑", 23f).apply {
            gravity = Gravity.CENTER
            contentDescription = "Send message"
            isClickable = true
            isFocusable = true
            setOnClickListener { sendMessage() }
        }
        entry.addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(52)))
        composerArea.addView(entry, LinearLayout.LayoutParams(-1, -2))
        root.addView(composerArea)
        setContentView(root)
    }

    private fun showAttachmentMenu(anchor: View) {
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
    private fun render() {
        if (!::root.isInitialized) return
        val current = project()
        projectLabel.text = current?.name ?: "Workspace · No project"
        chatTab.setTextColor(if (workTab) Color.GRAY else Color.rgb(168, 255, 178))
        workTabButton.setTextColor(if (workTab) Color.rgb(168, 255, 178) else Color.GRAY)
        composerArea.visibility = View.VISIBLE
        sendButton.isEnabled = activeRequest == null
        sendButton.alpha = if (activeRequest == null) 1f else .45f
        content.removeAllViews()
        content.addView(label(statusMessage, 12f))
        if (workTab) renderWork(current) else renderChat(current)
        renderAttachments()
    }

    private fun renderChat(current: WorkspaceProject?) {
        if (current == null) {
            content.addView(label("Start a conversation or choose New Project from ⋮.", 16f))
            addControl("＋ New Project") { showNewProjectDialog() }
            return
        }
        val messages = runCatching { conversations.read(current.projectId) }
            .getOrElse {
                content.addView(label("Conversation storage requires attention. No other project's messages will be shown."))
                return
            }
        if (messages.isEmpty()) content.addView(label("What would you like to build or ask about?", 18f))
        messages.forEach { message ->
            val card = label((if (message.role == "user") "You" else "LYRA · Workspace") +
                "\n\n" + message.text).apply {
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
        content.addView(label("${current.name} · ${current.type.displayName}\nFiles and approvals are scoped to this project.", 15f))
        addControl("AI change from latest Chat request") {
            startActivity(WorkspaceChatProposalActivity.intent(this, current.projectId))
        }
        addControl("Task brief & approve scope") {
            startActivity(WorkspaceTaskActivity.intent(this, current.projectId))
        }
        addControl("Existing AI patch & draft restore") {
            startActivity(WorkspaceStructuredEditActivity.intent(this, current.projectId))
        }
        addControl("Safe Edit · Undo / Keep") {
            startActivity(WorkspaceScopedEditActivity.intent(this, current.projectId))
        }
        addControl("Project files & editor") {
            startActivity(WorkspaceEditorActivity.intent(this, current.projectId))
        }
        if (current.type == WorkspaceProjectType.WEBSITE) addControl("Website preview") {
            startActivity(WorkspacePreviewActivity.intent(this, current.projectId))
        }
        runCatching { conversations.read(current.projectId).lastOrNull { it.role == "user" } }
            .getOrNull()?.let { latest ->
                content.addView(label("Latest Chat request:\n${latest.text}", 13f).apply {
                    setTextIsSelectable(true)
                })
            }
        content.addView(label("PROJECT FILES", 12f))
        runCatching { files.list(current.projectId) }
            .onSuccess { entries ->
                if (entries.isEmpty()) content.addView(label("No files yet. Use AI change to add the approved website starter or open the existing editor."))
                entries.filterNot { it.folder }.take(80).forEach { entry ->
                    addControl("${entry.path}  ›") {
                        runCatching {
                            files.rememberActive(current.projectId, entry.path)
                            startActivity(WorkspaceEditorActivity.intent(this, current.projectId))
                        }.onFailure { toast(it.message ?: "File unavailable") }
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
        selectedId = id
        projects.markOpened(id)
        preferences.edit().putString("selected_project", id).apply()
        attachments.clear()
        composer.setText(localDrafts[id].orEmpty())
        statusMessage = "${projects.getProject(id)?.name} selected. Other project history remains separate."
        render()
    }

    private fun showNewProjectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_workspace_project, null)
        val name = view.findViewById<EditText>(R.id.projectNameInput)
        val group = view.findViewById<RadioGroup>(R.id.projectTypeGroup)
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
            val type = if (group.checkedRadioButtonId == R.id.projectTypeAndroid)
                WorkspaceProjectType.ANDROID_APP else WorkspaceProjectType.WEBSITE
            runCatching { projects.createProject(name.text.toString(), type) }
                .onSuccess { created -> dialog.dismiss(); selectProject(created.projectId) }
                .onFailure { name.error = it.message ?: "Project creation failed" }
        }
    }

    /** Choose only one configured route; never silently switch providers or use a paid fallback. */
    private fun selectedProvider(): WorkspaceChatGateway.Provider? {
        val openRouter = keys.get(ApiKeyStore.OPENROUTER)
        if (openRouter.isNotBlank()) return WorkspaceChatGateway.Provider.OPENROUTER_FREE
        val gemini = keys.get(ApiKeyStore.GEMINI)
        if (gemini.isNotBlank()) return WorkspaceChatGateway.Provider.GEMINI_FREE_TIER
        return null
    }

    private fun keyFor(provider: WorkspaceChatGateway.Provider): String =
        keys.get(if (provider == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
            ApiKeyStore.OPENROUTER else ApiKeyStore.GEMINI)

    private fun addAttachment(uri: Uri, photo: Boolean) {
        if (selectedId == null) { toast("Select a project before attaching files"); return }
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

    /** Works on API 26 too; never allocates more than the authorized limit plus one byte. */
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
        if (activeRequest != null) { toast("One request already in progress"); return }
        val text = composer.text.toString().trim()
        if (text.isEmpty()) { toast("Write a message first"); return }
        if (selectedId == null) {
            AlertDialog.Builder(this).setTitle("Start a private conversation?")
                .setMessage("Create a General chat project for this message and its history?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create & continue") { _, _ ->
                    runCatching { projects.createProject("General chat", WorkspaceProjectType.WEBSITE) }
                        .onSuccess { selectProject(it.projectId); composer.setText(text); sendMessage() }
                        .onFailure { toast(it.message ?: "Project creation failed") }
                }.show()
            return
        }
        val id = selectedId ?: return
        val picked = attachments.toList()
        val stored = runCatching { conversations.append(id, "user", text) }
            .getOrElse { toast(it.message ?: "Cannot save message"); return }
        composer.text.clear()
        localDrafts.remove(id)
        attachments.clear()
        val provider = runCatching { selectedProvider() }
            .getOrElse { statusMessage = "Secure key storage unavailable. Message saved locally."; render(); return }
        if (provider == null) {
            statusMessage = "Message saved locally. Configure a free route in Settings; no request was sent."
            render()
            AlertDialog.Builder(this).setTitle("No Workspace provider key")
                .setMessage("Add an OpenRouter key in API & Cloud Settings, or a Gemini key in Voice & AI Models. No provider will be contacted without consent.")
                .setNegativeButton("Close", null)
                .setPositiveButton("API settings") { _, _ ->
                    startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
                }.show()
            return
        }
        val name = if (provider == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
            "OpenRouter's $0 free-model router" else "Gemini 2.5 Flash"
        val warning = if (provider == WorkspaceChatGateway.Provider.GEMINI_FREE_TIER)
            "Only proceed if your Gemini account has free-tier access with billing disabled. " else
            "A free quota or privacy-compatible route may be unavailable. "
        val selectedDetails = if (picked.isEmpty()) "No attachments selected. " else
            "Also send: ${picked.joinToString { "${it.name} (${it.mime})" }}. "
        AlertDialog.Builder(this).setTitle("Send to $name?")
            .setMessage("Share up to eight recent messages from ${project()?.name} with $name. " +
                selectedDetails + "No project source is automatically included. " + warning +
                "No paid fallback or automatic provider switch. Cancel keeps the message local.")
            .setNegativeButton("Keep local", null)
            .setPositiveButton("Send once") { _, _ -> requestReply(id, stored.id, provider, picked) }
            .show()
        render()
    }

    private fun requestReply(id: String, messageId: String, provider: WorkspaceChatGateway.Provider,
                             picked: List<Attachment>) {
        if (selectedId != id || activeRequest != null) return
        val history = runCatching { conversations.read(id) }
            .getOrElse { toast("Conversation unavailable"); return }
        if (history.lastOrNull()?.id != messageId) { toast("Conversation changed; request cancelled"); return }
        val enriched = runCatching {
            val addition = picked.filterNot { it.mime.startsWith("image/") }
                .joinToString("\n\n") { "Document ${it.name}:\n${readAttachmentText(it)}" }
            val last = history.last()
            val expanded = last.text + if (addition.isBlank()) "" else "\n\n$addition"
            require(expanded.length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
                "Attachments exceed the private request limit"
            }
            history.dropLast(1) + last.copy(text = expanded)
        }.getOrElse { toast(it.message ?: "Document unavailable"); return }
        val image = picked.firstOrNull { it.mime.startsWith("image/") }?.let { attachment ->
            runCatching {
                val bytes = contentResolver.openInputStream(attachment.uri)?.use { it.readBounded(2_000_000) }
                    ?: throw IllegalArgumentException("Cannot read photo")
                require(bytes.isNotEmpty()) { "Photo is empty" }
                WorkspaceChatGateway.Image(attachment.mime,
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }.getOrElse { toast(it.message ?: "Photo unavailable"); return }
        }
        val request = runCatching { WorkspaceChatGateway.request(provider, keyFor(provider), enriched, image) }
            .getOrElse { toast(it.message ?: "Provider unavailable"); return }
        val serial = ++requestGeneration
        val call = WorkspaceChatGateway.client.newCall(request)
        activeRequest = call
        statusMessage = "Requesting one reply from ${provider.name}. No file write authorized."
        render()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = complete(call, serial, id,
                Result.failure(IllegalStateException("Connection failed or was cancelled. No retry or paid fallback.")))
            override fun onResponse(call: Call, response: Response) =
                complete(call, serial, id, runCatching { WorkspaceChatGateway.read(provider, response) })
        })
    }

    private fun complete(call: Call, serial: Long, id: String, result: Result<String>) {
        runOnUiThread {
            if (isFinishing || isDestroyed || serial != requestGeneration ||
                activeRequest !== call || selectedId != id) return@runOnUiThread
            activeRequest = null
            result.onSuccess { reply ->
                runCatching { conversations.append(id, "assistant", reply) }
                    .onSuccess { statusMessage = "Response saved. Review proposals in Work; writing requires separate approval." }
                    .onFailure { statusMessage = "Response could not be saved; no source changed." }
            }.onFailure { statusMessage = it.message ?: "Provider failed; no file changed." }
            render()
        }
    }
}
