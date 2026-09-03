package com.myra.assistant.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.ActivityManager
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.IntentFilter
import android.provider.Settings
import android.os.BatteryManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.phone.AppActionExecutor
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.service.MyraVoiceService
import com.myra.assistant.model.AppCommand
import com.myra.assistant.MyApplication
import com.myra.assistant.commands.CommandType
import com.myra.assistant.commands.CommandParser as StructuredCommandParser
import com.myra.assistant.core.AssistantController
import com.myra.assistant.core.AssistantResult
import com.myra.assistant.core.AssistantState
import com.myra.assistant.commands.Command
import com.myra.assistant.databinding.ActivityMainBinding
import com.myra.assistant.databinding.SheetSettingsBinding
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.screen.ScreenCaptureService
import com.myra.assistant.screen.ScreenShareState
import com.myra.assistant.screen.PendingVisualActionStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var audio: AudioEngine? = null
    private var live: GeminiLiveClient? = null
    private lateinit var appActions: AppActionExecutor
    private val assistantController by lazy { (application as MyApplication).assistantController }
    private val controllerListener = object : AssistantController.Listener {
        override fun onStateChanged(state: AssistantState) = runOnUiThread {
            when (state) {
                AssistantState.PROCESSING -> showStatus("Soch rahi hoon…")
                AssistantState.EXECUTING_ACTION -> showStatus("Kar rahi hoon…")
                AssistantState.SPEAKING -> { b.orb.state = OrbAnimationView.State.SPEAKING; showStatus("Bol rahi hoon…") }
                AssistantState.WAKE_WORD_LISTENING, AssistantState.COMMAND_LISTENING -> { b.orb.state = OrbAnimationView.State.LISTENING; showStatus("Sun rahi hoon…") }
                AssistantState.ERROR -> showStatus("Kuch gadbad hui—dobara try karo")
                AssistantState.STOPPED -> showStatus("LYRA ruk gayi")
                else -> Unit
            }
        }
        override fun onResult(command: Command, result: AssistantResult) = runOnUiThread {
            addBubble(result.spokenMessage, false, !result.success)
            showStatus(result.spokenMessage)
        }
    }
    private var muted = false
    private var suppressModelForTurn = false
    private var lastCommandKey = ""
    private var lastCommandAt = 0L
    private val commandProbe = StringBuilder()
    private val input = StringBuilder(); private val output = StringBuilder()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) showStatus("Microphone permission required") }
    private val screenProjectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            ScreenCaptureService.markPermissionDenied()
            PendingVisualActionStore.clear()
            MyraVoiceService.notifyScreenProjectionPermissionResult(false)
            updateScreenVisionButton(ScreenShareState.ERROR)
            finishVoicePermissionTransition()
        } else {
            PendingVisualActionStore.markPermissionApproved()
            val service = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            ContextCompat.startForegroundService(this, service)
            finishVoicePermissionTransition()
        }
    }
    private val screenStateListener: (ScreenShareState, ByteArray?) -> Unit = { state, _ -> runOnUiThread { updateScreenVisionButton(state) } }
    private var pendingImage: ByteArray? = null
    private var pendingImageMimeType = "image/jpeg"
    private val screenshotPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val image = prepareScreenshot(uri)
        if (image == null) {
            showStatus("Screenshot read nahi hui — doosri image try karo")
        } else {
            pendingImage = image
            pendingImageMimeType = "image/jpeg"
            b.attachImageButton.setColorFilter(Color.WHITE)
            showStatus("Screenshot attached — sawaal likho ya Send dabao")
        }
    }
    private val voiceListener = object : MyraVoiceService.Listener {
        override fun onState(text: String) = runOnUiThread { showStatus(text) }
        override fun onReady() = runOnUiThread { b.connectButton.setColorFilter(Color.WHITE); showStatus("Sun rahi hoon…") }
        override fun onAmplitude(value: Float) = runOnUiThread { b.orb.amplitude = value }
        override fun onSpeaking(speaking: Boolean) = runOnUiThread { b.orb.state = if (speaking) OrbAnimationView.State.SPEAKING else OrbAnimationView.State.LISTENING; showStatus(if (speaking) "Bol rahi hoon…" else "Sun rahi hoon…") }
        override fun onUserText(text: String) = runOnUiThread { addBubble(text, true) }
        override fun onMyraText(text: String, error: Boolean) = runOnUiThread { addBubble(text, false, error) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        MyraVoiceService.listener = voiceListener
        assistantController.addListener(controllerListener)
        appActions = AppActionExecutor(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
        b.settingsButton.setOnClickListener { showSettings() }
        b.screenVisionButton.setOnClickListener { handleScreenVisionButton() }
        b.chatToggleButton.setOnClickListener { expandComposer() }
        b.characterSurface.onCharacterTapped = { speakCharacterTouchReaction() }
        updateDeviceStatus()
        b.connectButton.setOnClickListener { if (!MyraVoiceService.isRunning) connect() else disconnect() }
        b.attachImageButton.setOnClickListener {
            if (!MyraVoiceService.isRunning) showStatus("Connect to LYRA first — tap the mic")
            else screenshotPicker.launch("image/*")
        }
        b.sendButton.setOnClickListener {
            val text = b.textInput.text.toString().trim()
            val image = pendingImage
            if (image != null) {
                if (!MyraVoiceService.isRunning) {
                    showStatus("Connect to LYRA first — tap the mic")
                } else {
                    val prompt = text.ifBlank { "Analyze this screenshot and tell me naturally what you can see and what the issue may be." }
                    addBubble(if (text.isBlank()) "📷 Screenshot" else "📷 $text", true)
                    MyraVoiceService.sendImage(image, pendingImageMimeType, prompt)
                    pendingImage = null
                    b.attachImageButton.clearColorFilter()
                    b.attachImageButton.setColorFilter(Color.rgb(169, 155, 165))
                    b.textInput.text.clear()
                    collapseComposer()
                    showStatus("Screenshot dekh rahi hoon…")
                }
            } else {
                text.takeIf { it.isNotEmpty() }?.let {
                    addBubble(it, true)
                    val structured = StructuredCommandParser.parse(it)
                    if (structured.type != CommandType.UNKNOWN) {
                        if (!MyraVoiceService.isRunning || !MyraVoiceService.executeLocalText(it)) {
                            assistantController.processCommand(structured, speak = true)
                        }
                    } else {
                        if (!MyraVoiceService.isRunning) showStatus("Connect to LYRA first — tap the mic") else MyraVoiceService.sendText(it)
                    }
                    b.textInput.text.clear()
                    if (structured.type != CommandType.UNKNOWN || MyraVoiceService.isRunning) {
                        collapseComposer()
                    }
                }
            }
        }
        b.stopButton.setOnClickListener { MyraVoiceService.interrupt(); assistantController.stop(); showStatus("Stopped") }
        b.muteButton.setOnClickListener { muted = !muted; startService(Intent(this, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_MUTE).putExtra(MyraVoiceService.EXTRA_MUTED, muted)); b.muteButton.alpha = if (muted) 1f else .6f; showStatus(if (muted) "Microphone muted" else "Sun rahi hoon…") }
        handleVoiceScreenPermissionIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceScreenPermissionIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ScreenCaptureService.listeners += screenStateListener
        updateScreenVisionButton(ScreenCaptureService.currentState)
    }

    override fun onStop() {
        ScreenCaptureService.listeners -= screenStateListener
        super.onStop()
    }

    private fun handleScreenVisionButton() {
        when (ScreenCaptureService.currentState) {
            ScreenShareState.ACTIVE -> AlertDialog.Builder(this).setTitle("Screen Vision")
                .setItems(arrayOf("Pause", "Stop")) { _, which ->
                    startService(Intent(this, ScreenCaptureService::class.java).setAction(if (which == 0) ScreenCaptureService.ACTION_PAUSE else ScreenCaptureService.ACTION_STOP))
                }.show()
            ScreenShareState.PAUSED -> startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_RESUME))
            ScreenShareState.REQUESTING_PERMISSION, ScreenShareState.RESUMING, ScreenShareState.STOPPING -> Unit
            else -> {
                requestScreenProjectionPermission()
            }
        }
    }

    private fun handleVoiceScreenPermissionIntent(request: Intent?) {
        if (request?.action != ACTION_REQUEST_SCREEN_PROJECTION) return
        voicePermissionTransition = true
        request.action = null
        if (ScreenCaptureService.currentState == ScreenShareState.ACTIVE) {
            MyraVoiceService.notifyScreenProjectionPermissionResult(true)
            finishVoicePermissionTransition()
        } else if (ScreenCaptureService.currentState !in setOf(
                ScreenShareState.REQUESTING_PERMISSION, ScreenShareState.RESUMING, ScreenShareState.STOPPING
            )) {
            requestScreenProjectionPermission()
        }
    }

    private fun requestScreenProjectionPermission() {
        ScreenCaptureService.markPermissionRequesting()
        PendingVisualActionStore.markPermissionRequesting()
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenProjectionPermission.launch(manager.createScreenCaptureIntent())
    }

    private fun finishVoicePermissionTransition() {
        if (!voicePermissionTransition) return
        voicePermissionTransition = false
        // Reveal the app that initiated the voice action. Pending state is still
        // revalidated against fresh Accessibility context before anything runs.
        moveTaskToBack(true)
    }

    private fun updateScreenVisionButton(state: ScreenShareState) {
        b.screenVisionButton.text = when (state) {
            ScreenShareState.ACTIVE -> "LIVE"
            ScreenShareState.PAUSED -> "PAUSE"
            ScreenShareState.REQUESTING_PERMISSION, ScreenShareState.RESUMING -> "WAIT"
            ScreenShareState.ERROR -> "RETRY"
            else -> "OFF"
        }
        b.screenVisionButton.alpha = if (state == ScreenShareState.ACTIVE) 1f else .75f
    }

    private var voicePermissionTransition = false

    companion object {
        const val ACTION_REQUEST_SCREEN_PROJECTION = "com.myra.assistant.REQUEST_SCREEN_PROJECTION"
    }
    private fun speakCharacterTouchReaction() {
        val reactions = listOf(
            "Tum mujhe touch karke kya dekh rahe ho?",
            "Aise baar-baar touch kyun kar rahe ho, Zopy?",
            "Hey... mujhe gudgudi hoti hai.",
            "Kya hua yaar, attention chahiye?",
            "Mujhe touch karna itna pasand hai kya?",
            "Phir se touch? Bade shararti ho tum."
        )
        val preferences = getSharedPreferences("myra", MODE_PRIVATE)
        val last = preferences.getInt("last_character_reaction", -1)
        val next = reactions.indices.filter { it != last }.random()
        preferences.edit().putInt("last_character_reaction", next).apply()
        if (!MyraVoiceService.isNaturalVoiceReady) {
            showStatus("Connect LYRA first — tap the mic")
            return
        }
        val message = reactions[next]
        addBubble(message, false)
        showStatus(message)
        MyraVoiceService.speakLocal(message)
    }

    private fun prepareScreenshot(uri: android.net.Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)
                bitmap.recycle()
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateDeviceStatus() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0) level * 100 / scale else 0
        val memory = ActivityManager.MemoryInfo().also { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val totalGb = kotlin.math.ceil(memory.totalMem / 1073741824.0).toInt()
        b.deviceText.text = "$percent%\n${totalGb}GB"
        b.timeText.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    private fun connect() {
        assistantController.resume()
        val key = ApiKeyStore(this).get(ApiKeyStore.GEMINI)
        if (key.isBlank()) { showSettings(); showStatus("Add your API key first"); return }
        MyraVoiceService.listener = voiceListener
        ContextCompat.startForegroundService(this, Intent(this, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_START))
        b.orb.state = OrbAnimationView.State.CONNECTING; showStatus("Starting background voice…")
    }

    private fun systemPrompt(name: String, mode: String): String {
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Warm close-friend Hinglish companion. Never use romantic pet names such as jaan or dear. Use natural friendship words like yaar, dost, haan, acha, or bilkul only when they fit. At most three sentences." }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are LYRA speaking ALOUD to $name. Current date/time: $now. $style Keep every response natural, conversational and safe. When the user asks to open or close an Android app, reply only with a brief acknowledgement such as Okay; never claim the action succeeded because the Android command layer reports the real result."
    }
    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    @Suppress("unused")
    private fun showLegacySettings() {
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)
        val s = SheetSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(s.root)
        dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * .86f).toInt()
        dialog.behavior.isFitToContents = true
        val p = getSharedPreferences("myra", MODE_PRIVATE)
        val modelLabels = arrayOf("Flash Live (Latest · Fast)", "Native Audio (Human Voice)")
        val modelIds = arrayOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")
        val voiceLabels = arrayOf("Aoede (Female)", "Charon (Male)", "Kore (Female)", "Fenrir (Male)", "Puck (Male)", "Leda (Female)", "Orus (Male)", "Zephyr (Female)")
        val voiceIds = arrayOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")
        var modelIndex = modelIds.indexOf(p.getString("model", modelIds[0])).coerceAtLeast(0)
        var voiceIndex = voiceIds.indexOf(p.getString("voice", voiceIds[0])).coerceAtLeast(0)
        s.sheetApiKey.setText(ApiKeyStore(this).get(ApiKeyStore.GEMINI)); s.sheetName.setText(p.getString("user_name", "Zopy"))
        s.modelChoice.text = modelLabels[modelIndex]; s.voiceChoice.text = voiceLabels[voiceIndex]
        when (p.getString("personality", "GF")) { "Professional" -> s.proMode.isChecked = true; "Assistant" -> s.assistantMode.isChecked = true; else -> s.gfMode.isChecked = true }
        s.micStatus.text = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Granted ✓" else "Not yet granted — tap the mic to allow"
        s.accessibilityStatus.text = if (AccessibilityHelperService.isEnabled(this)) "Enabled ✓ — LYRA can close the current app" else "Disabled — tap here to enable close-app control"
        s.accessibilityStatus.setTextColor(if (AccessibilityHelperService.isEnabled(this)) Color.rgb(0, 230, 118) else Color.rgb(255, 80, 110))
        s.accessibilityStatus.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        fun pick(title: String, labels: Array<String>, selected: Int, chosen: (Int) -> Unit) {
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, selected) { d, which -> chosen(which); d.dismiss() }.show()
        }
        s.modelChoice.setOnClickListener { pick("AI Model", modelLabels, modelIndex) { modelIndex = it; s.modelChoice.text = modelLabels[it] } }
        s.voiceChoice.setOnClickListener { pick("Voice", voiceLabels, voiceIndex) { voiceIndex = it; s.voiceChoice.text = voiceLabels[it] } }
        val contacts = JSONArray(p.getString("prime_contacts_json", "[]"))
        fun renderContacts() {
            s.contactList.removeAllViews()
            for (i in 0 until contacts.length()) {
                val item = contacts.getJSONObject(i)
                val row = TextView(this).apply {
                    text = "${item.optString("name")}  ·  ${item.optString("number")}    ×"
                    setTextColor(Color.LTGRAY); textSize = 13f; setPadding(16, 14, 16, 14); setBackgroundResource(com.myra.assistant.R.drawable.bg_field)
                    setOnClickListener { contacts.remove(i); renderContacts() }
                }
                s.contactList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
            }
        }
        renderContacts()
        s.addContact.setOnClickListener {
            val n=s.contactName.text.toString().trim(); val number=s.contactNumber.text.toString().trim()
            if (n.isNotEmpty() && number.isNotEmpty()) { contacts.put(JSONObject().put("name",n).put("number",number)); s.contactName.text.clear(); s.contactNumber.text.clear(); renderContacts() }
        }
        s.closeSettings.setOnClickListener { dialog.dismiss() }
        s.sheetSave.setOnClickListener {
            val personality = when (s.personalityGroup.checkedRadioButtonId) { s.proMode.id -> "Professional"; s.assistantMode.id -> "Assistant"; else -> "GF" }
            ApiKeyStore(this).put(ApiKeyStore.GEMINI, s.sheetApiKey.text.toString())
            p.edit().putString("user_name",s.sheetName.text.toString().trim().ifBlank { "Zopy" })
                .putString("model",modelIds[modelIndex]).putString("voice",voiceIds[voiceIndex]).putString("personality",personality)
                .putString("prime_contacts_json",contacts.toString()).apply()
            Toast.makeText(this,"Settings saved",Toast.LENGTH_SHORT).show(); dialog.dismiss()
            if (MyraVoiceService.isRunning) { disconnect(); showStatus("Tap the mic to apply new settings") }
        }
        dialog.show()
    }
    private fun addBubble(text: String, isUser: Boolean, isError: Boolean = false) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(if (isError) Color.rgb(255, 110, 130) else Color.rgb(238, 238, 238))
            textSize = 13f
            maxWidth = (resources.displayMetrics.widthPixels * .82f).toInt()
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
            setBackgroundResource(if (isUser) com.myra.assistant.R.drawable.bg_chat_user else com.myra.assistant.R.drawable.bg_chat_myra)
            setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("LYRA chat message", text))
                Toast.makeText(this@MainActivity, "Message copied", Toast.LENGTH_SHORT).show()
                true
            }
        }
        val row = LinearLayout(this).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            val gap = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, gap / 2, 0, gap / 2)
            if (!isUser) {
                val iconSize = (34 * resources.displayMetrics.density).toInt()
                val iconGap = (7 * resources.displayMetrics.density).toInt()
                val iconPadding = (7 * resources.displayMetrics.density).toInt()
                val icon = ImageView(this@MainActivity).apply {
                    setImageResource(com.myra.assistant.R.drawable.ic_lyra_sparkle)
                    setBackgroundResource(com.myra.assistant.R.drawable.bg_circle_outline)
                    setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                    contentDescription = "LYRA"
                }
                addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.TOP
                    marginEnd = iconGap
                })
            }
            addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        b.chatContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        while (b.chatContainer.childCount > 20) b.chatContainer.removeViewAt(0)
        b.chatScroll.post { b.chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
    private fun expandComposer() {
        b.chatToggleButton.visibility = View.INVISIBLE
        b.composer.visibility = View.VISIBLE
        b.textInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.textInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseComposer() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.textInput.windowToken, 0)
        b.textInput.clearFocus()
        b.composer.visibility = View.INVISIBLE
        b.chatToggleButton.visibility = View.VISIBLE
    }

    private fun executeAppCommand(text: String): Boolean {
        val command = CommandParser.parse(text) ?: return false
        executeAppCommand(command)
        return true
    }
    private fun executeAppCommand(command: AppCommand) {
        if (command is AppCommand.DeepResearch) {
            if (!MyraVoiceService.isRunning) {
                val message = "Connect LYRA first, then start Deep Research."
                showStatus(message); addBubble(message, false, true)
            } else {
                MyraVoiceService.startDeepResearch(command.query)
            }
            return
        }
        val result = appActions.execute(command)
        showStatus(result.message)
        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
        addBubble(result.message, false, !result.success)
    }
    private fun shouldExecute(command: AppCommand): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = when (command) {
            is AppCommand.OpenApp -> "open:${command.appName.lowercase(Locale.ROOT)}"
            is AppCommand.CloseCurrentApp -> "close:${command.requestedName.orEmpty().lowercase(Locale.ROOT)}"
            is AppCommand.SearchYouTube -> "youtube-search:${command.query.lowercase(Locale.ROOT)}"
            is AppCommand.PlayYouTube -> "youtube-play:${command.query.orEmpty().lowercase(Locale.ROOT)}"
            AppCommand.OpenYouTubeShorts -> "youtube-shorts"
            AppCommand.RequestInstagramReels -> "request-instagram-reels"
            AppCommand.OpenInstagramReels -> "open-instagram-reels"
            AppCommand.TakeScreenshot -> "take-screenshot"
            AppCommand.RepeatYouTubeSearch -> "youtube-search:repeat"
            is AppCommand.DeepResearch -> "research:${command.query.orEmpty().lowercase(Locale.ROOT)}"
            is AppCommand.ReplyWhatsApp -> "whatsapp-reply:${command.sender.orEmpty().lowercase(Locale.ROOT)}:${command.message.lowercase(Locale.ROOT)}"
            AppCommand.QueryWhatsAppMessages -> "whatsapp-message-query"
            AppCommand.GoHome -> "go-home"
            AppCommand.GoBack -> "go-back"
            AppCommand.CurrentTime -> "current-time"
            AppCommand.BatteryLevel -> "battery-level"
            AppCommand.ListFeatures -> "list-features"
            is AppCommand.SetFlashlight -> "flashlight:${command.enabled}"
            is AppCommand.ControlMedia -> "media:${command.action.name.lowercase(Locale.ROOT)}"
            is AppCommand.ScrollYouTube -> "youtube-scroll:${command.direction?.name?.lowercase(Locale.ROOT) ?: "repeat"}"
        }
        if (key == lastCommandKey && now - lastCommandAt < 2_000L) return false
        lastCommandKey = key
        lastCommandAt = now
        return true
    }
    private fun showStatus(text: String) { b.statusText.text = text }
    private fun disconnect() { startService(Intent(this, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_STOP)); b.connectButton.clearColorFilter(); b.orb.state = OrbAnimationView.State.IDLE; showStatus("Tap the mic to wake LYRA") }
    override fun onResume() { super.onResume(); MyraVoiceService.listener = voiceListener; MyraVoiceService.setUiVisible(true); if (MyraVoiceService.isRunning) b.connectButton.setColorFilter(Color.WHITE) }
    override fun onPause() { MyraVoiceService.setUiVisible(false); super.onPause() }
    override fun onDestroy() { assistantController.removeListener(controllerListener); if (MyraVoiceService.listener === voiceListener) MyraVoiceService.listener = null; super.onDestroy() }
}
