#!/usr/bin/env python3
"""Exact-anchor, one-time repair for recorded Groq 400 and missing CTA feedback.

Run only on agent/myra-phase-1; workflow tests before pushing code. No new model,
provider, storage owner, permission, paid route or unbounded retry.
"""
from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteFailureAndClickFeedbackTest.kt')

def replace_once(path, old, new):
    value = path.read_text()
    assert value.count(old) == 1, f'Source changed or anchor missing: {path} (count={value.count(old)})'
    path.write_text(value.replace(old, new, 1))

# Two structured-output attempts can both be rejected by a free backend. For
# the existing LAST attempt, use documented plain-text mode. Still require
# exact three-file JSON via the unchanged local parser, safety and Undo gates.
groq = ROOT / 'WorkspaceWebsiteGroqFallback.kt'
replace_once(groq,
    '        payload.put("response_format", JSONObject().put("type", "json_object"))\n',
    '''        // Last of at most three attempts: JSON Schema and JSON Object modes may
        // both receive a definite HTTP 400. Use the documented default text mode.
        // The approved JSON-only instruction and strict local parser remain in force.
        payload.remove("response_format")
''')

# A 400 alone does not reveal whether syntax, JSON generation or size failed.
# Parse only the provider error object; never display raw content or prompts.
error_file = ROOT / 'WorkspaceWebsiteProviderError.kt'
assert not error_file.exists(), 'Provider error categorizer already exists; refusing overwrite'
error_file.write_text('''package com.myra.assistant.ui.workspace

import org.json.JSONObject
import java.util.Locale

/** Safe, bounded Groq HTTP-400 category only. Never return raw provider messages:
 * they can echo user source, secrets or failed model generations.
 */
internal object WorkspaceWebsiteProviderError {
    fun category(body: String): String {
        if (body.length !in 1..8192) return "reason unavailable"
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            ?: return "reason unavailable"
        val code = error.optString("code").lowercase(Locale.ROOT)
        val message = error.optString("message").lowercase(Locale.ROOT)
        return when {
            "json_validate_failed" in code ||
                "generated json does not match" in message ||
                ("json" in message && "validation" in message) ->
                "generated JSON rejected by provider"
            "response_format" in message || "json_schema" in message ||
                "json_object" in message -> "response format rejected"
            "max_completion_tokens" in message || "max_tokens" in message ->
                "output token setting rejected"
            "context" in message && ("length" in message || "large" in message) ->
                "context too large"
            "model" in message && ("not found" in message || "unsupported" in message) ->
                "model rejected"
            else -> "reason unavailable"
        }
    }
}
''')

website = ROOT / 'WorkspaceWebsiteGeneration.kt'
replace_once(website,
    '''                400 -> "Groq Free HTTP 400: request or output format rejected, not a quota " +
                    "or billing signal. No further retry or paid fallback; project files unchanged."
''',
    '''                400 -> "Groq Free HTTP 400 (" +
                    WorkspaceWebsiteProviderError.category(result.peekBody(8_193L).string()) +
                    "): request rejected. No further retry or paid fallback; project files unchanged."
''')

# The user explicitly requested that tapping Explore Minicoy show the text
# "Exploring Minicoy!". Native in-page anchor + :target sibling feedback works
# without trusting a generated click listener or adding a JS bridge.
action = ROOT / 'WorkspaceWebsiteActionQuality.kt'
replace_once(action,
    '''    private const val FEEDBACK = "\\n/* LYRA Explore target feedback */\\n" +
        ".lyra-explore-target:target { outline: 2px solid #0f766e; " +
        "outline-offset: 6px; scroll-margin-top: 24px; border-radius: 6px; }\\n"
''',
    '''    private const val FEEDBACK = "\\n/* LYRA Explore target feedback */\\n" +
        ".lyra-explore-target:target { outline: 2px solid #0f766e; " +
        "outline-offset: 6px; scroll-margin-top: 24px; border-radius: 6px; }\\n" +
        ".lyra-explore-feedback { display: none; }\\n" +
        ".lyra-explore-target:target + .lyra-explore-feedback { display: block; " +
        "margin: 12px 0; padding: 10px 14px; background: #d1fae5; " +
        "color: #064e3b; font-weight: 700; border-radius: 8px; }\\n"
''')
replace_once(action,
    '''        fixedHtml = fixedHtml.replaceRange(freshHeading.range,
            opening + ">" + freshHeading.value.substringAfter('>'))
        val fixedCss = if (css.contains("/* LYRA Explore target feedback */")) css else css + FEEDBACK
''',
    '''        fixedHtml = fixedHtml.replaceRange(freshHeading.range,
            opening + ">" + freshHeading.value.substringAfter('>'))
        if (goal.contains("Exploring Minicoy!", ignoreCase = true) &&
            !fixedHtml.contains("lyra-explore-feedback")) {
            val updatedHeading = heading.findAll(fixedHtml).firstOrNull {
                text(it.groupValues[3]).equals("Things to Explore", ignoreCase = true)
            } ?: return Review(files, false)
            val feedback = "<p class=\\"lyra-explore-feedback\\" role=\\"status\\" " +
                "aria-live=\\"polite\\">Exploring Minicoy!</p>"
            fixedHtml = fixedHtml.replaceRange(updatedHeading.range.last + 1,
                updatedHeading.range.last + 1, feedback)
        }
        val fixedCss = if (css.contains("/* LYRA Explore target feedback */")) css else css + FEEDBACK
''')

