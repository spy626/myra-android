from pathlib import Path
B=Path('app/src/main/java/com/myra/assistant')
X=B/'ui/workspace/WorkspaceXKiroFree.kt'
S=B/'ui/settings/ApiCloudSettingsActivity.kt'
L=Path('app/src/main/res/layout/activity_api_cloud_settings.xml')
F=B/'ui/workspace/WorkspaceChatCodingFlow.kt'
def r(path,old,new):
 t=path.read_text()
 assert t.count(old)==1,(str(path),t.count(old),old[:60])
 path.write_text(t.replace(old,new))

# Keep the only existing Work owner, unchanged task/source, and no per-request dialog.
r(S,'import com.myra.assistant.ui.workspace.WorkspaceFreeCrossProvider\n','import com.myra.assistant.ui.workspace.WorkspaceFreeCrossProvider\nimport com.myra.assistant.ui.workspace.WorkspaceCodingAutoFallback\n')
r(S,'        b.workspaceMemorySwitch.isChecked = workspacePrefs.getBoolean(\n','''        b.xKiroFallbackSwitch.isChecked = workspacePrefs.getBoolean(
            WorkspaceCodingAutoFallback.PREFERENCE_KEY, false)
        b.xKiroFallbackSwitch.setOnCheckedChangeListener { _, enabled ->
            workspacePrefs.edit().putBoolean(WorkspaceCodingAutoFallback.PREFERENCE_KEY, enabled).apply()
        }
        b.workspaceMemorySwitch.isChecked = workspacePrefs.getBoolean(
''')
r(L,'    <CheckBox android:id="@+id/groqFreeZdrSwitch"','''    <CheckBox android:id="@+id/xKiroFallbackSwitch" android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="48dp" android:buttonTint="#B9DEBF" android:text="Automatically switch xKiro Work to available Free providers" android:textColor="#B9DEBF" android:textSize="14sp"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="9dp" android:text="OFF by default. One-time permission: on xKiro free preflight failure or final HTTP 429/502/503/504, send the SAME selected Work goal and approved source to OpenRouter Free, then Groq Free if OpenRouter definitively rejects. Groq requires its separate Free-account and inference-ZDR confirmation; LYRA cannot enforce a Groq account $0 cap. No per-request popup, voice, attachments, unrelated files, memory, paid fallback, timeout/partial-output resend. If all free routes fail, existing files remain unchanged." android:textColor="#AAA5AA" android:textSize="12sp"/>
    <CheckBox android:id="@+id/groqFreeZdrSwitch"''')
r(L,'No voice, attachments, unrelated files, saved LYRA memory or automatic xKiro cross-provider resend. Switching providers','No voice, attachments, unrelated files or saved LYRA memory. Automatic cross-provider sends require the separate Free fallback switch. Switching providers')
P=B/'ui/workspace/WorkspaceCodingAutoFallback.kt'
assert not P.exists()
P.write_text('''package com.myra.assistant.ui.workspace

/** Pure opt-in/status policy. Existing Work and file owners remain authoritative. */
internal object WorkspaceCodingAutoFallback {
    const val PREFERENCE_KEY = "workspace_xkiro_free_coding_fallback_opt_in"
    fun xKiroRejected(code: Int) = code in setOf(429, 502, 503, 504)
    fun openRouterRejected(code: Int) = code in setOf(404, 429, 502, 503, 504)
    fun validKey(key: String) = key.length in 1..256 && key.none(Char::isWhitespace) && ',' !in key
    fun permitted(optIn: Boolean, xKiroEnabled: Boolean) = optIn && xKiroEnabled
    fun groqPermitted(optIn: Boolean, groqFreeZdr: Boolean, key: String) =
        optIn && groqFreeZdr && validKey(key)
}
''')

