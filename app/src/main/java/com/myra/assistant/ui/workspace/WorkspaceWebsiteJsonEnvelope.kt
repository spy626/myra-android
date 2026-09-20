package com.myra.assistant.ui.workspace

/** Bounded, offline normalization for common text-mode JSON presentation mistakes.
 * Only one COMPLETE envelope may be considered; never fill missing braces/files,
 * guess source code, replay a provider call or turn free-form prose into project files.
 * WorkspaceWebsiteGeneration.parse still enforces the exact three paths and source safety.
 */
internal object WorkspaceWebsiteJsonEnvelope {
    private val fence = Regex("""(?s)^```(?:json)?[ \t]*\r?\n(.*?)\r?\n?```[ \t]*$""", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimStart('\uFEFF').trim()
        val unwrapped = fence.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
        val start = unwrapped.indexOf('{')
        val end = unwrapped.lastIndexOf('}')
        if (start < 0 || end <= start) return unwrapped
        val before = unwrapped.substring(0, start)
        val after = unwrapped.substring(end + 1)
        // Only a bounded leading explanation is tolerated. A trailing sentence,
        // second candidate or code fence must retain the original rejection behavior.
        if (before.length > 160 || after.isNotBlank() ||
            before.any { it == '{' || it == '}' || it == '`' }) return unwrapped
        val candidate = unwrapped.substring(start, end + 1)
        // Preserve all actual file bytes except invalid literal CR/LF inside JSON
        // strings, which must be escaped by the JSON transport. Do not repair quotes,
        // backslashes, braces or incomplete responses.
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
        return result.toString()
    }
}
