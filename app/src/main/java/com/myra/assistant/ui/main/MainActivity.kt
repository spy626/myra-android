package com.myra.assistant.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.ActivityManager
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import android.os.BatteryManager
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
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
import com.myra.assistant.databinding.ActivityMainBinding
import com.myra.assistant.databinding.SheetSettingsBinding
import com.myra.assistant.ui.settings.SettingsActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var audio: AudioEngine? = null
    private var live: GeminiLiveClient? = null
    private lateinit var appActions: AppActionExecutor
    private var muted = false
    private var suppressModelForTurn = false
    private var lastCommandKey = ""
    private var lastCommandAt = 0L
    private val commandProbe = StringBuilder()
    private val input = StringBuilder(); private val output = StringBuilder()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) showStatus("Microphone permission required") }
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
        appActions = AppActionExecutor(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
        b.settingsButton.setOnClickListener { showSettings() }
        updateDeviceStatus()
        b.connectButton.setOnClickListener { if (!MyraVoiceService.isRunning) connect() else disconnect() }
        b.sendButton.setOnClickListener {
            b.textInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                addBubble(it, true)
                if (!executeAppCommand(it)) {
                    if (!MyraVoiceService.isRunning) showStatus("Connect to MYRA first — tap the mic") else MyraVoiceService.sendText(it)
                }
                b.textInput.text.clear()
            }
        }
        b.stopButton.setOnClickListener { MyraVoiceService.interrupt(); showStatus("Stopped") }
        b.muteButton.setOnClickListener { muted = !muted; startService(Intent(this, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_MUTE).putExtra(MyraVoiceService.EXTRA_MUTED, muted)); b.muteButton.alpha = if (muted) 1f else .6f; showStatus(if (muted) "Microphone muted" else "Sun rahi hoon…") }
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
        val key = ApiKeyStore(this).get(ApiKeyStore.GEMINI)
        if (key.isBlank()) { showSettings(); showStatus("Add your API key first"); return }
        MyraVoiceService.listener = voiceListener
        ContextCompat.startForegroundService(this, Intent(this, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_START))
        b.orb.state = OrbAnimationView.State.CONNECTING; showStatus("Starting background voice…")
    }

    private fun systemPrompt(name: String, mode: String): String {
        val style = when (mode) { "Professional" -> "Formal English, precise, no emoji, at most two sentences."; "Assistant" -> "Friendly Hinglish or English, balanced and helpful, at most three sentences."; else -> "Warm caring Hinglish girlfriend-style companion. Use natural words like haan, acha, bilkul. At most three sentences." }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "You are MYRA speaking ALOUD to $name. Current date/time: $now. $style Keep every response natural, conversational and safe. When the user asks to open or close an Android app, reply only with a brief acknowledgement such as Okay; never claim the action succeeded because the Android command layer reports the real result."
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
        s.sheetApiKey.setText(p.getString("api_key", "")); s.sheetName.setText(p.getString("user_name", "Zopy"))
        s.modelChoice.text = modelLabels[modelIndex]; s.voiceChoice.text = voiceLabels[voiceIndex]
        when (p.getString("personality", "GF")) { "Professional" -> s.proMode.isChecked = true; "Assistant" -> s.assistantMode.isChecked = true; else -> s.gfMode.isChecked = true }
        s.micStatus.text = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Granted ✓" else "Not yet granted — tap the mic to allow"
        s.accessibilityStatus.text = if (AccessibilityHelperService.isEnabled(this)) "Enabled ✓ — MYRA can close the current app" else "Disabled — tap here to enable close-app control"
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
            p.edit().putString("api_key",s.sheetApiKey.text.toString().trim()).putString("user_name",s.sheetName.text.toString().trim())
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
        }
        val row = LinearLayout(this).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            val gap = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, gap / 2, 0, gap / 2)
            addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        b.chatContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        b.chatScroll.post { b.chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
    private fun executeAppCommand(text: String): Boolean {
        val command = CommandParser.parse(text) ?: return false
        executeAppCommand(command)
        return true
    }
    private fun executeAppCommand(command: AppCommand) {
        if (command is AppCommand.DeepResearch) {
            if (!MyraVoiceService.isRunning) {
                val message = "Connect MYRA first, then start Deep Research."
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
            AppCommand.RepeatYouTubeSearch -> "youtube-search:repeat"
            is AppCommand.DeepResearch -> "research:${command.query.orEmpty().lowercase(Locale.ROOT)}"
            is AppCommand.ReplyWhatsApp -> "whatsapp-reply:${command.sender.orEmpty().lowercase(Locale.ROOT)}:${command.message.lowercase(Locale.ROOT)}"
        }
        if (key == lastCommandKey && now - lastCommandAt < 2_000L) return false
        lastCommandKey = key
        lastCommandAt = now
        return true
    }
    private fun showStatus(text: String) { b.statusText.text = text }
    private fun disconnect() { startService(Intent(this, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_STOP)); b.connectButton.clearColorFilter(); b.orb.state = OrbAnimationView.State.IDLE; showStatus("Tap the mic to wake MYRA") }
    override fun onResume() { super.onResume(); MyraVoiceService.listener = voiceListener; if (MyraVoiceService.isRunning) b.connectButton.setColorFilter(Color.WHITE) }
    override fun onDestroy() { if (MyraVoiceService.listener === voiceListener) MyraVoiceService.listener = null; super.onDestroy() }
}
