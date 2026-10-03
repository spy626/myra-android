package com.myra.assistant.ui.workspace

/** Advisory only: never rewrites source files or authorizes deletion. */
object WorkspaceDeleteReferencePolicy {
    private val patterns = listOf(
        Regex("""(?i)\b(?:href|src)\s*=\s*["']([^"'<>]+)["']"""),
        Regex("""(?i)@import\s+(?:url\(\s*)?["']?([^"'\s)]+)"""),
        Regex("""(?i)\burl\(\s*["']?([^"'\s)]+)"""),
        Regex("""(?i)\b(?:from|import)\s*["']([^"']+)["']"""),
        Regex("""(?i)\bimport\s*\(\s*["']([^"']+)["']"""),
    )

    fun referencesTarget(sourcePath: String, content: String, deletedPath: String): Boolean =
        patterns.any { pattern ->
            pattern.findAll(content).any { match ->
                val resolved = resolve(sourcePath, match.groupValues[1])
                resolved != null && (resolved == deletedPath || resolved.startsWith("$deletedPath/"))
            }
        }

    private fun resolve(sourcePath: String, raw: String): String? {
        val candidate = raw.trim().substringBefore('#').substringBefore('?')
        if (candidate.isBlank() || candidate.startsWith("//") || candidate.contains(':') ||
            candidate.contains('\\') || candidate.startsWith('#') || candidate.startsWith('?')) return null
        val result = if (candidate.startsWith('/')) mutableListOf() else
            sourcePath.split('/').dropLast(1).toMutableList()
        for (part in candidate.removePrefix("/").split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex) else return null
                else -> result.add(part)
            }
        }
        return result.takeIf { it.isNotEmpty() }?.joinToString("/")
    }
}
