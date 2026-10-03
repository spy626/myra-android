#!/usr/bin/env python3
"""One-time, exact-anchor Workspace phone-failure recovery; no new provider or storage owner."""
from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteRecoveryTest.kt')

def replace_once(path, old, new):
    text = path.read_text()
    assert text.count(old) == 1, f'Unexpected source revision or missing anchor: {path.name}'
    path.write_text(text.replace(old, new, 1))

groq = ROOT / 'WorkspaceWebsiteGroqFallback.kt'
replace_once(groq,
    '    /** GPT-OSS 120B supports strict JSON schema; require exactly the three named files.',
    '''    /** A definitive Groq HTTP 400 can indicate incompatibility with structured-output
     * parameters on a particular free backend. Reuse the exact approved snapshot and
     * key once with documented JSON Object Mode. Local parse/apply remain strict.
     * NEVER use this for timeouts, 429, or a response that may have succeeded.
     */
    fun compatibilityEligible(primary: WorkspaceWebsiteRoute.Provider, code: Int): Boolean =
        primary == WorkspaceWebsiteRoute.Provider.GROQ && code == 400

    fun compatibilityRequest(groqKey: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        val original = request(groqKey, snapshot)
        val buffer = Buffer()
        requireNotNull(original.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        payload.put("response_format", JSONObject().put("type", "json_object"))
        return original.newBuilder()
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /** GPT-OSS 120B supports strict JSON schema; require exactly the three named files.''')

flow = ROOT / 'WorkspaceChatCodingFlow.kt'
replace_once(flow,
    '''                val rejectedStatus = response.code
                if (primary == WorkspaceWebsiteRoute.Provider.OPENROUTER &&''',
    '''                val rejectedStatus = response.code
                if (WorkspaceWebsiteGroqFallback.compatibilityEligible(primary, rejectedStatus)) {
                    response.close() // Definitive HTTP rejection, not an uncertain timeout.
                    recoverGroqWebsiteFormat(call, serial, id, snapshot, groqKey)
                    return
                }
                if (primary == WorkspaceWebsiteRoute.Provider.OPENROUTER &&''')
replace_once(flow,
    '    private fun fallbackWebsiteOnRejected(first: Call, serial: Long, id: String,',
    '''    /** One same-provider compatibility attempt after a definitive Groq-only HTTP 400.
     * The second response is terminal: never loop, change provider, or replay on timeout.
     */
    private fun recoverGroqWebsiteFormat(first: Call, serial: Long, id: String,
                                         snapshot: WorkspaceWebsiteGeneration.Snapshot,
                                         groqKey: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== first || !current(id)) return@runOnUiThread
            val alternate = runCatching {
                WorkspaceWebsiteGroqFallback.compatibilityRequest(groqKey, snapshot)
            }.getOrElse {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("Groq Free rejected this website format; no files changed.")))
                return@runOnUiThread
            }
            val second = WorkspaceWebsiteGroqFallback.client.newCall(alternate)
            request = second
            report("Trying a compatible Groq Free website format · Stop ■ to cancel.")
            second.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    completeWebsite(call, serial, id, snapshot, Result.failure(
                        IllegalStateException("Groq Free compatibility attempt could not complete. " +
                            "No uncertain request was resent; project files unchanged.")))
                }
                override fun onResponse(call: Call, response: Response) = completeWebsite(
                    call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            })
        }
    }

    private fun fallbackWebsiteOnRejected(first: Call, serial: Long, id: String,''')
replace_once(flow,
    '''            result.onSuccess { generated ->
                runCatching {
                    WorkspaceWebsiteGeneration.apply(files, tasks, projects, snapshot, generated)
                }.onSuccess {
                    terminal(WorkspaceCodingResult.websiteSuccess(snapshot.original, generated))''',
    '''            result.onSuccess { generated ->
                val cleaned = WorkspaceWebsiteGeneration.omitUnverifiedImages(snapshot, generated)
                val omittedImages = cleaned["index.html"] != generated["index.html"]
                runCatching {
                    WorkspaceWebsiteGeneration.apply(files, tasks, projects, snapshot, cleaned)
                }.onSuccess {
                    val summary = WorkspaceCodingResult.websiteSuccess(snapshot.original, cleaned) +
                        if (omittedImages) "\\nUnverified images omitted; cards use the saved text and CSS." else ""
                    terminal(summary, "") // The durable Chat reply is the single success message.''')

