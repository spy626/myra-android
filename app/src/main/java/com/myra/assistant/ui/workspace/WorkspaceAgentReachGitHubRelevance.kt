package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Deterministic local path selector for pinned GitHub repository metadata.
 *
 * It never reads file contents and never sends repository metadata to a provider. The selector
 * chooses only a few text-like public files so Agent Reach can retrieve them one-by-one later.
 */
internal object WorkspaceAgentReachGitHubRelevance {
    const val MAX_SELECTED_FILES = 4
    private const val MAX_FILE_BYTES = 48_000

    data class Candidate(
        val path: String,
        val score: Int,
        val reason: String,
        val size: Int?,
    )

    private val url = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val word = Regex("""[A-Za-z0-9_+.-]{3,}""")
    private val stopWords = setOf(
        "https", "github", "com", "www", "check", "read", "inspect", "review", "open",
        "analyse", "analyze", "summarise", "summarize", "repo", "repository", "this", "that",
        "with", "from", "into", "please", "bro", "isko", "karo", "kro", "dekho", "dekh",
        "idea", "ideas", "feature", "features"
    )
    private val textExtensions = setOf(
        "md", "txt", "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "json", "jsonc", "yaml", "yml", "toml", "xml", "gradle", "properties", "rs", "go",
        "swift", "c", "cc", "cpp", "h", "hpp", "css", "html", "htm"
    )
    private val blockedNames = setOf(
        ".env", ".env.local", ".env.production", "id_rsa", "id_ed25519"
    )
    private val highSignalNames = mapOf(
        "skill.md" to 70,
        "agents.md" to 65,
        "agent.md" to 55,
        "architecture.md" to 60,
        "security.md" to 55,
        "contributing.md" to 30,
        "pyproject.toml" to 35,
        "package.json" to 35,
        "build.gradle" to 30,
        "build.gradle.kts" to 30,
        "settings.gradle" to 25,
        "settings.gradle.kts" to 25,
        "cargo.toml" to 35,
        "go.mod" to 30
    )

    private fun queryTokens(message: String): Set<String> {
        val withoutUrls = url.replace(message, " ")
        return word.findAll(withoutUrls)
            .map { it.value.lowercase(Locale.US).trim('.', '-', '_', '+') }
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    private fun isBlockedPath(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        val name = lower.substringAfterLast('/')
        if (name in blockedNames) return true
        if (lower.split('/').any {
                it == ".git" || it == "node_modules" || it == "vendor" ||
                    it == "build" || it == "dist" || it == "target"
            }) return true
        val sensitive = listOf("secret", "credential", "private_key", "apikey", "api_key")
        return sensitive.any { token -> name.contains(token) }
    }

    private fun textLike(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase(Locale.US)
        if (name == "dockerfile" || name == "makefile" || name == "license") return true
        val ext = name.substringAfterLast('.', "")
        return ext in textExtensions
    }

    fun select(
        message: String,
        pathMap: WorkspaceAgentReachGitHub.RepositoryPathMap,
    ): List<Candidate> {
        val tokens = queryTokens(message)
        return pathMap.entries.asSequence()
            .filter { it.kind == WorkspaceAgentReachGitHub.PathEntryKind.FILE }
            .filter { it.size == null || it.size in 1..MAX_FILE_BYTES }
            .filter { textLike(it.path) && !isBlockedPath(it.path) }
            .mapNotNull { entry ->
                val lower = entry.path.lowercase(Locale.US)
                val name = lower.substringAfterLast('/')
                var score = highSignalNames[name] ?: 0
                val reasons = mutableListOf<String>()

                tokens.forEach { token ->
                    if (name.contains(token)) {
                        score += 50
                        reasons += "filename matches '$token'"
                    } else if (lower.split('/').any { segment -> segment.contains(token) }) {
                        score += 24
                        reasons += "path matches '$token'"
                    }
                }

                if (lower.startsWith("docs/") || "/docs/" in lower) {
                    score += 18
                    reasons += "documentation path"
                }
                if (lower.startsWith("src/") || "/src/" in lower) score += 8
                if ("example" in lower || "sample" in lower) score += 10
                if (name.startsWith("readme")) score -= 20
                score -= lower.count { it == '/' }.coerceAtMost(8)

                if (score <= 0) null else Candidate(
                    path = entry.path,
                    score = score,
                    reason = reasons.distinct().take(3).joinToString("; ")
                        .ifBlank { "high-signal repository file" },
                    size = entry.size
                )
            }
            .sortedWith(compareByDescending<Candidate> { it.score }
                .thenBy { it.path.lowercase(Locale.US) })
            .distinctBy { it.path.lowercase(Locale.US) }
            .take(MAX_SELECTED_FILES)
            .toList()
    }
}
