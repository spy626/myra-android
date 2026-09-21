#!/usr/bin/env python3
"""ONE-SHOT, fail-closed source patch, run only by xkiro-once.yml on agent/myra-phase-1.
No credentials or network calls here. Every replacement matches exactly once or aborts.
The workflow compiles/tests before committing and removes this staging script.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "app/src/main/java/com/myra/assistant/"
WORK = BASE + "ui/workspace/"


def replace(path, before, after):
    file = ROOT / path
    source = file.read_text(encoding="utf-8")
    if source.count(before) != 1:
        raise SystemExit(f"Refusing unexpected source in {path}; expected exactly one anchor, found {source.count(before)}")
    file.write_text(source.replace(before, after, 1), encoding="utf-8")


def new_file(path, content):
    file = ROOT / path
    if file.exists():
        raise SystemExit(f"Refusing to overwrite {path}")
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(content, encoding="utf-8")


replace(BASE + "ai/ApiKeyStore.kt", 'const val GROQ="groq_api_key";',
        'const val GROQ="groq_api_key";const val XKIRO="xkiro_api_key";')
replace(WORK + "WorkspaceWebsiteRoute.kt", 'enum class Provider { OPENROUTER, GROQ }',
        'enum class Provider { OPENROUTER, GROQ, XKIRO }')

new_file(WORK + "WorkspaceXKiroFree.kt", r'''package com.myra.assistant.ui.workspace

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Optional, user-approved xKiro Free route for Work coding ONLY. No automatic fallback.
 * Public catalog + key-specific free quota are checked before EVERY source-bearing POST;
 * missing/ambiguous metadata fails closed. A :free label alone is not sufficient.
 */
internal object WorkspaceXKiroFree {
    const val PREFERENCE_KEY = "workspace_xkiro_work_opt_in"
    const val MODEL = "qwen/qwen3-coder-plus:free"
    const val ENDPOINT = "https://api.xkiro.com/v1/chat/completions"
    private const val MODELS = "https://api.xkiro.com/v1/models"
    private const val USAGE = "https://api.xkiro.com/v1/usage"
    private const val WEBSITE_OUTPUT_TOKENS = 7_000
    private const val EDIT_OUTPUT_TOKENS = 3_500
    private const val MAX_CHECK_BYTES = 500_000L

    fun validKey(key: String): Boolean = key.length in 1..256 &&
        key.none(Char::isWhitespace) && ',' !in key

    /** An exact model, free access tier, and literal zero for every pricing component. */
    internal fun catalogIsFree(raw: String, expected: String = MODEL): Boolean = runCatching {
        val rows = JSONObject(raw).getJSONArray("data")
        val model = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .singleOrNull { it.optString("id") == expected } ?: return@runCatching false
        if (expected != MODEL || model.optString("access_tier") != "free") return@runCatching false
        val pricing = model.optJSONObject("pricing") ?: return@runCatching false
        if (!pricing.has("input") || !pricing.has("output")) return@runCatching false
        if (model.has("max_output_tokens") &&
            model.optInt("max_output_tokens", -1) < WEBSITE_OUTPUT_TOKENS) return@runCatching false
        val prices = pricing.keys().asSequence().filterNot { it in setOf("currency", "unit") }.toList()
        prices.isNotEmpty() && prices.all { key ->
            val amount = pricing.opt(key)?.toString() ?: return@all false
            runCatching { BigDecimal(amount).signum() == 0 }.getOrDefault(false)
        }
    }.getOrDefault(false)

    /** Unknown/free-cap-exhausted usage never enables a source-bearing request. */
    internal fun quotaIsFree(raw: String): Boolean = runCatching {
        val free = JSONObject(raw).optJSONObject("free_tokens") ?: return@runCatching false
        free.has("remaining") && !free.isNull("remaining") &&
            free.optLong("remaining", -1) >= WEBSITE_OUTPUT_TOKENS + 1_000L
    }.getOrDefault(false)

    private val checkClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()

    private fun checkedGet(url: String, key: String? = null): String {
        val request = Request.Builder().url(url).apply {
            if (key != null) header("Authorization", "Bearer $key")
        }.get().build()
        return checkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("xKiro Free preflight unavailable; no source sent")
            val bytes = response.peekBody(MAX_CHECK_BYTES + 1L).bytes()
            if (bytes.isEmpty() || bytes.size > MAX_CHECK_BYTES) {
                throw IOException("xKiro Free preflight invalid; no source sent")
            }
            String(bytes, Charsets.UTF_8)
        }
    }

    private class FreeOnlyGate : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val header = request.header("Authorization").orEmpty()
            val key = header.removePrefix("Bearer ")
            if (request.method != "POST" || request.url.toString() != ENDPOINT ||
                !header.startsWith("Bearer ") || !validKey(key)) {
                throw IOException("xKiro Free request refused; no source sent")
            }
            try {
                if (!catalogIsFree(checkedGet(MODELS)))
                    throw IOException("xKiro Free preflight: exact model/zero price unverified; no source sent")
                if (!quotaIsFree(checkedGet(USAGE, key)))
                    throw IOException("xKiro Free preflight: remaining free quota unverified; no source sent")
            } catch (failure: IOException) {
                if (failure.message?.startsWith("xKiro Free preflight") == true) throw failure
                throw IOException("xKiro Free preflight unavailable; no source sent", failure)
            } catch (failure: Exception) {
                throw IOException("xKiro Free preflight invalid; no source sent", failure)
            }
            if (chain.call().isCanceled()) throw IOException("xKiro Free request cancelled; no source sent")
            return chain.proceed(request)
        }
    }

    /** New independent adapter; no generic retry interceptor and no hidden paid routes. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(95, TimeUnit.SECONDS)
        .callTimeout(115, TimeUnit.SECONDS)
        .addInterceptor(FreeOnlyGate()).build()

    private fun transform(key: String, canonical: JSONObject, maxTokens: Int): Request {
        require(validKey(key)) { "Save a valid xKiro Free key in API & Cloud Settings" }
        val messages = canonical.getJSONArray("messages")
        val last = messages.getJSONObject(messages.length() - 1)
        require(last.optString("role") == "user" && last.optString("content").isNotEmpty()) {
            "xKiro Work requires an approved user request"
        }
        canonical.remove("provider")
        canonical.remove("plugins")
        canonical.remove("response_format")
        canonical.put("model", MODEL).put("stream", false).put("max_tokens", maxTokens)
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(canonical.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /** Canonical three-file source and goal; change only serialization, never task meaning. */
    fun websiteRequest(key: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        val canonical = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(canonical.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        val system = payload.getJSONArray("messages").getJSONObject(0)
        val original = system.getString("content")
        val jsonOpening = "Return exactly ONE JSON object with only a files object containing exactly " +
            "index.html, style.css, script.js string fields. Each field is the COMPLETE new file " +
            "content, not a patch or a markdown code fence. "
        val jsonEnding = "No prose, explanations or markdown outside the JSON."
        require(original.contains(jsonOpening) && original.contains(jsonEnding)) {
            "Website contract changed; source not sent"
        }
        val blocks = "Return exactly THREE complete files as consecutive labelled code blocks, " +
            "NOT JSON or a patch. Respond with index.html then a fenced html block, style.css " +
            "then a fenced css block, script.js then a fenced javascript block, each on new lines. " +
            "Use exact lowercase labels, close every fence; use an empty script.js block if no JS. "
        system.put("content", original.replace(jsonOpening, blocks)
            .replace(jsonEnding, "No preface, duplicated file, fourth block or trailing prose."))
        return transform(key, payload, WEBSITE_OUTPUT_TOKENS)
    }

    /** One already-authorized source file, same scoped draft and local Safe Edit owner. */
    fun editRequest(key: String, preparedPrompt: String): Request {
        val message = WorkspaceConversationStore.Message("xkiro-one-file", "user", preparedPrompt,
            System.currentTimeMillis())
        return transform(key, JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message))),
            EDIT_OUTPUT_TOKENS)
    }

    private fun verifiedResponse(response: Response): JSONObject {
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw IllegalStateException("xKiro Free HTTP $status; no paid fallback, no files changed")
        }
        val bytes = response.peekBody(130_001L).bytes()
        require(bytes.isNotEmpty() && bytes.size <= 130_000) { "xKiro response too large; no files changed" }
        val outer = JSONObject(String(bytes, Charsets.UTF_8))
        require(outer.optString("model") == MODEL && !outer.has("error")) {
            "xKiro did not confirm the requested free model; no files changed"
        }
        return outer
    }

    fun readWebsite(response: Response): Map<String, String> = response.use {
        verifiedResponse(it)
        WorkspaceWebsiteGeneration.readResponse(it)
    }

    fun readEdit(response: Response): String = response.use {
        val outer = verifiedResponse(it)
        val choice = outer.optJSONArray("choices")?.optJSONObject(0)
        require(choice?.optString("finish_reason") == "stop") {
            "xKiro Free reply incomplete; no files changed"
        }
        val reply = choice?.optJSONObject("message")?.opt("content") as? String
        require(!reply.isNullOrBlank() && reply.length <= 30_000) {
            "xKiro Free edit missing or oversized; no files changed"
        }
        reply
    }
}
''')

flow = WORK + "WorkspaceChatCodingFlow.kt"
replace(flow,
'''        val key = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure provider key unavailable; nothing was shared."); return }
        if (key.isBlank()) {
            error("Coding request saved locally. Configure a free Workspace route in API & Cloud Settings.")
            return
        }''',
'''        val xKiroEnabled = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, false)
        val xKiroKey = if (xKiroEnabled) runCatching { keys.get(ApiKeyStore.XKIRO) }
            .getOrElse { error("Secure xKiro key unavailable; nothing was shared."); return } else ""
        val usingXKiro = xKiroEnabled && WorkspaceXKiroFree.validKey(xKiroKey)
        val key = if (usingXKiro) xKiroKey else runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure provider key unavailable; nothing was shared."); return }
        if (key.isBlank()) {
            error("Coding request saved locally. Configure a free Workspace route in API & Cloud Settings.")
            return
        }''')
replace(flow, '        prepareSource(id, instruction, key)\n',
             '        prepareSource(id, instruction, key, usingXKiro)\n')
replace(flow,
'''        val primary = WorkspaceWebsiteRoute.choose(openRouterKey, groqKey, groqFreeEnabled)
        if (primary == null) {''',
'''        val xKiroApproved = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, false)
        val xKiroKey = if (xKiroApproved) runCatching { keys.get(ApiKeyStore.XKIRO) }
            .getOrElse { error("Secure xKiro key unavailable; no source was shared."); return } else ""
        val primary = if (xKiroApproved && WorkspaceXKiroFree.validKey(xKiroKey))
            WorkspaceWebsiteRoute.Provider.XKIRO
        else WorkspaceWebsiteRoute.choose(openRouterKey, groqKey, groqFreeEnabled)
        if (primary == null) {''')
replace(flow,
'''                WorkspaceWebsiteRoute.Provider.GROQ ->
                    WorkspaceWebsiteGroqFallback.request(groqKey, snapshot)
            }
        }.getOrElse''',
'''                WorkspaceWebsiteRoute.Provider.GROQ ->
                    WorkspaceWebsiteGroqFallback.request(groqKey, snapshot)
                WorkspaceWebsiteRoute.Provider.XKIRO ->
                    WorkspaceXKiroFree.websiteRequest(xKiroKey, snapshot)
            }
        }.getOrElse''')
replace(flow,
'''        val client = if (primary == WorkspaceWebsiteRoute.Provider.GROQ)
            WorkspaceWebsiteGroqFallback.client else WorkspaceWebsiteGeneration.client''',
'''        val client = when (primary) {
            WorkspaceWebsiteRoute.Provider.GROQ -> WorkspaceWebsiteGroqFallback.client
            WorkspaceWebsiteRoute.Provider.XKIRO -> WorkspaceXKiroFree.client
            WorkspaceWebsiteRoute.Provider.OPENROUTER -> WorkspaceWebsiteGeneration.client
        }''')
replace(flow,
'''        report("Building website · free attempt 1/3 · Stop ■ to cancel.")''',
'''        report(if (primary == WorkspaceWebsiteRoute.Provider.XKIRO)
            "Building website · xKiro Free attempt 1/1 · Stop ■ to cancel."
        else "Building website · free attempt 1/3 · Stop ■ to cancel.")''')
replace(flow,
'''                val message = if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                    "Website provider timed out (up to 80 seconds). No incomplete code was saved."
                else "Website provider connection failed; no result received. No paid fallback."''',
'''                val message = if (primary == WorkspaceWebsiteRoute.Provider.XKIRO &&
                    e.message?.startsWith("xKiro Free") == true) e.message!!
                else if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                    "Website provider timed out. No incomplete code was saved."
                else "Website provider connection failed; no result received. No paid fallback."''')
replace(flow,
'''                completeWebsite(call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            }
        })''',
'''                completeWebsite(call, serial, id, snapshot,
                    runCatching { if (primary == WorkspaceWebsiteRoute.Provider.XKIRO)
                        WorkspaceXKiroFree.readWebsite(response)
                    else WorkspaceWebsiteGeneration.readResponse(response) })
            }
        })''')
replace(flow,
'''    private fun prepareSource(id: String, instruction: String, key: String) {''',
'''    private fun prepareSource(id: String, instruction: String, key: String,
                              usingXKiro: Boolean) {''')
replace(flow, '        send(id, key, prepared)\n', '        send(id, key, prepared, usingXKiro)\n')
replace(flow,
'''    private fun send(id: String, key: String, prepared: WorkspaceAiHandoff.Draft) {''',
'''    private fun send(id: String, key: String, prepared: WorkspaceAiHandoff.Draft,
                     usingXKiro: Boolean) {''')
replace(flow,
'''        val outgoing = runCatching { WorkspaceChatGateway.request(provider, key, messages) }
            .getOrElse { error("Provider request refused: ${it.message}"); return }
        val serial = ++generation
        val call = WorkspaceChatGateway.client.newCall(outgoing)''',
'''        val outgoing = runCatching { if (usingXKiro)
            WorkspaceXKiroFree.editRequest(key, prepared.prompt)
        else WorkspaceChatGateway.request(provider, key, messages) }
            .getOrElse { error("Provider request refused: ${it.message}"); return }
        val serial = ++generation
        val call = (if (usingXKiro) WorkspaceXKiroFree.client
            else WorkspaceChatGateway.client).newCall(outgoing)''')
replace(flow,
'''            override fun onFailure(call: Call, e: IOException) = complete(call, serial, id, prepared,
                Result.failure(IllegalStateException(WorkspaceFreeAiSuggestion.networkFailure(e))))
            override fun onResponse(call: Call, response: Response) = complete(call, serial, id,
                prepared, runCatching { WorkspaceChatGateway.read(provider, response) })''',
'''            override fun onFailure(call: Call, e: IOException) = complete(call, serial, id, prepared,
                Result.failure(IllegalStateException(if (usingXKiro &&
                    e.message?.startsWith("xKiro Free") == true) e.message!!
                else WorkspaceFreeAiSuggestion.networkFailure(e))))
            override fun onResponse(call: Call, response: Response) = complete(call, serial, id,
                prepared, runCatching { if (usingXKiro) WorkspaceXKiroFree.readEdit(response)
                    else WorkspaceChatGateway.read(provider, response) })''')

settings = BASE + "ui/settings/ApiCloudSettingsActivity.kt"
replace(settings, 'import android.os.Bundle\n', 'import android.os.Bundle\nimport android.view.View\n')
replace(settings,
'''        b.groqKey.setText(keys.get(ApiKeyStore.GROQ))''',
'''        b.groqKey.setText(keys.get(ApiKeyStore.GROQ))
        b.xKiroKey.setText(keys.get(ApiKeyStore.XKIRO))''')
replace(settings,
'''        val workspacePrefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)''',
'''        val workspacePrefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        b.advancedProviderControls.visibility = View.GONE
        b.advancedProviderToggle.setOnClickListener {
            val opening = b.advancedProviderControls.visibility != View.VISIBLE
            b.advancedProviderControls.visibility = if (opening) View.VISIBLE else View.GONE
            b.advancedProviderToggle.text = if (opening) "Advanced · Privacy & fallback ▴"
                else "Advanced · Privacy & fallback ▾"
        }
        b.xKiroWorkSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceXKiroFree.PREFERENCE_KEY, false)
        b.xKiroWorkSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, enabled).apply()
        }''')
replace(settings, 'import com.myra.assistant.ui.workspace.WorkspaceGroqFree\n',
        'import com.myra.assistant.ui.workspace.WorkspaceGroqFree\nimport com.myra.assistant.ui.workspace.WorkspaceXKiroFree\n')
replace(settings,
'''            keys.put(ApiKeyStore.GROQ, b.groqKey.text.toString())''',
'''            keys.put(ApiKeyStore.GROQ, b.groqKey.text.toString())
            keys.put(ApiKeyStore.XKIRO, b.xKiroKey.text.toString())''')

layout = "app/src/main/res/layout/activity_api_cloud_settings.xml"
replace(layout,
'''    <TextView style="@style/MyraLabel" android:text="OPENROUTER API KEYS (RECOMMENDED)"/>''',
'''    <TextView style="@style/MyraLabel" android:text="XKIRO FREE API KEY (OPTIONAL WORK CODING)"/>
    <EditText android:id="@+id/xKiroKey" style="@style/MyraField" android:hint="sk-xt-…" android:inputType="textPassword"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="8dp" android:text="xKiro Work coding is OFF until enabled in Advanced. Free model + quota checked before sending source." android:textColor="#AAA5AA" android:textSize="12sp"/>
    <TextView style="@style/MyraLabel" android:text="OPENROUTER API KEYS (RECOMMENDED)"/>''')
replace(layout,
'''    <CheckBox android:id="@+id/groqFreeZdrSwitch"''',
'''    <TextView android:id="@+id/advancedProviderToggle" android:layout_width="match_parent" android:layout_height="48dp" android:gravity="center_vertical" android:paddingStart="8dp" android:paddingEnd="8dp" android:clickable="true" android:focusable="true" android:text="Advanced · Privacy &amp; fallback ▾" android:textColor="#B9DEBF" android:textSize="15sp" android:contentDescription="Expand provider privacy and fallback permissions"/>
    <LinearLayout android:id="@+id/advancedProviderControls" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone">
    <CheckBox android:id="@+id/xKiroWorkSwitch" android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="48dp" android:buttonTint="#B9DEBF" android:text="Use xKiro Free for Work coding (website + one-file edits)" android:textColor="#B9DEBF" android:textSize="14sp"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="9dp" android:text="OFF by default. When enabled with a valid key, selected Work goals and source may be sent to xKiro and its upstream model provider. Free-only model and remaining free token quota are checked before each request; unverifiable terms, quota errors and network failures stop without a paid fallback. No voice, attachments, unrelated files, saved LYRA memory or automatic xKiro cross-provider resend. Switching providers does not alter existing code until normal guarded Undo/Keep processing." android:textColor="#AAA5AA" android:textSize="12sp"/>
    <CheckBox android:id="@+id/groqFreeZdrSwitch"''')
replace(layout,
'''    <TextView style="@style/MyraLabel" android:text="DEEPSEEK API KEYS"/>''',
'''    </LinearLayout>
    <TextView style="@style/MyraLabel" android:text="DEEPSEEK API KEYS"/>''')

new_file("app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceXKiroFreeTest.kt", r'''package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Offline tests; never assert a live account or phone result. */
class WorkspaceXKiroFreeTest {
    private val free = """{"data":[{"id":"qwen/qwen3-coder-plus:free","access_tier":"free","max_output_tokens":8192,"pricing":{"input":0,"output":0}},{"id":"openai/gpt-5.6-sol","access_tier":"paid","pricing":{"input":1,"output":2}}]}"""
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approval",
        "Create a responsive recipe website", mapOf("index.html" to null,
            "style.css" to "body{color:white}", "script.js" to null))

    @Test fun exactFreeRouteMustPassCatalogPriceAndQuota() {
        assertTrue(WorkspaceXKiroFree.catalogIsFree(free))
        assertFalse(WorkspaceXKiroFree.catalogIsFree(free, "openai/gpt-5.6-sol"))
        assertFalse(WorkspaceXKiroFree.catalogIsFree(free.replace("\"input\":0", "\"input\":0.01")))
        assertFalse(WorkspaceXKiroFree.catalogIsFree(free.replace("\"access_tier\":\"free\"", "\"access_tier\":\"paid\"")))
        assertFalse(WorkspaceXKiroFree.catalogIsFree("{\"data\":[]}"))
        assertFalse(WorkspaceXKiroFree.catalogIsFree("garbled"))
        assertTrue(WorkspaceXKiroFree.quotaIsFree("{\"free_tokens\":{\"remaining\":8000}}"))
        assertFalse(WorkspaceXKiroFree.quotaIsFree("{\"free_tokens\":{\"remaining\":7999}}"))
        assertFalse(WorkspaceXKiroFree.quotaIsFree("{\"free_tokens\":{\"remaining\":null}}"))
    }

    @Test fun websiteUsesExactFreeModelAndCanonicalApprovedSourceOnly() {
        val request = WorkspaceXKiroFree.websiteRequest("sk-xt-test", snapshot)
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals(WorkspaceXKiroFree.ENDPOINT, request.url.toString())
        assertEquals(WorkspaceXKiroFree.MODEL, body.getString("model"))
        assertFalse(body.has("provider")); assertFalse(body.has("plugins"))
        assertFalse(body.has("response_format"))
        assertEquals(2, body.getJSONArray("messages").length())
        val context = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(snapshot.goal, context.getString("goal"))
        assertEquals("body{color:white}", context.getJSONObject("existingFiles").getString("style.css"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content")
            .contains("Return exactly THREE complete files"))
    }

    @Test fun badKeysAreRefusedWithoutSending() {
        for (key in listOf("", "sk-xt-a,b", "sk-xt- a", " ", "x".repeat(257))) {
            assertFalse(WorkspaceXKiroFree.validKey(key))
            assertTrue(runCatching { WorkspaceXKiroFree.websiteRequest(key, snapshot) }.isFailure)
        }
    }

    @Test fun existingProviderSelectionRemainsUnchangedUntilExplicitOptIn() {
        assertEquals(WorkspaceWebsiteRoute.Provider.OPENROUTER,
            WorkspaceWebsiteRoute.choose("or-key", "", false, false))
        assertEquals(WorkspaceWebsiteRoute.Provider.GROQ,
            WorkspaceWebsiteRoute.choose("", "gsk-test", true, false))
        assertNull(WorkspaceWebsiteRoute.choose("", "", false, false))
    }
}
''')

new_file("docs/WORKSPACE_XKIRO_FREE_AUDIT_2026-09-21.md", """# xKiro Free optional Work coding — 2026-09-21

