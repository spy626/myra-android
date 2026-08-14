package com.myra.assistant.ui.settings

import com.myra.assistant.ai.ApiKeyStore

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val models = listOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")
    private val voices = listOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")
    private val personalities = listOf("GF", "Professional", "Assistant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivitySettingsBinding.inflate(layoutInflater); setContentView(b.root)
        val p = getSharedPreferences("myra", MODE_PRIVATE)
        b.modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        b.voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voices)
        b.personalitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, personalities)
        val keyStore = ApiKeyStore(this)
        b.apiKeyInput.setText(keyStore.get(ApiKeyStore.GEMINI)); b.nameInput.setText(p.getString("user_name", "Friend"))
        b.modelSpinner.setSelection(models.indexOf(p.getString("model", models[0])).coerceAtLeast(0))
        b.voiceSpinner.setSelection(voices.indexOf(p.getString("voice", voices[0])).coerceAtLeast(0))
        b.personalitySpinner.setSelection(personalities.indexOf(p.getString("personality", personalities[0])).coerceAtLeast(0))
        b.saveButton.setOnClickListener {
            keyStore.put(ApiKeyStore.GEMINI, b.apiKeyInput.text.toString())
            p.edit().putString("user_name", b.nameInput.text.toString().trim())
                .putString("model", models[b.modelSpinner.selectedItemPosition]).putString("voice", voices[b.voiceSpinner.selectedItemPosition])
                .putString("personality", personalities[b.personalitySpinner.selectedItemPosition]).apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show(); finish()
        }
    }
}