verify = ROOT / 'WorkspaceWebsiteConsistency.kt'
replace_once(verify,
    '''                    "Explore Minicoy target or visible feedback is missing; no files changed"
                }
            }
        }
        return files
''',
    '''                    "Explore Minicoy target or visible feedback is missing; no files changed"
                }
                if (goal.contains("Exploring Minicoy!", ignoreCase = true)) {
                    require(html.contains("class=\\"lyra-explore-feedback\\"") &&
                        html.contains("Exploring Minicoy!") &&
                        css.contains(".lyra-explore-target:target + .lyra-explore-feedback")) {
                        "Requested Explore click text is missing; no files changed"
                    }
                }
            }
        }
        return files
''')

assert not TEST.exists(), 'Test already exists; refusing overwrite'
TEST.write_text('''package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteFailureAndClickFeedbackTest {
    private val goal = "Minicoy website: Welcome to Minicoy heading, Explore Minicoy button; " +
        "Things to Explore section. Button tap karne par Things to Explore tak scroll ho aur " +
        "Exploring Minicoy! text clearly dikhe."
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))

    private fun body(request: okhttp3.Request): JSONObject {
        val out = Buffer()
        requireNotNull(request.body).writeTo(out)
        return JSONObject(out.readUtf8())
    }

    @Test fun thirdAttemptIsTextModeWithSameFreeModelAndApprovedSource() {
        val first = body(WorkspaceWebsiteGroqFallback.request("gsk_test", snapshot))
        val third = body(WorkspaceWebsiteGroqFallback.compatibilityRequest("gsk_test", snapshot))
        assertEquals(first.getString("model"), third.getString("model"))
        assertEquals(first.getJSONArray("messages").toString(), third.getJSONArray("messages").toString())
        assertEquals("json_schema", first.getJSONObject("response_format").getString("type"))
        assertFalse(third.has("response_format"))
        assertFalse(third.has("provider"))
        assertFalse(third.has("plugins"))
        assertTrue(runCatching { WorkspaceWebsiteGeneration.parse("not json") }.isFailure)
        assertTrue(runCatching { WorkspaceWebsiteGeneration.parse("{\\\"files\\\":{}}") }.isFailure)
    }

    @Test fun providerErrorCategoryNeverEchoesSecretsOrSource() {
        val source = "gsk_private_key_DoNotShow My sensitive website code"
        val body = JSONObject().put("error", JSONObject().put("code", "json_validate_failed")
            .put("message", "Generated JSON does not match schema; $source")).toString()
        assertEquals("generated JSON rejected by provider", WorkspaceWebsiteProviderError.category(body))
        assertEquals("response format rejected", WorkspaceWebsiteProviderError.category(
            JSONObject().put("error", JSONObject().put("message", "response_format json_schema invalid $source")).toString()))
        assertEquals("reason unavailable", WorkspaceWebsiteProviderError.category("not-json $source"))
        assertFalse(WorkspaceWebsiteProviderError.category(body).contains(source))
        assertEquals("reason unavailable", WorkspaceWebsiteProviderError.category("a".repeat(8_193)))
    }

    @Test fun requestedTapFeedbackIsVisibleViaNativeTargetAndIdempotent() {
        val input = mapOf("index.html" to "<html><head></head><body>" +
            "<h1>Welcome to Minicoy</h1><button>Explore Minicoy</button>" +
            "<h2>Things to Explore</h2></body></html>",
            "style.css" to "body { margin: 0; }", "script.js" to "")
        val first = WorkspaceWebsiteVisualQuality.review(snapshot, input)
        val html = first.files.getValue("index.html")
        val css = first.files.getValue("style.css")
        assertTrue(html.contains("href=\\"#lyra-explore-section\\""))
        assertTrue(html.contains("class=\\"lyra-explore-feedback\\""))
        assertTrue(html.contains("Exploring Minicoy!"))
        assertTrue(css.contains(".lyra-explore-target:target + .lyra-explore-feedback { display: block;"))
        assertEquals(1, Regex("Exploring Minicoy!").findAll(html).count())
        assertEquals(first.files, WorkspaceWebsiteVisualQuality.review(snapshot, first.files).files)
    }

    @Test fun unrelatedSiteDoesNotAcquireMinicoySpecificFeedback() {
        val unrelated = snapshot.copy(goal = "Build a booking site")
        val result = WorkspaceWebsiteActionQuality.review(unrelated, mapOf(
            "index.html" to "<html><body><button>Explore Minicoy</button>" +
                "<h2>Things to Explore</h2></body></html>",
            "style.css" to "", "script.js" to ""))
        assertFalse(result.repaired)
        assertFalse(result.files.getValue("index.html").contains("lyra-explore-feedback"))
    }
}
''')

# The pre-existing regression test expects JSON Object Mode; the third attempt
# now intentionally removes that constrained mode while retaining local parsing.
old_test = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteRecoveryTest.kt')
replace_once(old_test,
    '        assertEquals("json_object", second.getJSONObject("response_format").getString("type"))\n',
    '        assertFalse(second.has("response_format")) // last attempt is plain text, locally parsed\n')
print('Staged one-shot free text fallback, secret-safe 400 category, native click feedback and tests')
