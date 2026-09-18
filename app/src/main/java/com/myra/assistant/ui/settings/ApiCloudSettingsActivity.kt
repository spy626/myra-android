package com.myra.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.databinding.ActivityApiCloudSettingsBinding

/** Non-voice provider credentials only. Gemini Live is configured in Voice & AI Models. */
class ApiCloudSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityApiCloudSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        val keys = ApiKeyStore(this)
        b.openRouterKey.setText(keys.get(ApiKeyStore.OPENROUTER))
        b.groqKey.setText(keys.get(ApiKeyStore.GROQ))
        b.deepseekKey.setText(keys.get(ApiKeyStore.DEEPSEEK))
        b.backButton.setOnClickListener { finish() }
        b.deepResearchButton.setOnClickListener {
            startActivity(Intent(this, DeepResearchSettingsActivity::class.java))
        }
        b.saveButton.setOnClickListener {
            // Never touch the Gemini key or legacy conversation_provider preference here.
            keys.put(ApiKeyStore.OPENROUTER, b.openRouterKey.text.toString())
            keys.put(ApiKeyStore.GROQ, b.groqKey.text.toString())
            keys.put(ApiKeyStore.DEEPSEEK, b.deepseekKey.text.toString())
            Toast.makeText(this, "API configuration saved", Toast.LENGTH_SHORT).show()
        }
    }
}