Scope: one existing Workspace coding owner; **website generation + one-file scoped edits only**. Normal chat, voice, memory and existing OpenRouter/Groq routes unchanged. xKiro API key is encrypted by existing ApiKeyStore, and Work opt-in defaults OFF in a collapsed Advanced section. Existing saved permissions are not altered. No model- or website-specific prompt patches.

Sources checked: https://docs.xkiro.com/api/chat-completions/ ; https://docs.xkiro.com/api/list-models/ ; https://docs.xkiro.com/api/usage/ ; https://xkiro.com/models ; https://xkiro.com/privacy . xKiro lists `qwen/qwen3-coder-plus:free` and publicly documents model access tier and token pricing, while its privacy policy says content is forwarded to upstream model providers and temporary diagnostic logging is possible. xKiro's exact model availability, account entitlement and upstream privacy **cannot be verified without an account**. Do not present docs/CI as live API or physical-phone acceptance.

Before every source-bearing POST, an isolated GET to the public xKiro model catalog must verify exact model ID, `access_tier=free`, and numerical zero input/output/all price fields. A separate authenticated GET /v1/usage must confirm a non-null minimum free-token remainder. If either check fails, return an error WITHOUT sending the source. Even a confirmed free route has quotas and can change. Only `/v1/chat/completions` with the pinned `:free` model is allowed, no paid automatic fallback. The catalog can be stale between preflight and inference; xKiro's documented free-tier 429 blocks further free requests. This is a bounded safeguard, not an absolute future pricing guarantee; do not add payment details.

Project source and task are included only on explicit user Send after opt-in; unrelated files, attachments, voice and saved LYRA memories are not sent. The model proposes text; existing local parse/snapshot/Undo/Keep owners remain authoritative. No automatic retries, timeout resends or cross-provider switches from xKiro. Live signup, API call, model behavior, signature compatibility and on-phone testing remain pending.
""")

print("xKiro guarded source patch prepared: provider, Work routing, advanced settings, tests, audit")
