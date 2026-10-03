#!/usr/bin/env python3
"""Apply the narrow, consented Work website OpenRouter 429 -> Groq Free patch."""
from pathlib import Path

BASE = Path('app/src/main')

def edit(path, old, new):
    p = BASE / path
    source = p.read_text()
    if source.count(old) != 1:
        raise SystemExit(f'Expected exactly one anchor in {p}: {source.count(old)}')
    p.write_text(source.replace(old, new, 1))

edit('java/com/myra/assistant/ui/workspace/WorkspaceChatCodingFlow.kt',
     'import androidx.appcompat.app.AlertDialog',
     'import android.content.Context\nimport androidx.appcompat.app.AlertDialog')
edit('java/com/myra/assistant/ui/workspace/WorkspaceChatCodingFlow.kt',
     '''            override fun onResponse(call: Call, response: Response) = completeWebsite(
                call, serial, id, snapshot, runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
        })
    }

    private fun completeWebsite''',
     '''            override fun onResponse(call: Call, response: Response) {
                if (response.code == 429) {
                    // Only a definitive final HTTP rejection can trigger another provider.
                    // Close the first response before attempting the separately consented resend.
                    response.close()
                    fallbackWebsiteOn429(call, serial, id, snapshot)
                    return
                }
                completeWebsite(call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            }
        })
    }

    private fun fallbackWebsiteOn429(first: Call, serial: Long, id: String,
                                     snapshot: WorkspaceWebsiteGeneration.Snapshot) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== first || !current(id)) return@runOnUiThread
            val preferences = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            val optedIn = preferences.getBoolean(WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, false)
            val groqFree = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
            val key = runCatching { keys.get(ApiKeyStore.GROQ) }.getOrDefault("")
            if (!WorkspaceWebsiteGroqFallback.eligible(429, optedIn, groqFree, key)) {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("OpenRouter Free HTTP 429. Website Groq fallback is unavailable or OFF. " +
                        "To enable a one-time automatic switch, save a Groq Free key and enable " +
                        "Groq Free/ZDR plus the separate website-source fallback setting. No paid fallback.")))
                return@runOnUiThread
            }
            val secondRequest = runCatching { WorkspaceWebsiteGroqFallback.request(key, snapshot) }
                .getOrElse { issue ->
                    completeWebsite(first, serial, id, snapshot, Result.failure(
                        IllegalStateException("OpenRouter 429; Groq Free website fallback not sent: " +
                            "${issue.message}. No files changed.")))
                    return@runOnUiThread
                }
            val second = WorkspaceWebsiteGeneration.client.newCall(secondRequest)
            request = second
            report("OpenRouter Free rate-limited; trying Groq Free once for this website · Stop ■ to cancel.")
            second.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val message = if (e is java.net.SocketTimeoutException ||
                        e is java.io.InterruptedIOException)
                        "Groq Free website fallback timed out. No uncertain request was resent."
                    else "Groq Free website fallback connection failed. No paid fallback."
                    completeWebsite(call, serial, id, snapshot,
                        Result.failure(IllegalStateException(message)))
                }
                override fun onResponse(call: Call, response: Response) = completeWebsite(
                    call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            })
        }
    }

    private fun completeWebsite''')
edit('java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGeneration.kt',
     '''        require(result.isSuccessful) {
            WorkspaceFreeAiSuggestion.httpFailure(result.code, result.header("Retry-After"))
        }''',
     '''        require(result.isSuccessful) {
            if (result.request.url.toString() == WorkspaceGroqFree.ENDPOINT)
                "Groq Free HTTP ${result.code}: website fallback refused or quota-limited. " +
                    "No further retry or paid fallback; project files unchanged."
            else WorkspaceFreeAiSuggestion.httpFailure(result.code, result.header("Retry-After"))
        }''')
edit('java/com/myra/assistant/ui/settings/ApiCloudSettingsActivity.kt',
     'import com.myra.assistant.ui.workspace.WorkspaceGroqFree',
     'import com.myra.assistant.ui.workspace.WorkspaceGroqFree\nimport com.myra.assistant.ui.workspace.WorkspaceWebsiteGroqFallback')
edit('java/com/myra/assistant/ui/settings/ApiCloudSettingsActivity.kt',
     '''        b.backButton.setOnClickListener { finish() }''',
     '''        // Website source is more sensitive than selected chat text: a separate opt-in
        // is required before any existing HTML/CSS/JS is sent to Groq after OpenRouter 429.
        b.websiteGroqFallbackSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, false)
        b.websiteGroqFallbackSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, enabled).apply()
        }
        b.backButton.setOnClickListener { finish() }''')
edit('res/layout/activity_api_cloud_settings.xml',
     '''    <TextView style="@style/MyraLabel" android:text="DEEPSEEK API KEYS"/>''',
     '''    <CheckBox android:id="@+id/websiteGroqFallbackSwitch" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="6dp" android:minHeight="48dp" android:buttonTint="#B9DEBF" android:text="Allow automatic OpenRouter 429 → Groq Free WEBSITE fallback" android:textColor="#B9DEBF" android:textSize="14sp"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="9dp" android:text="OFF by default. Requires both keys, Groq Free/ZDR enabled, and your Groq account remaining Free. If a website build receives a final OpenRouter HTTP 429, LYRA may send its selected website goal plus existing index.html, style.css and script.js source to Groq for ONE attempt. No other files, attachments, chats, saved memory, paid model or uncertain network retry. The source may be too large for Groq Free; a second 429 or incomplete reply stops without file changes. Groq has no API-side $0 billing cap; turn this OFF before upgrading your Groq account." android:textColor="#AAA5AA" android:textSize="12sp"/>
    <TextView style="@style/MyraLabel" android:text="DEEPSEEK API KEYS"/>''')
print('Scoped website fallback, consent UI and diagnostics applied.')
