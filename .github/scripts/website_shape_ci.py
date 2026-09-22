from pathlib import Path

root = Path('app/src/main/java/com/myra/assistant/ui/workspace')
target = root / 'WorkspaceWebsiteGeneration.kt'
source = target.read_text()
old = '''        require(root.keys().asSequence().toSet() == setOf("files")) {
            "Website response must contain only a files object"
        }
        val files = requireNotNull(root.optJSONObject("files")) { "Website files object is missing" }
'''
new = '''        // Accept only complete, unambiguous representations of the SAME three files.
        // Path, size, secret, HTML and atomic-write checks below remain authoritative.
        val files = WorkspaceWebsiteReplyShape.exactFiles(root)
'''
assert source.count(old) == 1, 'Website parser unexpectedly changed: abort'
target.write_text(source.replace(old, new, 1))
helper = root / 'WorkspaceWebsiteReplyShape.kt'
assert not helper.exists(), 'Shape adapter already exists: abort'
helper.write_text(r'''package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject

/** Complete, exact three-file transport formats only. No guessed paths or repaired source.
 * Fixed diagnostics never echo the model reply or any user source.
 */
internal object WorkspaceWebsiteReplyShape {
    private val paths = WorkspaceWebsiteGeneration.PATHS.toSet()

    private fun fail(category: String): Nothing = throw IllegalArgumentException(
        "Website reply structure unsupported [shape: $category]; no files changed")

    fun exactFiles(root: JSONObject): JSONObject {
        val keys = root.keys().asSequence().toSet()
        if (keys == paths) return root
        if (keys != setOf("files")) {
            val category = when {
                "files" in keys -> "extra_top_level_fields"
                keys.any { it in paths } -> "incomplete_or_extra_file_keys"
                keys.any { it in setOf("html", "css", "javascript", "js") } -> "unlabelled_file_fields"
                else -> "unknown_top_level"
            }
            fail(category)
        }
        return when (val value = root.opt("files")) {
            is JSONObject -> value
            is JSONArray -> fromExactArray(value)
            else -> fail("files_value_not_object_or_array")
        }
    }

    private fun fromExactArray(array: JSONArray): JSONObject {
        if (array.length() != WorkspaceWebsiteGeneration.PATHS.size) fail("files_array_wrong_count")
        val result = JSONObject()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: fail("files_array_entry_invalid")
            if (item.keys().asSequence().toSet() != setOf("path", "content"))
                fail("files_array_entry_invalid")
            val path = item.opt("path") as? String ?: fail("files_array_entry_invalid")
            val content = item.opt("content") as? String ?: fail("files_array_entry_invalid")
            if (path !in paths || result.has(path)) fail("files_array_paths_invalid")
            result.put(path, content)
        }
        if (result.keys().asSequence().toSet() != paths) fail("files_array_paths_invalid")
        return result
    }
}
''')
test = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteReplyShapeTest.kt')
assert not test.exists(), 'Shape tests already exist: abort'
test.write_text(r'''package com.myra.assistant.ui.workspace

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
''')
print('Patched parser and created exact-shape adapter + regression tests; no other files touched')
