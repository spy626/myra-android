#!/usr/bin/env python3
"""One-off guarded patch, executed only in a checked-out CI workspace.

The source commit remains unchanged if any assertion or unit test fails. Never
run against main. No API key is needed for compile/unit verification.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = 'app/src/main/java/com/myra/assistant/'
WS = SRC + 'ui/workspace/'


def edit(path, old, new):
    target = ROOT / path
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one safe edit anchor, found {count}')
    target.write_text(text.replace(old, new, 1))


def add(path, content):
    target = ROOT / path
    if target.exists():
        raise RuntimeError(f'{path} already exists; refusing overwrite')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)


add(WS + 'WorkspaceZaiFree.kt', '''package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Direct Z.ai, exact zero-list-price model allowlist; never uses Gemini Live or another gateway. */
internal object WorkspaceZaiFree {
    const val ENDPOINT = "https://api.z.ai/api/paas/v4/chat/completions"
    const val PREFERENCE_KEY = "workspace_zai_free_text_opt_in"
    const val VISION_PREFERENCE_KEY = "workspace_zai_free_vision_opt_in"
    const val MODEL_PREFERENCE_KEY = "workspace_zai_free_text_model"
    const val DEFAULT_TEXT_MODEL = "glm-4.7-flash"
    const val ALT_TEXT_MODEL = "glm-4.5-flash"
    const val VISION_MODEL = "glm-4.6v-flash"
    private const val MAX_RESPONSE_BYTES = 32_768L

    // No existing cross-provider, memory, or retry interceptors are attached.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder().build()

    fun textModel(saved: String?): String = when (saved) {
        null, DEFAULT_TEXT_MODEL -> DEFAULT_TEXT_MODEL
        ALT_TEXT_MODEL -> ALT_TEXT_MODEL
        else -> throw IllegalArgumentException("Unrecognized Z.ai free model; nothing was sent")
    }

    fun request(key: String, messages: List<WorkspaceConversationStore.Message>,
                image: WorkspaceChatGateway.Image?, textModel: String,
                visionApproved: Boolean): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Save a valid Z.ai key in API & Cloud Settings"
        }
        require(messages.isNotEmpty() && messages.last().role == "user" &&
            WorkspaceLongInputPolicy.requestFits(messages)) { "Selected chat exceeds safe request limit" }
        val model = if (image == null) textModel(textModel) else {
            require(visionApproved) { "Z.ai vision needs its separate permission; no image sent" }
            require(image.mime == "image/jpeg" || image.mime == "image/png") { "Unsupported photo format" }
            require(image.base64.length in 1..2_700_000 &&
                image.base64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
                "Photo is invalid or too large"
            }
            VISION_MODEL
        }
        // Reuse the existing same-chat system discipline and bounded raw-turn projection.
        // Remove OpenRouter-specific routing and plugin fields before contacting Z.ai.
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(messages, image))
        body.remove("provider")
        body.remove("plugins")
        body.put("model", model)
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    fun read(response: Response): String = response.use {
        require(it.isSuccessful) {
            when (it.code) {
                401, 403 -> "Z.ai refused the API key or free model access (HTTP ${it.code}). No paid fallback."
                402 -> "Z.ai requested payment (HTTP 402); LYRA stopped. No paid fallback."
                429 -> "Z.ai free model is rate-limited (HTTP 429); try later. No automatic retry."
                else -> "Z.ai free request failed (HTTP ${it.code}). No paid fallback."
            }
        }
        val bytes = it.peekBody(MAX_RESPONSE_BYTES + 1).bytes()
        require(bytes.isNotEmpty() && bytes.size <= MAX_RESPONSE_BYTES) {
            "Z.ai response is empty or exceeds safe size"
        }
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Z.ai returned invalid response") }
        require(!root.has("error")) { "Z.ai returned an error; no paid fallback" }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalArgumentException("Z.ai did not return a complete reply")
        require(choice.optString("finish_reason") == "stop") {
            "Z.ai reply was incomplete or filtered; no partial reply saved"
        }
        val content = choice.optJSONObject("message")?.opt("content")
        require(content is String && content.trim().length in 1..6_000) {
            "Z.ai returned no bounded text reply"
        }
        content.trim()
    }
}
''')

edit(SRC + 'ai/ApiKeyStore.kt', 'const val XKIRO="xkiro_api_key";',
     'const val XKIRO="xkiro_api_key";const val ZAI="zai_api_key";')

edit(SRC + 'ui/settings/ApiCloudSettingsActivity.kt',
     'import com.myra.assistant.ui.workspace.WorkspaceXKiroFree\n',
     'import com.myra.assistant.ui.workspace.WorkspaceXKiroFree\nimport com.myra.assistant.ui.workspace.WorkspaceZaiFree\n')
edit(SRC + 'ui/settings/ApiCloudSettingsActivity.kt',
     '        b.xKiroKey.setText(keys.get(ApiKeyStore.XKIRO))\n',
     '        b.xKiroKey.setText(keys.get(ApiKeyStore.XKIRO))\n        b.zaiKey.setText(keys.get(ApiKeyStore.ZAI))\n')
edit(SRC + 'ui/settings/ApiCloudSettingsActivity.kt',
     '        b.xKiroWorkSwitch.isChecked = workspacePrefs.getBoolean(\n',
     '''        b.zaiWorkSwitch.isChecked = workspacePrefs.getBoolean(WorkspaceZaiFree.PREFERENCE_KEY, false)
        b.zaiWorkSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceZaiFree.PREFERENCE_KEY, enabled).apply()
        }
        b.zaiVisionSwitch.isChecked = workspacePrefs.getBoolean(WorkspaceZaiFree.VISION_PREFERENCE_KEY, false)
        b.zaiVisionSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceZaiFree.VISION_PREFERENCE_KEY, enabled).apply()
        }
        val selectedZai = workspacePrefs.getString(WorkspaceZaiFree.MODEL_PREFERENCE_KEY,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL)
        b.zaiModel45.isChecked = selectedZai == WorkspaceZaiFree.ALT_TEXT_MODEL
        b.zaiModel47.isChecked = selectedZai != WorkspaceZaiFree.ALT_TEXT_MODEL
        b.zaiModelGroup.setOnCheckedChangeListener { _, checked ->
            val model = if (checked == b.zaiModel45.id) WorkspaceZaiFree.ALT_TEXT_MODEL
                else WorkspaceZaiFree.DEFAULT_TEXT_MODEL
            workspacePrefs.edit().putString(WorkspaceZaiFree.MODEL_PREFERENCE_KEY, model).apply()
        }
        b.xKiroWorkSwitch.isChecked = workspacePrefs.getBoolean(
''')
edit(SRC + 'ui/settings/ApiCloudSettingsActivity.kt',
     '            keys.put(ApiKeyStore.XKIRO, b.xKiroKey.text.toString())\n',
     '            keys.put(ApiKeyStore.XKIRO, b.xKiroKey.text.toString())\n            keys.put(ApiKeyStore.ZAI, b.zaiKey.text.toString())\n')

LAYOUT = 'app/src/main/res/layout/activity_api_cloud_settings.xml'
edit(LAYOUT, '    <TextView style="@style/MyraLabel" android:text="DEEPSEEK API KEYS"/>', '''    <TextView style="@style/MyraLabel" android:text="Z.AI DIRECT FREE API KEY (OPTIONAL CHAT)"/>
    <EditText android:id="@+id/zaiKey" style="@style/MyraField" android:hint="Z.ai API key" android:inputType="textPassword"/>
    <CheckBox android:id="@+id/zaiWorkSwitch" android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="48dp" android:text="Use Z.ai Free for Workspace text chat" android:textColor="#B9DEBF"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="OFF by default. The selected chat text and same-chat context go directly to Z.ai, subject to Z.ai's privacy terms. No Gemini Live key, voice, project source or saved personal memory is sent. Only the exact free models below; no paid fallback or silent switch. If Z.ai changes pricing or requires payment, disable this route." android:textColor="#AAA5AA" android:textSize="12sp"/>
    <RadioGroup android:id="@+id/zaiModelGroup" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
        <RadioButton android:id="@+id/zaiModel47" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="GLM-4.7-Flash · free text" android:textColor="#EEEEEE"/>
        <RadioButton android:id="@+id/zaiModel45" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="GLM-4.5-Flash · free text" android:textColor="#EEEEEE"/>
    </RadioGroup>
    <CheckBox android:id="@+id/zaiVisionSwitch" android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="48dp" android:text="Allow GLM-4.6V-Flash for explicitly attached photos" android:textColor="#B9DEBF"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="8dp" android:text="OFF by default. Requires Z.ai text route enabled. Only your chosen JPEG/PNG photo and current same-chat text are sent to Z.ai. No automatic screen capture, fallback, or voice access." android:textColor="#AAA5AA" android:textSize="12sp"/>
    <TextView style="@style/MyraLabel" android:text="DEEPSEEK API KEYS"/>''')

SELECTION = WS + 'WorkspaceFreeProviderSelection.kt'
selection = (ROOT / SELECTION).read_text()
if 'internal object WorkspaceFreeProviderSelection' not in selection or 'zaiApproved' in selection:
    raise RuntimeError('Provider selection changed: stop safely')
(ROOT / SELECTION).write_text('''package com.myra.assistant.ui.workspace

/** Deterministic, consent-gated selection; no implicit cross-provider switch. */
internal object WorkspaceFreeProviderSelection {
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean, zaiApproved: Boolean = false,
               zaiAvailable: Boolean = false, zaiVisionApproved: Boolean = false,
               hasImage: Boolean = false): WorkspaceChatGateway.Provider? = when {
        // Photo needs separate consent. If not granted, preserve existing OpenRouter route.
        hasImage && zaiApproved && zaiAvailable && zaiVisionApproved -> WorkspaceChatGateway.Provider.ZAI_FREE
        hasAttachments -> if (openRouterAvailable) WorkspaceChatGateway.Provider.OPENROUTER_FREE
            else if (zaiApproved && zaiAvailable && !hasImage) WorkspaceChatGateway.Provider.ZAI_FREE else null
        zaiApproved && zaiAvailable -> WorkspaceChatGateway.Provider.ZAI_FREE
        groqFreeZdrApproved && groqAvailable && groqWithinBudget -> WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        else -> null
    }
}
''')

GATEWAY = WS + 'WorkspaceChatGateway.kt'
edit(GATEWAY, 'enum class Provider { OPENROUTER_FREE, GROQ_FREE }',
     'enum class Provider { OPENROUTER_FREE, GROQ_FREE, ZAI_FREE }')
edit(GATEWAY,
     '                image: Image? = null): Request {',
     '                image: Image? = null, zaiModel: String = WorkspaceZaiFree.DEFAULT_TEXT_MODEL,\n                zaiVisionApproved: Boolean = false): Request {')
edit(GATEWAY,
     '        if (provider == Provider.GROQ_FREE) return WorkspaceGroqFree.request(key, messages, image)\n',
     '''        if (provider == Provider.GROQ_FREE) return WorkspaceGroqFree.request(key, messages, image)
        if (provider == Provider.ZAI_FREE) return WorkspaceZaiFree.request(key, messages, image,
            zaiModel, zaiVisionApproved)
''')
edit(GATEWAY, '        Provider.GROQ_FREE -> WorkspaceGroqFree.read(response)\n',
     '        Provider.GROQ_FREE -> WorkspaceGroqFree.read(response)\n        Provider.ZAI_FREE -> WorkspaceZaiFree.read(response)\n')

ACTIVITY = WS + 'WorkspaceActivity.kt'
edit(ACTIVITY, 'private fun selectedProvider(hasAttachments: Boolean = false): WorkspaceChatGateway.Provider? {',
     'private fun selectedProvider(hasAttachments: Boolean = false, hasImage: Boolean = false): WorkspaceChatGateway.Provider? {')
edit(ACTIVITY,
     '        return WorkspaceFreeProviderSelection.choose(openRouterAvailable, groqAvailable,\n            groqApproved, groqFits, hasAttachments)\n',
     '''        val zaiApproved = preferences.getBoolean(WorkspaceZaiFree.PREFERENCE_KEY, false)
        val zaiAvailable = zaiApproved && keys.get(ApiKeyStore.ZAI).isNotBlank()
        return WorkspaceFreeProviderSelection.choose(openRouterAvailable, groqAvailable,
            groqApproved, groqFits, hasAttachments, zaiApproved, zaiAvailable,
            preferences.getBoolean(WorkspaceZaiFree.VISION_PREFERENCE_KEY, false), hasImage)
''')
edit(ACTIVITY, '        WorkspaceChatGateway.Provider.GROQ_FREE -> keys.get(ApiKeyStore.GROQ)\n',
     '        WorkspaceChatGateway.Provider.GROQ_FREE -> keys.get(ApiKeyStore.GROQ)\n        WorkspaceChatGateway.Provider.ZAI_FREE -> keys.get(ApiKeyStore.ZAI)\n')
edit(ACTIVITY,
     '            keys.get(ApiKeyStore.GROQ).isNotBlank()) {\n            statusMessage = "Groq Free is text-only.',
     '''            keys.get(ApiKeyStore.GROQ).isNotBlank() &&
            !(preferences.getBoolean(WorkspaceZaiFree.PREFERENCE_KEY, false) &&
              keys.get(ApiKeyStore.ZAI).isNotBlank() &&
              (!picked.any { it.mime.startsWith("image/") } ||
               preferences.getBoolean(WorkspaceZaiFree.VISION_PREFERENCE_KEY, false)))) {
            statusMessage = "Groq Free is text-only.''')
edit(ACTIVITY, 'val provider = runCatching { selectedProvider(picked.isNotEmpty()) }',
     'val provider = runCatching { selectedProvider(picked.isNotEmpty(),\n            picked.any { it.mime.startsWith("image/") }) }')
edit(ACTIVITY,
     'Save a valid OpenRouter Free key or enable Groq Free/ZDR with a valid key. No paid fallback.',
     'Save a valid OpenRouter Free key, Groq Free/ZDR key, or enable Z.ai Free with its own key. No paid fallback.')
edit(ACTIVITY,
     'val outgoing = runCatching { WorkspaceChatGateway.request(provider, keyFor(provider), enriched, image) }',
     '''val outgoing = runCatching {
            WorkspaceChatGateway.request(provider, keyFor(provider), enriched, image,
                WorkspaceZaiFree.textModel(preferences.getString(WorkspaceZaiFree.MODEL_PREFERENCE_KEY,
                    WorkspaceZaiFree.DEFAULT_TEXT_MODEL)),
                preferences.getBoolean(WorkspaceZaiFree.VISION_PREFERENCE_KEY, false))
        }''')
edit(ACTIVITY, 'val call = WorkspaceChatGateway.client.newCall(outgoing)',
     'val call = (if (provider == WorkspaceChatGateway.Provider.ZAI_FREE)\n            WorkspaceZaiFree.client else WorkspaceChatGateway.client).newCall(outgoing)')

TEST = 'app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceChatGatewayTest.kt'
edit(TEST,
     'WorkspaceChatGateway.Provider.GROQ_FREE),\n            WorkspaceChatGateway.Provider.values().toList())',
     'WorkspaceChatGateway.Provider.GROQ_FREE,\n            WorkspaceChatGateway.Provider.ZAI_FREE),\n            WorkspaceChatGateway.Provider.values().toList())')

add('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceZaiFreeTest.kt', '''package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceZaiFreeTest {
    private val message = WorkspaceConversationStore.Message("one", "user", "Hindi mein jawab do", 1L)
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    @Test fun exactAllowlistAndSeparateVoiceKey() {
        assertEquals("glm-4.7-flash", WorkspaceZaiFree.textModel(null))
        assertEquals("glm-4.5-flash", WorkspaceZaiFree.textModel("glm-4.5-flash"))
        assertTrue(runCatching { WorkspaceZaiFree.textModel("glm-4.7-flashx") }.isFailure)
        val request = WorkspaceZaiFree.request("zai-only-key", listOf(message), null,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, false)
        val data = body(request)
        assertEquals("api.z.ai", request.url.host)
        assertEquals("glm-4.7-flash", data.getString("model"))
        assertFalse(data.has("provider"))
        assertFalse(data.has("plugins"))
        assertFalse(data.toString().contains("zai-only-key"))
        assertEquals("Bearer zai-only-key", request.header("Authorization"))
        assertFalse(request.url.toString().contains("zai-only-key"))
    }

    @Test fun photoRequiresSeparateConsentAndExactFreeVisionModel() {
        val photo = WorkspaceChatGateway.Image("image/png", "cG5n")
        assertTrue(runCatching { WorkspaceZaiFree.request("zai-only-key", listOf(message), photo,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, false) }.isFailure)
        val request = WorkspaceZaiFree.request("zai-only-key", listOf(message), photo,
            WorkspaceZaiFree.DEFAULT_TEXT_MODEL, true)
        assertEquals("glm-4.6v-flash", body(request).getString("model"))
        assertTrue(body(request).toString().contains("data:image/png;base64,cG5n"))
    }

    @Test fun rateLimitAndIncompleteReplyFailWithoutEchoingSecrets() {
        val request = Request.Builder().url(WorkspaceZaiFree.ENDPOINT).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(429).message("limit").body("secret-key echoed".toResponseBody()).build()
        val problem = runCatching { WorkspaceZaiFree.read(response) }.exceptionOrNull()
        assertNotNull(problem)
        assertFalse(problem!!.message.orEmpty().contains("secret-key"))
        assertTrue(problem.message.orEmpty().contains("429"))
        val partial = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("ok").body("{\\"choices\\":[{\\"finish_reason\\":\\"length\\",\\"message\\":{\\"content\\":\\"partial\\"}}]}".toResponseBody()).build()
        assertTrue(runCatching { WorkspaceZaiFree.read(partial) }.isFailure)
    }

    @Test fun selectionRequiresOptInAndImageConsent() {
        assertNull(WorkspaceFreeProviderSelection.choose(false, false, false, false, false,
            zaiApproved = false, zaiAvailable = true))
        assertEquals(WorkspaceChatGateway.Provider.ZAI_FREE,
            WorkspaceFreeProviderSelection.choose(false, false, false, false, false,
                zaiApproved = true, zaiAvailable = true))
        assertNull(WorkspaceFreeProviderSelection.choose(false, false, false, false, true,
            zaiApproved = true, zaiAvailable = true, hasImage = true))
        assertEquals(WorkspaceChatGateway.Provider.ZAI_FREE,
            WorkspaceFreeProviderSelection.choose(false, false, false, false, true,
                zaiApproved = true, zaiAvailable = true, zaiVisionApproved = true, hasImage = true))
    }
}
''')

print('Z.ai free chat + explicitly attached photo integration staged (no Gemini, memory, or coding changes).')
