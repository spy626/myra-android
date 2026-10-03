#!/usr/bin/env python3
"""Narrow, assertion-anchored one-time patch for website provider selection."""
from pathlib import Path

root = Path('app/src/main/java/com/myra/assistant/ui/workspace')
tests = Path('app/src/test/java/com/myra/assistant/ui/workspace')
flow_path = root / 'WorkspaceChatCodingFlow.kt'
flow = flow_path.read_text()

def once(source, old, new, label):
    count = source.count(old)
    assert count == 1, f'{label}: expected exactly one anchor, got {count}'
    return source.replace(old, new, 1)

start = flow.index('    private fun continueWebsite(id: String, instruction: String) {')
end = flow.index('    private fun fallbackWebsiteOnRejected(', start)
website = flow[start:end]
website = once(website, '''        val key = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure OpenRouter key unavailable; nothing was shared."); return }
        if (key.isBlank()) {
            error("Website request saved locally. Add an OpenRouter Free key in API & Cloud Settings.")
            return
        }
''', '''        val openRouterKey = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure provider keys unavailable; no source was shared."); return }
        val groqKey = runCatching { keys.get(ApiKeyStore.GROQ) }
            .getOrElse { error("Secure provider keys unavailable; no source was shared."); return }
        val groqFreeEnabled = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        val primary = WorkspaceWebsiteRoute.choose(openRouterKey, groqKey, groqFreeEnabled)
        if (primary == null) {
            error("Website request saved locally. Save a valid OpenRouter Free key, or enable " +
                "Groq Free/ZDR with a valid Groq Free key in API & Cloud Settings. No source was sent.")
            return
        }
''', 'website key selection')
website = once(website, '''        val outgoing = runCatching { WorkspaceWebsiteGeneration.request(key, snapshot) }
            .getOrElse { error("Free website request refused: ${it.message}"); return }
        val serial = ++generation
        val call = WorkspaceWebsiteGeneration.client.newCall(outgoing)
''', '''        val outgoing = runCatching {
            when (primary) {
                WorkspaceWebsiteRoute.Provider.OPENROUTER ->
                    WorkspaceWebsiteGeneration.request(openRouterKey, snapshot)
                WorkspaceWebsiteRoute.Provider.GROQ ->
                    WorkspaceWebsiteGroqFallback.request(groqKey, snapshot)
            }
        }.getOrElse { error("Free website request refused: ${it.message}"); return }
        val serial = ++generation
        val client = if (primary == WorkspaceWebsiteRoute.Provider.GROQ)
            WorkspaceWebsiteGroqFallback.client else WorkspaceWebsiteGeneration.client
        val call = client.newCall(outgoing)
''', 'website client selection')
website = once(website, '''                if (WorkspaceWebsiteGroqFallback.routeRejected(rejectedStatus)) {''', '''                if (primary == WorkspaceWebsiteRoute.Provider.OPENROUTER &&
                    WorkspaceWebsiteGroqFallback.routeRejected(rejectedStatus)) {''', 'only OpenRouter-to-Groq fallback')
flow = flow[:start] + website + flow[end:]
flow_path.write_text(flow)

policy_path = root / 'WorkspaceWebsiteRoute.kt'
assert not policy_path.exists(), 'route policy already exists; never overwrite'
policy_path.write_text('''package com.myra.assistant.ui.workspace

/** Only existing free Workspace providers. One key is enough to start a website task.
 * Cross-company source forwarding still uses the existing saved website opt-in.
 */
internal object WorkspaceWebsiteRoute {
    enum class Provider { OPENROUTER, GROQ }

    private fun valid(key: String): Boolean =
        key.length in 1..256 && key.none(Char::isWhitespace)

    fun choose(openRouterKey: String, groqKey: String, groqFreeEnabled: Boolean): Provider? = when {
        valid(openRouterKey) -> Provider.OPENROUTER
        groqFreeEnabled && valid(groqKey) -> Provider.GROQ
        else -> null
    }
}
''')

# OpenRouter/free with require_parameters=true can reject all available free endpoints
# merely because they do not advertise JSON mode. Do not weaken the ZDR, data-collection,
# $0, no-paid-fallback or downstream strict three-file parsing requirements.
gen_path = root / 'WorkspaceWebsiteGeneration.kt'
gen = gen_path.read_text()
gen = once(gen, '''                .put("allow_fallbacks", false).put("require_parameters", true)
''', '''                .put("allow_fallbacks", false)
''', 'overconstrained OpenRouter/free parameter routing')
gen_path.write_text(gen)

website_test = tests / 'WorkspaceWebsiteGenerationTest.kt'
t = website_test.read_text()
t = once(t, '''        assertTrue(body.getJSONObject("provider").getBoolean("require_parameters"))
''', '''        assertFalse(body.getJSONObject("provider").optBoolean("require_parameters", false))
''', 'website free request test')
website_test.write_text(t)

route_test = tests / 'WorkspaceWebsiteRouteTest.kt'
assert not route_test.exists(), 'route test already exists; never overwrite'
route_test.write_text('''package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteRouteTest {
    @Test fun groqOnlyWebsiteUsesSavedGroqFreeKeyWithoutOpenRouter() {
        assertEquals(WorkspaceWebsiteRoute.Provider.GROQ,
            WorkspaceWebsiteRoute.choose("", "gsk_test_key", true))
    }

    @Test fun openRouterOnlyWebsiteUsesOpenRouterWithoutGroq() {
        assertEquals(WorkspaceWebsiteRoute.Provider.OPENROUTER,
            WorkspaceWebsiteRoute.choose("sk-or-test", "", false))
    }

    @Test fun twoKeysKeepOpenRouterPrimaryAndAllowExistingConsentedGroqFallback() {
        assertEquals(WorkspaceWebsiteRoute.Provider.OPENROUTER,
            WorkspaceWebsiteRoute.choose("sk-or-test", "gsk_test_key", true))
        assertTrue(WorkspaceWebsiteGroqFallback.eligible(404, true, true, "gsk_test_key"))
        assertTrue(WorkspaceWebsiteGroqFallback.eligible(429, true, true, "gsk_test_key"))
        assertFalse(WorkspaceWebsiteGroqFallback.eligible(404, false, true, "gsk_test_key"))
    }

    @Test fun savedGroqKeyAloneDoesNotAssumeFreeTierOrZdrAndInvalidKeysAreNotUsed() {
        assertNull(WorkspaceWebsiteRoute.choose("", "gsk_test_key", false))
        assertNull(WorkspaceWebsiteRoute.choose("bad key", "", true))
        assertEquals(WorkspaceWebsiteRoute.Provider.GROQ,
            WorkspaceWebsiteRoute.choose("invalid key", "gsk_test_key", true))
    }

    @Test fun groqOnlyRequestUsesStrictFreeWebsiteJsonWithoutOpenRouterAuthorization() {
        val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", "Build Minicoy", mapOf(
            "index.html" to null, "style.css" to null, "script.js" to null))
        val request = WorkspaceWebsiteGroqFallback.request("gsk_test_key", snapshot)
        assertEquals(WorkspaceGroqFree.ENDPOINT, request.url.toString())
        assertEquals("Bearer gsk_test_key", request.header("Authorization"))
        assertTrue(WorkspaceWebsiteGroqFallback.client.interceptors.none { it is WorkspaceFreeRouteRetry })
    }
}
''')

for path in [flow_path, gen_path, policy_path, website_test, route_test]:
    print('scoped:', path)