r(X,'import okhttp3.Response\n','''import okhttp3.Response
import android.content.Context
import com.myra.assistant.MyApplication
import com.myra.assistant.ai.ApiKeyStore
''')
r(X,'    private const val MAX_CHECK_BYTES = 500_000L\n','''    private const val MAX_CHECK_BYTES = 500_000L
    /** No inference POST was sent; cross-provider attempt is safe only with saved opt-in. */
    internal class NoSourcePreflight(message: String): IOException(message)
''')
r(X,'''                if (!catalogIsFree(checkedGet(MODELS)))
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
''','''                if (!catalogIsFree(checkedGet(MODELS)))
                    throw NoSourcePreflight("xKiro Free preflight: exact model/zero price unverified; no source sent")
                if (!quotaIsFree(checkedGet(USAGE, key)))
                    throw NoSourcePreflight("xKiro Free preflight: remaining free quota unverified; no source sent")
            } catch (failure: IOException) {
                if (failure is NoSourcePreflight) throw failure
                throw NoSourcePreflight("xKiro Free preflight unavailable; no source sent")
            } catch (failure: Exception) {
                throw NoSourcePreflight("xKiro Free preflight invalid; no source sent")
            }
            if (chain.call().isCanceled()) throw IOException("xKiro Free request cancelled; no source sent")
            val response = chain.proceed(request) // Network failure/timeout propagates; NEVER replay.
            if (!WorkspaceCodingAutoFallback.xKiroRejected(response.code)) return response
            val code = response.code
            response.close() // Definitive rejected HTTP, not a completed/partial output.
            return consentedFallback(chain, request, "xKiro Free HTTP $code")
''')
# Wrap only preflight; no catch around chain.proceed. Typed failures are caught at the call site.
r(X,'''            try {
                if (!catalogIsFree(checkedGet(MODELS)))
''','''            val preflightFailure = try {
                if (!catalogIsFree(checkedGet(MODELS)))
''')
r(X,'''            } catch (failure: IOException) {
                if (failure is NoSourcePreflight) throw failure
                throw NoSourcePreflight("xKiro Free preflight unavailable; no source sent")
            } catch (failure: Exception) {
                throw NoSourcePreflight("xKiro Free preflight invalid; no source sent")
            }
            if (chain.call().isCanceled())''','''                null
            } catch (failure: IOException) {
                if (failure is NoSourcePreflight) failure
                else NoSourcePreflight("xKiro Free preflight unavailable; no source sent")
            } catch (failure: Exception) {
                NoSourcePreflight("xKiro Free preflight invalid; no source sent")
            }
            if (preflightFailure != null) return consentedFallback(chain, request,
                preflightFailure.message.orEmpty(), preflightFailure)
            if (chain.call().isCanceled())''')
# The helper runs inside the original application's existing xKiro interceptor, not a new router.
r(X,'    /** New independent adapter; no generic retry interceptor and no hidden paid routes. */','''    /** A single user-selected source can cross companies after a definite rejection or
     * before xKiro sent ANY source. No uncertain resend or nested automatic retry chain.
     */
    private fun consentedFallback(chain: Interceptor.Chain, original: Request, reason: String,
                                  preflight: NoSourcePreflight? = null): Response {
        if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
        val context = MyApplication.contextOrNull()
        val prefs = context?.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        val approved = prefs?.let {
            WorkspaceCodingAutoFallback.permitted(
                it.getBoolean(WorkspaceCodingAutoFallback.PREFERENCE_KEY, false),
                it.getBoolean(PREFERENCE_KEY, false))
        } == true
        if (!approved) throw preflight ?: IOException("$reason; automatic Free fallback OFF. No files changed")
        val keys = ApiKeyStore(requireNotNull(context))
        val openKey = runCatching { keys.get(ApiKeyStore.OPENROUTER) }.getOrDefault("")
        val groqKey = runCatching { keys.get(ApiKeyStore.GROQ) }.getOrDefault("")
        val groqAllowed = WorkspaceCodingAutoFallback.groqPermitted(approved,
            prefs!!.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false), groqKey)
        val snapshot = payloadSnapshot(original)
        fun requestForGroq(): Request? = if (!groqAllowed) null else runCatching {
            if (snapshot != null) WorkspaceWebsiteGroqFallback.request(groqKey, snapshot)
            else WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.GROQ_FREE, groqKey,
                listOf(WorkspaceConversationStore.Message("approved-work-source", "user",
                    oneFilePrompt(original), System.currentTimeMillis())))
        }.getOrNull()
        val open = if (WorkspaceCodingAutoFallback.validKey(openKey)) runCatching {
            if (snapshot != null) WorkspaceWebsiteGeneration.request(openKey, snapshot)
            else WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE, openKey,
                listOf(WorkspaceConversationStore.Message("approved-work-source", "user",
                    oneFilePrompt(original), System.currentTimeMillis())))
        }.getOrNull() else null
        if (open != null) {
            if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
            val second = chain.proceed(open) // Any network ambiguity ends the chain.
            if (!WorkspaceCodingAutoFallback.openRouterRejected(second.code)) return second
            second.close()
        }
        val groq = requestForGroq() ?: throw IOException(
            "$reason; no eligible Groq Free/ZDR route remains; files unchanged")
        if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
        return chain.proceed(groq) // One Groq attempt; no paid or recursive fallback.
    }

    private fun payloadSnapshot(request: Request): WorkspaceWebsiteGeneration.Snapshot? {
        val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        if (body.optInt("max_tokens") != WEBSITE_OUTPUT_TOKENS) return null
        val text = body.getJSONArray("messages").getJSONObject(1).getString("content")
        val context = JSONObject(text)
        val sources = context.getJSONObject("existingFiles")
        val original = WorkspaceWebsiteGeneration.PATHS.associateWith { path ->
            if (sources.isNull(path)) null else sources.getString(path)
        }
        return WorkspaceWebsiteGeneration.Snapshot("", "", "", context.getString("goal"), original)
    }

    private fun oneFilePrompt(request: Request): String {
        val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
        val messages = JSONObject(buffer.readUtf8()).getJSONArray("messages")
        return messages.getJSONObject(messages.length() - 1).getString("content")
    }

    /** New independent adapter; no generic retry interceptor and no hidden paid routes. */''')
