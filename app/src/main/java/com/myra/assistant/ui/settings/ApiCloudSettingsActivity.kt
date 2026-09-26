package com.myra.assistant.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.databinding.ActivityApiCloudSettingsBinding
import com.myra.assistant.ui.workspace.WorkspaceFreeCrossProvider
import com.myra.assistant.ui.workspace.WorkspaceCodingAutoFallback
import com.myra.assistant.ui.workspace.WorkspaceGroqFree
import com.myra.assistant.ui.workspace.WorkspaceLlm7Free
import com.myra.assistant.ui.workspace.WorkspaceXKiroFree
import com.myra.assistant.ui.workspace.WorkspaceZaiFree
import com.myra.assistant.ui.workspace.WorkspaceCustomProviderProfile
import com.myra.assistant.ui.workspace.WorkspaceCustomProviderStore
import com.myra.assistant.ui.workspace.WorkspaceCustomProviderConnection
import com.myra.assistant.ui.workspace.WorkspaceWebsiteGroqFallback
import com.myra.assistant.ui.workspace.WorkspaceMemoryInterceptor

/** Non-voice provider credentials only. Gemini Live is configured in Voice & AI Models. */
class ApiCloudSettingsActivity : AppCompatActivity() {
    private var customProviderTestCall: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityApiCloudSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        val keys = ApiKeyStore(this)
        b.openRouterKey.setText(keys.get(ApiKeyStore.OPENROUTER))
        b.groqKey.setText(keys.get(ApiKeyStore.GROQ))
        b.llm7Key.setText(keys.get(ApiKeyStore.LLM7))
        b.xKiroKey.setText(keys.get(ApiKeyStore.XKIRO))
        b.zaiKey.setText(keys.get(ApiKeyStore.ZAI))
        b.deepseekKey.setText(keys.get(ApiKeyStore.DEEPSEEK))
        val workspacePrefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        val customProfile = WorkspaceCustomProviderStore.load(this)
        b.customProviderName.setText(customProfile?.displayName.orEmpty())
        b.customProviderBaseUrl.setText(customProfile?.baseUrl.orEmpty())
        b.customProviderModel.setText(customProfile?.modelId.orEmpty())
        b.customProviderKey.setText(customProfile?.let { keys.get(it.encryptedKeySlot) }.orEmpty())
        b.customProviderLocalSwitch.isChecked = customProfile?.localEndpoint == true
        b.customProviderChatSwitch.isChecked = customProfile != null &&
            WorkspaceCustomProviderStore.chatEnabled(this)
        // One-time retirement of old encrypted credentials and opt-in settings.
        keys.remove("cloudflare_workers_ai_token")
        keys.remove("cloudflare_workers_ai_account")
        workspacePrefs.edit().remove("workspace_cloudflare_free_direct_opt_in")
            .remove("workspace_cloudflare_selected_free_model").apply()
        b.advancedProviderControls.visibility = View.GONE
        b.advancedProviderToggle.setOnClickListener {
            val opening = b.advancedProviderControls.visibility != View.VISIBLE
            b.advancedProviderControls.visibility = if (opening) View.VISIBLE else View.GONE
            b.advancedProviderToggle.text = if (opening) "Advanced · Privacy & fallback ▴"
                else "Advanced · Privacy & fallback ▾"
        }
        b.customProviderToggle.setOnClickListener {
            val opening = b.customProviderControls.visibility != View.VISIBLE
            b.customProviderControls.visibility = if (opening) View.VISIBLE else View.GONE
            b.customProviderToggle.text = if (opening) "Custom API Base URL ▴"
                else "Custom API Base URL ▾"
        }
        b.customProviderTestButton.setOnClickListener {
            if (customProviderTestCall != null) {
                Toast.makeText(this, "Custom API connection test is already running",
                    Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val profile = runCatching {
                WorkspaceCustomProviderProfile.validate(
                    WorkspaceCustomProviderProfile.userDraft(
                        id = WorkspaceCustomProviderStore.DEFAULT_PROFILE_ID,
                        displayName = b.customProviderName.text.toString(),
                        baseUrl = b.customProviderBaseUrl.text.toString(),
                        modelId = b.customProviderModel.text.toString(),
                        localEndpoint = b.customProviderLocalSwitch.isChecked,
                    )
                )
            }.getOrElse {
                b.customProviderTestStatus.text = it.message ?: "Custom API profile is invalid"
                return@setOnClickListener
            }
            val key = b.customProviderKey.text.toString().trim()
            val request = runCatching {
                WorkspaceCustomProviderConnection.request(profile, key)
            }.getOrElse {
                b.customProviderTestStatus.text = it.message ?: "Custom API request is invalid"
                return@setOnClickListener
            }
            val call = WorkspaceCustomProviderConnection.client(profile).newCall(request)
            customProviderTestCall = call
            b.customProviderTestButton.isEnabled = false
            b.customProviderTestStatus.text =
                "Testing with synthetic text only · no chat, memory or project source is sent."
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        if (customProviderTestCall === call) customProviderTestCall = null
                        b.customProviderTestButton.isEnabled = true
                        b.customProviderTestStatus.text =
                            "Connection failed or timed out. No project source was sent."
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        WorkspaceCustomProviderConnection.read(response)
                    }.fold(
                        onSuccess = { it },
                        onFailure = { it.message ?: "Custom API connection was not accepted." }
                    )
                    runOnUiThread {
                        if (customProviderTestCall === call) customProviderTestCall = null
                        b.customProviderTestButton.isEnabled = true
                        b.customProviderTestStatus.text = result
                    }
                }
            })
        }
        // Z.ai is coding-only. Retire stale Chat/vision/model prefs from older test builds.
        workspacePrefs.edit()
            .remove("workspace_zai_free_text_opt_in")
            .remove("workspace_zai_free_vision_opt_in")
            .remove("workspace_zai_free_text_model")
            .apply()
        b.zaiCodingSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceZaiFree.CODING_PREFERENCE_KEY, false)
        b.zaiCodingSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceZaiFree.CODING_PREFERENCE_KEY, enabled).apply()
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
        b.llm7FreeSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceLlm7Free.PREFERENCE_KEY, false)
        b.llm7FreeSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceLlm7Free.PREFERENCE_KEY, enabled).apply()
        }
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
            val customName = b.customProviderName.text.toString().trim()
            val customBase = b.customProviderBaseUrl.text.toString().trim()
            val customModel = b.customProviderModel.text.toString().trim()
            val customKey = b.customProviderKey.text.toString().trim()
            val customAllBlank = customName.isBlank() && customBase.isBlank() &&
                customModel.isBlank() && customKey.isBlank()
            if (customAllBlank) {
                WorkspaceCustomProviderStore.load(this)?.let { keys.remove(it.encryptedKeySlot) }
                WorkspaceCustomProviderStore.clear(this)
            } else {
                val profile = runCatching {
                    WorkspaceCustomProviderProfile.validate(
                        WorkspaceCustomProviderProfile.userDraft(
                            id = WorkspaceCustomProviderStore.DEFAULT_PROFILE_ID,
                            displayName = customName,
                            baseUrl = customBase,
                            modelId = customModel,
                            localEndpoint = b.customProviderLocalSwitch.isChecked,
                        )
                    )
                }.getOrElse {
                    Toast.makeText(this, it.message ?: "Custom API profile is invalid",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (customKey.length > 512 || customKey.any { it == '\n' || it == '\r' }) {
                    Toast.makeText(this, "Custom API key is too long or contains line breaks",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                WorkspaceCustomProviderStore.save(this, profile)
                keys.put(profile.encryptedKeySlot, customKey)
                WorkspaceCustomProviderStore.setChatEnabled(
                    this, b.customProviderChatSwitch.isChecked)
            }
            // Never touch the Gemini key or legacy conversation_provider preference here.
            keys.put(ApiKeyStore.OPENROUTER, b.openRouterKey.text.toString())
            keys.put(ApiKeyStore.GROQ, b.groqKey.text.toString())
            keys.put(ApiKeyStore.LLM7, b.llm7Key.text.toString())
            keys.put(ApiKeyStore.XKIRO, b.xKiroKey.text.toString())
            keys.put(ApiKeyStore.ZAI, b.zaiKey.text.toString())
            keys.put(ApiKeyStore.DEEPSEEK, b.deepseekKey.text.toString())
            Toast.makeText(this, "API configuration saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        customProviderTestCall?.cancel()
        customProviderTestCall = null
        super.onDestroy()
    }
}
