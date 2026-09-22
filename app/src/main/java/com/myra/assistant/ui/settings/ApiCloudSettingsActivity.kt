package com.myra.assistant.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.databinding.ActivityApiCloudSettingsBinding
import com.myra.assistant.ui.workspace.WorkspaceFreeCrossProvider
import com.myra.assistant.ui.workspace.WorkspaceCodingAutoFallback
import com.myra.assistant.ui.workspace.WorkspaceGroqFree
import com.myra.assistant.ui.workspace.WorkspaceXKiroFree
import com.myra.assistant.ui.workspace.WorkspaceCloudflareFree
import com.myra.assistant.ui.workspace.WorkspaceWebsiteGroqFallback
import com.myra.assistant.ui.workspace.WorkspaceMemoryInterceptor

/** Non-voice provider credentials only. Gemini Live is configured in Voice & AI Models. */
class ApiCloudSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityApiCloudSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        val keys = ApiKeyStore(this)
        b.openRouterKey.setText(keys.get(ApiKeyStore.OPENROUTER))
        b.groqKey.setText(keys.get(ApiKeyStore.GROQ))
        b.xKiroKey.setText(keys.get(ApiKeyStore.XKIRO))
        b.cloudflareToken.setText(keys.get(ApiKeyStore.CLOUDFLARE_TOKEN))
        b.cloudflareAccountId.setText(keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT))
        b.deepseekKey.setText(keys.get(ApiKeyStore.DEEPSEEK))
        val workspacePrefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        b.advancedProviderControls.visibility = View.GONE
        b.advancedProviderToggle.setOnClickListener {
            val opening = b.advancedProviderControls.visibility != View.VISIBLE
            b.advancedProviderControls.visibility = if (opening) View.VISIBLE else View.GONE
            b.advancedProviderToggle.text = if (opening) "Advanced · Privacy & fallback ▴"
                else "Advanced · Privacy & fallback ▾"
        }
        b.cloudflareFreeSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceCloudflareFree.PREFERENCE_KEY, false)
        b.cloudflareFreeSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceCloudflareFree.PREFERENCE_KEY, enabled).apply()
        }
        b.xKiroWorkSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceXKiroFree.PREFERENCE_KEY, false)
        b.xKiroWorkSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, enabled).apply()
        }
        b.xKiroFallbackSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceCodingAutoFallback.PREFERENCE_KEY, false)
        b.xKiroFallbackSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceCodingAutoFallback.PREFERENCE_KEY, enabled).apply()
        }
        b.workspaceMemorySwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceMemoryInterceptor.PREFERENCE_KEY, false)
        b.workspaceMemorySwitch.setOnCheckedChangeListener { _, enabled ->
            // This explicit setting is persisted immediately; no per-message permission popup.
            workspacePrefs.edit().putBoolean(WorkspaceMemoryInterceptor.PREFERENCE_KEY, enabled).apply()
        }
        // A saved key is never consent to send personal text to a second company. Groq has
        // no enforceable API-side $0 ceiling; this confirmation is valid only while the
        // account stays on Free tier with inference ZDR enabled.
        b.groqFreeZdrSwitch.isChecked = workspacePrefs.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        b.groqFreeZdrSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceGroqFree.PREFERENCE_KEY, enabled).apply()
        }
        // Independent opt-in: enabling Groq or merely saving two keys never enables failover.
        b.workspaceFreeCrossProviderSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceFreeCrossProvider.PREFERENCE_KEY, false)
        b.workspaceFreeCrossProviderSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceFreeCrossProvider.PREFERENCE_KEY, enabled).apply()
        }
        // Website source is more sensitive than selected chat text: a separate opt-in
        // is required before any existing HTML/CSS/JS is sent to Groq after OpenRouter 429.
        b.websiteGroqFallbackSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, false)
        b.websiteGroqFallbackSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, enabled).apply()
        }
        b.backButton.setOnClickListener { finish() }
        b.deepResearchButton.setOnClickListener {
            startActivity(Intent(this, DeepResearchSettingsActivity::class.java))
        }
        b.saveButton.setOnClickListener {
            // Never touch the Gemini key or legacy conversation_provider preference here.
            keys.put(ApiKeyStore.OPENROUTER, b.openRouterKey.text.toString())
            keys.put(ApiKeyStore.GROQ, b.groqKey.text.toString())
            keys.put(ApiKeyStore.XKIRO, b.xKiroKey.text.toString())
            keys.put(ApiKeyStore.CLOUDFLARE_TOKEN, b.cloudflareToken.text.toString())
            keys.put(ApiKeyStore.CLOUDFLARE_ACCOUNT, b.cloudflareAccountId.text.toString())
            keys.put(ApiKeyStore.DEEPSEEK, b.deepseekKey.text.toString())
            Toast.makeText(this, "API configuration saved", Toast.LENGTH_SHORT).show()
        }
    }
}