generation = ROOT / 'WorkspaceWebsiteGeneration.kt'
replace_once(generation,
    '''            "Do not include external scripts, CDN dependencies, tracking, secrets or additional files. " +''',
    '''            "Do not invent image URLs or file names: this task writes only three text files. " +
            "For cards use CSS-only decoration or text. Do not add img tags without existing local assets. " +
            "Do not include external scripts, CDN dependencies, tracking, secrets or additional files. " +''')
replace_once(generation,
    '    @Synchronized fun pending(projects: WorkspaceProjectStore, id: String): BackupRecord? {',
    '''    /** A three-text-file generation cannot create photo assets. Preserve exact existing
     * image tags, but omit newly hallucinated image references before saving Preview.
     * This is not an image generator and never fetches an external URL.
     */
    fun omitUnverifiedImages(snapshot: Snapshot, generated: Map<String, String>): Map<String, String> {
        val html = generated["index.html"] ?: return generated
        val original = snapshot.original["index.html"].orEmpty()
        val clean = Regex("(?is)<img\\\\b[^>]*>").replace(html) { match ->
            if (original.contains(match.value)) match.value else ""
        }
        return if (clean == html) generated else generated + ("index.html" to clean)
    }

    @Synchronized fun pending(projects: WorkspaceProjectStore, id: String): BackupRecord? {''')

assert not TEST.exists(), 'Recovery test already exists; refusing overwrite'
TEST.write_text('''package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteRecoveryTest {
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", "Build a Minicoy website",
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))

    private fun payload(request: okhttp3.Request): JSONObject {
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    @Test fun onlyDefinitivelyRejectedGroqPrimary400GetsCompatibilityAttempt() {
        val eligible = WorkspaceWebsiteGroqFallback::compatibilityEligible
        assertTrue(eligible(WorkspaceWebsiteRoute.Provider.GROQ, 400))
        for (code in listOf(401, 403, 404, 408, 429, 500, 502, 503, 504)) {
            assertFalse(eligible(WorkspaceWebsiteRoute.Provider.GROQ, code))
        }
        assertFalse(eligible(WorkspaceWebsiteRoute.Provider.OPENROUTER, 400))
    }

    @Test fun compatibleRequestUsesSameApprovedContextFreeModelAndStrictLocalParsing() {
        val original = WorkspaceWebsiteGroqFallback.request("gsk_test_key", snapshot)
        val recovery = WorkspaceWebsiteGroqFallback.compatibilityRequest("gsk_test_key", snapshot)
        val first = payload(original)
        val second = payload(recovery)
        assertEquals(original.url, recovery.url)
        assertEquals(original.header("Authorization"), recovery.header("Authorization"))
        assertEquals(first.getJSONArray("messages").toString(), second.getJSONArray("messages").toString())
        assertEquals(first.getString("model"), second.getString("model"))
        assertEquals("json_schema", first.getJSONObject("response_format").getString("type"))
        assertEquals("json_object", second.getJSONObject("response_format").getString("type"))
        assertFalse(second.has("provider"))
        assertFalse(second.has("plugins"))
        assertTrue(runCatching { WorkspaceWebsiteGeneration.parse("{\\\"files\\\":{}}") }.isFailure)
    }

    @Test fun newlyInventedImagesAreOmittedButExistingMarkupIsPreserved() {
        val html = "<html><body><h1>Things to Explore</h1>" +
            "<img src=\\\"https://example.com/not-a-real-beach.jpg\\\" alt=\\\"Beach\\\">" +
            "<img src=\\\"missing-local-photo.jpg\\\" alt=\\\"Lighthouse\\\">" +
            "<h2>Beaches</h2><h2>Lighthouse</h2></body></html>"
        val cleaned = WorkspaceWebsiteGeneration.omitUnverifiedImages(snapshot,
            mapOf("index.html" to html, "style.css" to "", "script.js" to ""))
        assertFalse(cleaned.getValue("index.html").contains("<img"))
        assertTrue(cleaned.getValue("index.html").contains("Things to Explore"))
        assertTrue(cleaned.getValue("index.html").contains("Lighthouse"))
        val existing = snapshot.copy(original = snapshot.original +
            ("index.html" to "<img src=\\\"existing.jpg\\\">"))
        val preserved = WorkspaceWebsiteGeneration.omitUnverifiedImages(existing,
            mapOf("index.html" to "<html><body><img src=\\\"existing.jpg\\\"></body></html>"))
        assertTrue(preserved.getValue("index.html").contains("existing.jpg"))
    }
}
''')
print('Applied: Groq one-shot JSON compatibility, non-network image guard, one durable success reply; tests staged')
