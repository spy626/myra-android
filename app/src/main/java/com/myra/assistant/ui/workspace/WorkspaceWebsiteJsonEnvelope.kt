package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/** Bounded, offline recovery for complete model transport formats. Never invent a file,
 * synthesize a missing brace, replay a provider call, or parse prose as source code.
 * The canonical three-file parser still checks the complete HTML, allowed paths, limits,
 * secret screening, source freshness and atomic Undo/Keep before a file can be saved.
 */
internal object WorkspaceWebsiteJsonEnvelope {
    private val fence = Regex("""(?s)^```(?:json)?[ \t]*\r?\n(.*?)\r?\n?```[ \t]*$""", RegexOption.IGNORE_CASE)
    private val prefixedFence = Regex(
        """(?s)^[^{}\r\n`]{1,160}\r?\n```(?:json)?[ \t]*\r?\n(.*?)\r?\n?```[ \t]*$""",
        RegexOption.IGNORE_CASE)

    /** A completed, explicitly labelled triple of standalone code fences is not free-form
     * prose. The order and labels are exact; an extra block, preface, duplicate path, nested
     * fence or trailing explanation causes rejection rather than a guessed project write.
     */
    private fun exactLabelledFiles(raw: String): String? {
        if (raw.length > 30_000) return null
        val lines = raw.lines()
        val languages = mapOf(
            "index.html" to setOf("```html", "```"),
            "style.css" to setOf("```css", "```"),
            "script.js" to setOf("```javascript", "```js", "```"))
        val files = LinkedHashMap<String, String>()
        var line = 0
        for ((index, path) in WorkspaceWebsiteGeneration.PATHS.withIndex()) {
            val label = lines.getOrNull(line)?.trim() ?: return null
            if (label != path && label != "### $path") return null
            line++
            val opening = lines.getOrNull(line)?.trim()?.lowercase() ?: return null
            if (opening !in languages.getValue(path)) return null
            line++
            val content = mutableListOf<String>()
            while (line < lines.size && lines[line].trim() != "```") {
                if (lines[line].trimStart().startsWith("```")) return null
                content += lines[line]
                line++
            }
            if (line >= lines.size) return null
            files[path] = content.joinToString("\n")
            line++
            // Models may insert a blank line between complete file blocks. Accept at
            // most two separators; never skip arbitrary text, missing labels or fences.
            if (index < WorkspaceWebsiteGeneration.PATHS.lastIndex) {
                var blankLines = 0
                while (line < lines.size && lines[line].isBlank()) {
                    if (++blankLines > 2) return null
                    line++
                }
            }
        }
        if (line != lines.size) return null
        val fileObject = JSONObject()
        files.forEach { (path, source) -> fileObject.put(path, source) }
        return JSONObject().put("files", fileObject).toString()
    }

    /** Diagnose a completed response without persisting any raw model text, source, or keys.
     * This is not an opportunity to retry an uncertain request or relax file validation.
     */
    private fun completeObjectOrFail(candidate: String, original: String): String {
        val valid = runCatching {
            val tokens = JSONTokener(candidate)
            tokens.nextValue() is JSONObject && tokens.nextClean() == '\u0000'
        }.getOrDefault(false)
        if (!valid) {
            val category = when {
                original.startsWith("{") && !original.endsWith("}") -> "incomplete_json"
                original.startsWith("{") -> "invalid_json_syntax"
                original.startsWith("[") -> "unsupported_root"
                "```" in original -> "unrecognized_fence"
                else -> "non_json_output"
            }
            throw IllegalArgumentException(
                "Website model did not return complete three-file JSON [format: $category]; no files changed")
        }
        return candidate
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimStart('\uFEFF').trim()
        exactLabelledFiles(trimmed)?.let { return it }
        val unwrapped = fence.matchEntire(trimmed)?.groupValues?.get(1)?.trim()
            ?: prefixedFence.matchEntire(trimmed)?.groupValues?.get(1)?.trim()
            ?: trimmed
        val start = unwrapped.indexOf('{')
        val end = unwrapped.lastIndexOf('}')
        if (start < 0 || end <= start) return completeObjectOrFail(unwrapped, trimmed)
        val before = unwrapped.substring(0, start)
        val after = unwrapped.substring(end + 1)
        if (before.length > 160 || after.isNotBlank() ||
            before.any { it == '{' || it == '}' || it == '`' }) {
            return completeObjectOrFail(unwrapped, trimmed)
        }
        val candidate = unwrapped.substring(start, end + 1)
        // Preserve actual file text while escaping invalid literal CR/LF inside JSON
        // strings. Do not repair quotes, backslashes, braces or incomplete responses.
        val result = StringBuilder(candidate.length + 32)
        var quoted = false
        var escaped = false
        for (character in candidate) {
            when {
                escaped -> { result.append(character); escaped = false }
                character == '\\' -> { result.append(character); escaped = true }
                character == '"' -> { result.append(character); quoted = !quoted }
                quoted && character == '\n' -> result.append("\\n")
                quoted && character == '\r' -> result.append("\\r")
                else -> result.append(character)
            }
        }
        return completeObjectOrFail(result.toString(), trimmed)
    }
}
