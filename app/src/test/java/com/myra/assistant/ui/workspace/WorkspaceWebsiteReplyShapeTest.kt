package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteReplyShapeTest {
    private val html = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
        "<body><h1>Fallback Test</h1><script src=\"script.js\"></script></body></html>"
    private fun files() = JSONObject().put("index.html", html)
        .put("style.css", "body { color: black; }")
        .put("script.js", "document.body.dataset.ready = 'yes';")
    private fun parse(raw: String) = WorkspaceWebsiteGeneration.parse(raw)
    private fun rejected(raw: String, expected: String) {
        val error = runCatching { parse(raw) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains(expected))
        assertFalse(error.message.orEmpty().contains("PRIVATE_PROVIDER_TEXT_9"))
    }

    @Test fun canonicalAndDirectMapRequireSameCompleteFileValues() {
        val expected = parse(JSONObject().put("files", files()).toString())
        assertEquals(expected, parse(files().toString()))
        assertEquals(html, parse(files().toString()).getValue("index.html"))
    }

    @Test fun exactThreeFileArrayIsAcceptedRegardlessOfOrder() {
        val expected = parse(JSONObject().put("files", files()).toString())
        val array = JSONArray()
            .put(JSONObject().put("path", "script.js").put("content", expected.getValue("script.js")))
            .put(JSONObject().put("path", "index.html").put("content", html))
            .put(JSONObject().put("path", "style.css").put("content", expected.getValue("style.css")))
        assertEquals(expected, parse(JSONObject().put("files", array).toString()))
    }

    @Test fun extraFieldsMissingOrDuplicateFilesNeverBecomeCode() {
        rejected(files().put("PRIVATE_PROVIDER_TEXT_9", "unsafe").toString(),
            "incomplete_or_extra_file_keys")
        rejected(JSONObject().put("files", files()).put("explanation", "PRIVATE_PROVIDER_TEXT_9").toString(),
            "extra_top_level_fields")
        rejected(JSONObject().put("files", JSONObject().put("index.html", html)).toString(),
            "exactly index.html, style.css and script.js")
        val array = JSONArray()
            .put(JSONObject().put("path", "index.html").put("content", html))
            .put(JSONObject().put("path", "index.html").put("content", html))
            .put(JSONObject().put("path", "script.js").put("content", ""))
        rejected(JSONObject().put("files", array).toString(), "files_array_paths_invalid")
        rejected(JSONObject().put("files", JSONArray().put(JSONObject().put("path", "index.html")
            .put("content", html))).toString(), "files_array_wrong_count")
    }

    @Test fun invalidArrayEntriesAndUnsafeFilesRemainRejected() {
        val array = JSONArray()
            .put(JSONObject().put("path", "index.html").put("content", html)
                .put("extra", "PRIVATE_PROVIDER_TEXT_9"))
            .put(JSONObject().put("path", "style.css").put("content", ""))
            .put(JSONObject().put("path", "script.js").put("content", ""))
        rejected(JSONObject().put("files", array).toString(), "files_array_entry_invalid")
        rejected(JSONObject().put("files", "PRIVATE_PROVIDER_TEXT_9").toString(),
            "files_value_not_object_or_array")
        rejected(files().put("script.js", "const api_key = 'PRIVATE_PROVIDER_TEXT_9';").toString(),
            "Possible secret")
        rejected(files().put("index.html", "<div>Not a complete page</div>").toString(),
            "Website HTML is incomplete")
    }

    @Test fun unsupportedWrapperHasSafeFixedCategory() {
        rejected(JSONObject().put("response", files())
            .put("PRIVATE_PROVIDER_TEXT_9", "anything").toString(), "unknown_top_level")
    }
}
