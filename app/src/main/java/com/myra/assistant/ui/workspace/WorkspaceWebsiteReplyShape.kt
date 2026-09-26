package com.myra.assistant.ui.workspace

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