r(X,'''        .callTimeout(115, TimeUnit.SECONDS)
''','''        .callTimeout(240, TimeUnit.SECONDS) // Total bound includes up to two definite free fallbacks.
''')
r(X,'''    fun readWebsite(response: Response): Map<String, String> = response.use {
        verifiedResponse(it)
        WorkspaceWebsiteGeneration.readResponse(it)
    }
''','''    fun readWebsite(response: Response): Map<String, String> = response.use {
        if (it.request.url.toString() == ENDPOINT) verifiedResponse(it)
        WorkspaceWebsiteGeneration.readResponse(it)
    }
''')
r(X,'''    fun readEdit(response: Response): String = response.use {
        val outer = verifiedResponse(it)
''','''    fun readEdit(response: Response): String = response.use {
        if (it.request.url.toString() == WorkspaceFreeAiSuggestion.ENDPOINT)
            return@use WorkspaceFreeAiSuggestion.readResponse(it)
        if (it.request.url.toString() == WorkspaceGroqFree.ENDPOINT)
            return@use WorkspaceGroqFree.read(it)
        val outer = verifiedResponse(it)
''')
# After request finish, the visible Chat result identifies the actual provider.
r(F,'''                completeWebsite(call, serial, id, snapshot,
                    runCatching { if (primary == WorkspaceWebsiteRoute.Provider.XKIRO)
                        WorkspaceXKiroFree.readWebsite(response)
                    else WorkspaceWebsiteGeneration.readResponse(response) })
''','''                val via = if (primary == WorkspaceWebsiteRoute.Provider.XKIRO)
                    WorkspaceCodingAutoFallback.displayName(response.request.url.toString()) else null
                completeWebsite(call, serial, id, snapshot,
                    runCatching { if (primary == WorkspaceWebsiteRoute.Provider.XKIRO)
                        WorkspaceXKiroFree.readWebsite(response)
                    else WorkspaceWebsiteGeneration.readResponse(response) }, via)
''')
r(F,'''                                result: Result<Map<String, String>>) {
''','''                                result: Result<Map<String, String>>,
                                via: String? = null) {
''')
r(F,'''                    val summary = WorkspaceCodingResult.websiteSuccess(snapshot.original, review.files) +
                        review.chatNote()
''','''                    val summary = WorkspaceCodingResult.websiteSuccess(snapshot.original, review.files) +
                        review.chatNote() + (via?.let { " Completed via $it after xKiro was unavailable." } ?: "")
''')
r(F,'''            override fun onResponse(call: Call, response: Response) = complete(call, serial, id,
                prepared, runCatching { if (usingXKiro) WorkspaceXKiroFree.readEdit(response)
                    else WorkspaceChatGateway.read(provider, response) })
''','''            override fun onResponse(call: Call, response: Response) = complete(call, serial, id,
                prepared, runCatching { if (usingXKiro) WorkspaceXKiroFree.readEdit(response)
                    else WorkspaceChatGateway.read(provider, response) },
                if (usingXKiro) WorkspaceCodingAutoFallback.displayName(response.request.url.toString()) else null)
''')
r(F,'''                         result: Result<String>) {
''','''                         result: Result<String>, via: String? = null) {
''')
r(F,'''                    terminal("Updated ${prepared.context.path} in your existing project. " +
                        "Review the file and use Undo / Keep in Chat. Preview/build is not verified.")
''','''                    terminal("Updated ${prepared.context.path} in your existing project. " +
                        "Review the file and use Undo / Keep in Chat. Preview/build is not verified." +
                        (via?.let { " Completed via $it after xKiro was unavailable." } ?: ""))
''')
r(P,'''    fun validKey(key: String)''','''    fun displayName(endpoint: String): String? = when (endpoint) {
        WorkspaceFreeAiSuggestion.ENDPOINT -> "OpenRouter Free"
        WorkspaceGroqFree.ENDPOINT -> "Groq Free"
        else -> null
    }
    fun validKey(key: String)''')
