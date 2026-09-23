package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/** Bounded, offline recovery for complete model transport formats. Never invent a file,
 * synthesize a missing brace, replay a provider call, or parse prose as source code.
 * The canonical three-file parser still checks the complete HTML, allowed paths, limits,
 * secret screening, source freshness and atomic Undo/Keep before a file can be saved.
 */
internal object WorkspaceWebsiteJsonEnvelope {
    private val jsonFence = Regex(
        """(?s)```(?:json)?[ \t]*\r?\n(.*?)\r?\n?```""",
        RegexOption.IGNORE_CASE)

    private fun boundedWrapper(text: String): Boolean {
        val value = text.trim()
        return value.length <= 160 && value.none { it == '{' || it == '}' || it == '`' }
    }

    private fun isFileLabel(raw: String, path: String): Boolean {
        var label = raw.trim()
        val hashes = label.takeWhile { it == '#' }.length
        if (hashes in 1..6) label = label.drop(hashes).trimStart()
        for (marker in listOf("**", "__", "`")) {
            if (label.startsWith(marker) && label.endsWith(marker) &&
                label.length > marker.length * 2) {
                label = label.substring(marker.length, label.length - marker.length).trim()
            }
        }
        return label == path
    }
    /** A completed, explicitly labelled triple of standalone code fences is not free-form
     * prose. The order and labels are exact. Only a short bounded prose wrapper is ignored;
     * an extra block, duplicate path or nested fence still causes rejection.
     */
    private fun exactLabelledFiles(raw: String): String? {
        if (raw.length > 30_000) return null
        val lines = raw.lines()
        val languages = mapOf(
            "index.html" to setOf("```html", "```"),
            "style.css" to setOf("```css", "```"),
            "script.js" to setOf("```javascript", "```js", "```"))
        val start = lines.indexOfFirst { isFileLabel(it, WorkspaceWebsiteGeneration.PATHS.first()) }
        if (start < 0 || !boundedWrapper(lines.take(start).joinToString("\n"))) return null
        val files = LinkedHashMap<String, String>()
        var line = start
        for ((index, path) in WorkspaceWebsiteGeneration.PATHS.withIndex()) {
            val label = lines.getOrNull(line) ?: return null
            if (!isFileLabel(label, path)) return null
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
        if (!boundedWrapper(lines.drop(line).joinToString("\n"))) return null
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
        val fenced = jsonFence.findAll(trimmed).toList()
        val unwrapped = if (fenced.size == 1) {
            val match = fenced.single()
            val before = trimmed.substring(0, match.range.first)
            val after = trimmed.substring(match.range.last + 1)
            if (boundedWrapper(before) && boundedWrapper(after))
                match.groupValues[1].trim()
            else trimmed
        } else trimmed
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
