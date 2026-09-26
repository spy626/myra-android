#!/usr/bin/env python3
"""One scoped website free-route recovery patch; fail closed on unexpected source."""
from pathlib import Path

BASE = Path('app/src/main')

def edit(relative, old, new):
    path = BASE / relative
    text = path.read_text(encoding='utf-8')
    occurrences = text.count(old)
    if occurrences != 1:
        raise SystemExit(f'{path}: expected one anchor, got {occurrences}: {old[:95]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

flow = 'java/com/myra/assistant/ui/workspace/WorkspaceChatCodingFlow.kt'
fallback = 'java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGroqFallback.kt'
activity = 'java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt'

edit(fallback,
    '    fun eligible(code: Int, websiteOptIn: Boolean, groqFreeZdrOptIn: Boolean,\n                 groqKey: String): Boolean = code == 429 && websiteOptIn && groqFreeZdrOptIn &&',
    '''    /** Only definitive free-route HTTP rejections. A timeout or unknown network outcome
     * is NEVER resent to another provider, and an alternate response is NEVER retried.
     * 404 can mean OpenRouter has no free endpoint matching the requested JSON parameters.
     */
    fun routeRejected(code: Int): Boolean = code in setOf(404, 429, 502, 503, 504)

    fun eligible(code: Int, websiteOptIn: Boolean, groqFreeZdrOptIn: Boolean,
                 groqKey: String): Boolean = routeRejected(code) && websiteOptIn && groqFreeZdrOptIn &&''')

edit(flow,
    '''                if (response.code == 429) {
                    // Only a definitive final HTTP rejection can trigger another provider.
                    // Close the first response before attempting the separately consented resend.
                    response.close()
                    fallbackWebsiteOn429(call, serial, id, snapshot)
                    return
                }''',
    '''                val rejectedStatus = response.code
                if (WorkspaceWebsiteGroqFallback.routeRejected(rejectedStatus)) {
                    // The user enabled website source sharing once in Settings; no per-edit
                    // permission dialog. Only a definitive HTTP rejection may switch routes.
                    response.close()
                    fallbackWebsiteOnRejected(call, serial, id, snapshot, rejectedStatus)
                    return
                }''')
edit(flow,
    '''    private fun fallbackWebsiteOn429(first: Call, serial: Long, id: String,
                                     snapshot: WorkspaceWebsiteGeneration.Snapshot) {''',
    '''    private fun fallbackWebsiteOnRejected(first: Call, serial: Long, id: String,
                                           snapshot: WorkspaceWebsiteGeneration.Snapshot,
                                           statusCode: Int) {''')
edit(flow, 'WorkspaceWebsiteGroqFallback.eligible(429, optedIn, groqFree, key)',
     'WorkspaceWebsiteGroqFallback.eligible(statusCode, optedIn, groqFree, key)')
edit(flow, '"OpenRouter Free HTTP 429. Website Groq fallback is unavailable or OFF. " +',
     '"Primary free route HTTP $statusCode. Website Groq fallback is unavailable or OFF. " +')
edit(flow, '"OpenRouter 429; Groq Free website fallback not sent: " +',
     '"Primary free route HTTP $statusCode; Groq Free website fallback not sent: " +')
edit(flow, 'report("OpenRouter Free rate-limited; trying Groq Free once for this website · Stop ■ to cancel.")',
     'report("Primary free route unavailable; trying Groq Free for this website · Stop ■ to cancel.")')
# A rejected coding turn is already saved once in Chat as an assistant response. Avoid
# duplicating it in a persistent status banner and opening an unsolicited Retry dialog.
edit(flow,
     '    private fun error(message: String) = terminal(WorkspaceCodingResult.failure(message), message)',
     '    private fun error(message: String) = terminal(WorkspaceCodingResult.failure(message), "")')
edit(activity,
    '''                if (::root.isInitialized) {
                    render()
                    if (message.startsWith("Free AI reached its output-token limit") ||
                        message.startsWith("OpenRouter returned HTTP ") ||
                        message.startsWith("Phone/network ") ||
                        message.startsWith("Free AI provider returned an error") ||
                        message.startsWith("Free AI stopped")) presentCodingFailure(message)
                }''',
    '''                if (::root.isInitialized) render() // Failure is already in Chat; no duplicate modal.''')

edit('res/layout/activity_api_cloud_settings.xml',
     'Allow automatic OpenRouter 429 → Groq Free WEBSITE fallback',
     'Allow automatic OpenRouter unavailable → Groq Free WEBSITE fallback')
edit('res/layout/activity_api_cloud_settings.xml',
     'If a website build receives a final OpenRouter HTTP 429, LYRA may send',
     'If a website build receives final OpenRouter HTTP 404/429/502/503/504, LYRA may send')

# Preserve existing opt-in preference key and OFF default, so an already-approved user
# automatically gets this fix without agreeing to every individual file change.
test = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGroqFallbackTest.kt')
text = test.read_text(encoding='utf-8')
old = '''    @Test fun final429OnlyWithSeparateWebsiteAndGroqFreeConsentAndKey() {
        val allowed = WorkspaceWebsiteGroqFallback::eligible
        assertTrue(allowed(429, true, true, "gsk_test_key"))
        for (code in listOf(400, 401, 402, 403, 408, 500, 502, 503, 504)) {
            assertFalse(allowed(code, true, true, "gsk_test_key"))
        }
        assertFalse(allowed(429, false, true, "gsk_test_key"))
        assertFalse(allowed(429, true, false, "gsk_test_key"))
        assertFalse(allowed(429, true, true, ""))
        assertFalse(allowed(429, true, true, "bad key"))
    }'''
new = '''    @Test fun definitiveFreeRouteRejectionsSwitchWithoutRepeatingConsent() {
        val allowed = WorkspaceWebsiteGroqFallback::eligible
        for (code in listOf(404, 429, 502, 503, 504)) {
            assertTrue("Expected safe failover for HTTP $code", allowed(code, true, true, "gsk_test_key"))
        }
        for (code in listOf(200, 400, 401, 402, 403, 408, 413, 422, 500)) {
            assertFalse("Must not resend for HTTP $code", allowed(code, true, true, "gsk_test_key"))
        }
        assertFalse(allowed(404, false, true, "gsk_test_key"))
        assertFalse(allowed(404, true, false, "gsk_test_key"))
        assertFalse(allowed(404, true, true, ""))
        assertFalse(allowed(404, true, true, "bad key"))
        assertEquals("workspace_website_groq_429_opt_in", WorkspaceWebsiteGroqFallback.PREFERENCE_KEY)
    }'''
if text.count(old) != 1:
    raise SystemExit('Expected exact website fallover test anchor')
test.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Applied website 404/429/502/503/504 consented free failover, no duplicate coding error popup, and regression tests.')