T=Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceCodingAutoFallbackTest.kt')
assert not T.exists()
T.write_text('''package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCodingAutoFallbackTest {
    private val p = WorkspaceCodingAutoFallback
    @Test fun optInAndGroqFreeZdrAreIndependent() {
        assertFalse(p.permitted(false, true))
        assertFalse(p.permitted(true, false))
        assertTrue(p.permitted(true, true))
        assertFalse(p.groqPermitted(true, false, "gsk-test"))
        assertFalse(p.groqPermitted(false, true, "gsk-test"))
        assertTrue(p.groqPermitted(true, true, "gsk-test"))
        assertFalse(p.validKey("key,another"))
    }
    @Test fun onlyDefinitiveUnavailableHttpStatusesQualify() {
        for (s in listOf(429, 502, 503, 504)) {
            assertTrue(p.xKiroRejected(s)); assertTrue(p.openRouterRejected(s))
        }
        assertTrue(p.openRouterRejected(404))
        for (s in listOf(200, 400, 401, 402, 403, 413)) {
            assertFalse(p.xKiroRejected(s)); assertFalse(p.openRouterRejected(s))
        }
    }
    @Test fun displayRealFallbackOnly() {
        assertEquals("OpenRouter Free", p.displayName(WorkspaceFreeAiSuggestion.ENDPOINT))
        assertEquals("Groq Free", p.displayName(WorkspaceGroqFree.ENDPOINT))
        assertNull(p.displayName(WorkspaceXKiroFree.ENDPOINT))
    }
}
''')
D=Path('docs/WORKSPACE_XKIRO_AUTOMATIC_FREE_FALLBACK_2026-09-21.md')
assert not D.exists()
D.write_text('''# Optional xKiro → OpenRouter Free → Groq Free Work failover

Work coding only, current project source only, one-time Advanced consent OFF by default. Exact xKiro model/zero-price/quota preflight remains before any xKiro POST. If preflight cannot verify free usage then NO source went to xKiro; or a definite HTTP 429/502/503/504 permits OpenRouter Free (hard zero price, ZDR/no collection, fallback disabled). Final OpenRouter 404/429/502/503/504 may go to Groq only if user confirms their Groq account is Free with inference ZDR. Groq has no API-side hard $0 ceiling; upgrading the account requires turning this OFF. Missing keys, auth/billing errors, partial/invalid replies, cancellations, and uncertain timeouts do not trigger source replay. All failures preserve existing files; successful output still uses existing validated file owner and Undo/Keep. No voice, memory, image, paid fallback, or permanent-completion guarantee. Phone and live account tests are pending; CI does not prove them. Preserve main, Build #2290 recovery and the isolated xKiro Test package.
''')
print('STAGED Fallback changes OK')
