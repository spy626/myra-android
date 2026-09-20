#!/usr/bin/env python3
"""Exact-anchor one-time phone-failure fix; existing Kotlin flow stays sole route owner."""
from pathlib import Path

root = Path('app/src/main/java/com/myra/assistant/ui/workspace')
flow = root / 'WorkspaceChatCodingFlow.kt'
fallback = root / 'WorkspaceWebsiteGroqFallback.kt'
test = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGroqFallbackTest.kt')

def replace_once(path, old, new):
    text = path.read_text(encoding='utf-8')
    n = text.count(old)
    assert n == 1, f'Exact anchor drift in {path}: count={n}\n{old[:100]}'
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

replace_once(fallback,
'''    private const val MAX_COMPLETION_TOKENS = 4_500
''',
'''    private const val MAX_COMPLETION_TOKENS = 4_500
    // A single website turn can issue at most three requests total (not three per route).
    const val MAX_WEBSITE_ATTEMPTS = 3

    fun canAttempt(issued: Int): Boolean = issued in 1 until MAX_WEBSITE_ATTEMPTS

    /** Third attempt: only after an actual Groq schema HTTP 400, never on timeout,
     * 429, invalid output, a finished compatible request, or a fourth call.
     */
    fun recoverAfterFallbackGroq(code: Int, issued: Int): Boolean =
        code == 400 && canAttempt(issued)
''')

replace_once(flow,
'''    private var request: Call? = null
    private var activeTurn: Pair<String, String>? = null
''',
'''    private var request: Call? = null
    // Counts actual provider calls, including retries; reset only at terminal/cancel.
    private var websiteAttempts = 0
    private var activeTurn: Pair<String, String>? = null
''')
replace_once(flow,
'''        request?.cancel()
        request = null
        activeTurn = null
''',
'''        request?.cancel()
        request = null
        websiteAttempts = 0
        activeTurn = null
''')
replace_once(flow,
'''        val call = client.newCall(outgoing)
        request = call
        report("Building index.html, style.css and script.js in this project · Stop ■ to cancel.")
''',
'''        val call = client.newCall(outgoing)
        websiteAttempts = 1
        request = call
        report("Building website · free attempt 1/3 · Stop ■ to cancel.")
''')
replace_once(flow,
'''    /** One same-provider compatibility attempt after a definitive Groq-only HTTP 400.
     * The second response is terminal: never loop, change provider, or replay on timeout.
     */
''',
'''    /** One same-provider JSON Object Mode compatibility attempt after a definitive
     * Groq schema HTTP 400, whether Groq was primary or the consented fallback.
     * It is terminal: never replay a timeout, invalid answer, or this third response.
     */
''')
replace_once(flow,
'''            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== first || !current(id)) return@runOnUiThread
            val alternate = runCatching {
''',
'''            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== first || !current(id)) return@runOnUiThread
            if (!WorkspaceWebsiteGroqFallback.canAttempt(websiteAttempts)) {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("All eligible free website attempts failed; no files changed.")))
                return@runOnUiThread
            }
            val alternate = runCatching {
''')
replace_once(flow,
'''            val second = WorkspaceWebsiteGroqFallback.client.newCall(alternate)
            request = second
            report("Trying a compatible Groq Free website format · Stop ■ to cancel.")
''',
'''            val second = WorkspaceWebsiteGroqFallback.client.newCall(alternate)
            websiteAttempts++
            request = second
            report("Trying compatible Groq Free format · attempt $websiteAttempts/3 · Stop ■ to cancel.")
''')
replace_once(flow,
'''            val preferences = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
''',
'''            if (!WorkspaceWebsiteGroqFallback.canAttempt(websiteAttempts)) {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("All eligible free website attempts failed; no files changed.")))
                return@runOnUiThread
            }
            val preferences = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
''')
replace_once(flow,
'''            val second = WorkspaceWebsiteGroqFallback.client.newCall(secondRequest)
            request = second
            report("Primary free route unavailable; trying Groq Free for this website · Stop ■ to cancel.")
''',
'''            val second = WorkspaceWebsiteGroqFallback.client.newCall(secondRequest)
            websiteAttempts++
            request = second
            report("Switching to Groq Free · attempt $websiteAttempts/3 · Stop ■ to cancel.")
''')
replace_once(flow,
'''                override fun onResponse(call: Call, response: Response) = completeWebsite(
                    call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            })
        }
    }

    private fun completeWebsite(call: Call, serial: Long, id: String,
''',
'''                override fun onResponse(call: Call, response: Response) {
                    // Phone failure: Groq was the SECOND provider, so the primary-Groq
                    // branch never ran. One definite 400 permits the THIRD request,
                    // with the exact same approved snapshot in JSON Object Mode.
                    if (WorkspaceWebsiteGroqFallback.recoverAfterFallbackGroq(
                            response.code, websiteAttempts)) {
                        response.close()
                        recoverGroqWebsiteFormat(call, serial, id, snapshot, key)
                        return
                    }
                    completeWebsite(call, serial, id, snapshot,
                        runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
                }
            })
        }
    }

    private fun completeWebsite(call: Call, serial: Long, id: String,
''')
replace_once(flow,
'''            request = null
            result.onSuccess { generated ->
''',
'''            request = null
            websiteAttempts = 0
            result.onSuccess { generated ->
''')

replace_once(test,
'''    @Test fun resendsOnlyApprovedWebsiteSnapshotWithGroqJsonModeAndNoPaidRouting() {
''',
'''    @Test fun exactlyThreeTotalAttemptsAndOnlyDefinitiveFallbackGroq400Recovers() {
        assertEquals(3, WorkspaceWebsiteGroqFallback.MAX_WEBSITE_ATTEMPTS)
        assertFalse(WorkspaceWebsiteGroqFallback.canAttempt(0))
        assertTrue(WorkspaceWebsiteGroqFallback.canAttempt(1))
        assertTrue(WorkspaceWebsiteGroqFallback.canAttempt(2))
        assertFalse(WorkspaceWebsiteGroqFallback.canAttempt(3))
        assertFalse(WorkspaceWebsiteGroqFallback.canAttempt(4))
        assertTrue(WorkspaceWebsiteGroqFallback.recoverAfterFallbackGroq(400, 2))
        assertFalse(WorkspaceWebsiteGroqFallback.recoverAfterFallbackGroq(400, 3))
        for (code in listOf(200, 401, 402, 403, 408, 429, 500, 502, 503, 504)) {
            assertFalse(WorkspaceWebsiteGroqFallback.recoverAfterFallbackGroq(code, 2))
        }
        // Groq-primary 400 may use attempt 2; a fallback Groq 400 may use 3.
        assertTrue(WorkspaceWebsiteGroqFallback.compatibilityEligible(
            WorkspaceWebsiteRoute.Provider.GROQ, 400))
    }

    @Test fun resendsOnlyApprovedWebsiteSnapshotWithGroqJsonModeAndNoPaidRouting() {
''')

assert 'recoverAfterFallbackGroq(\n                            response.code, websiteAttempts)' in flow.read_text()
assert 'websiteAttempts++' in flow.read_text()
print('Prepared bounded 1 OpenRouter + 1 Groq schema + 1 Groq JSON Object Mode path, with 3-attempt cap and policy tests')
