package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Local-only, bounded structural evidence for a pinned public GitHub repository.
 *
 * Repository paths are EXTERNAL UNTRUSTED DATA. This index never grants authority, never becomes
 * provider input by itself, and never reads file bodies. It only classifies already validated tree
 * metadata at one immutable commit so later local selection can stay small and explainable.
 */
internal object WorkspaceAgentReachGitHubRepoIndex {
    const val MAX_INDEX_ENTRIES = 1_500
    const val MAX_INDEX_PATH_CHARS = 96_000

    enum class Category {
        README_DOCS,
        SOURCE,
        MANIFEST_BUILD,
        SKILLS_PLUGINS,
        TESTS,
        WORKFLOWS_CONFIG,
        OTHER,
    }

    data class Entry(
        val path: String,
        val kind: WorkspaceAgentReachGitHub.PathEntryKind,
        val objectSha: String,
        val size: Int?,
        val category: Category,
    )

    data class Index(
        val commitSha: String,
        val entries: List<Entry>,
        val observedEntries: Int,
        val excludedGeneratedEntries: Int,
    ) {
        init {
            require(entries.size <= MAX_INDEX_ENTRIES) {
                "GitHub repository index exceeds Agent Reach entry bound"
            }
        }

        fun count(category: Category): Int = entries.count { it.category == category }
    }

    private val safeSha = Regex("""[0-9a-fA-F]{40,64}""")
    private val generatedSegments = setOf(
        ".git", ".gradle", ".idea", ".next", ".venv", "__pycache__", "build",
        "coverage", "deriveddata", "dist", "node_modules", "out", "pods", "target",
        "vendor", "venv",
    )
    private val skillSegments =
        setOf("skill", "skills", "plugin", "plugins", "extension", "extensions")
    private val testSegments = setOf("test", "tests", "spec", "specs", "__tests__")
    private val docsSegments = setOf("doc", "docs", "documentation")
    private val sourceSegments = setOf("src", "source", "app", "lib", "core")
    private val manifestNames = setOf(
        "androidmanifest.xml", "build.gradle", "build.gradle.kts", "cargo.lock", "cargo.toml",
        "gemfile", "go.mod", "go.sum", "gradle.properties", "package-lock.json", "package.json",
        "package.swift", "pnpm-lock.yaml", "podfile", "poetry.lock", "pom.xml", "pyproject.toml",
        "requirements.txt", "settings.gradle", "settings.gradle.kts", "yarn.lock",
    )
    private val sourceExtensions = setOf(
        "c", "cc", "cpp", "cs", "css", "go", "h", "hpp", "html", "java", "js", "jsx", "kt",
        "kts", "m", "mm", "php", "py", "rb", "rs", "scala", "sh", "svelte", "swift", "ts",
        "tsx", "vue",
    )
    private val docsExtensions = setOf("adoc", "md", "mdx", "rst")

    private fun validatePath(path: String) {
        require(path.isNotBlank() && path.length <= 1_024 && path.none(Char::isISOControl)) {
            "GitHub repository index contains an unsafe path"
        }
        require(path.split('/').all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 255
        }) { "GitHub repository index contains an unsafe path segment" }
    }

    private fun classify(path: String): Category {
        val lower = path.lowercase(Locale.US)
        val segments = lower.split('/')
        val name = segments.last()
        val extension = name.substringAfterLast('.', "")
        val stem = name.substringBeforeLast('.', name)

        if (segments.any { it in skillSegments } || name == "skill.md" || name == "agents.md") {
            return Category.SKILLS_PLUGINS
        }
        if (segments.any { it in testSegments } || stem.endsWith("test") ||
            stem.endsWith("tests") || stem.endsWith("spec")) {
            return Category.TESTS
        }
        if (lower.startsWith(".github/workflows/") ||
            segments.any { it == "config" || it == "configs" || it == ".config" } ||
            name == ".editorconfig" || name == "dockerfile" ||
            name.startsWith("docker-compose.") || name.startsWith("compose.") ||
            name.contains("config.")) {
            return Category.WORKFLOWS_CONFIG
        }
        if (name in manifestNames) return Category.MANIFEST_BUILD
        if (name.startsWith("readme") || segments.any { it in docsSegments } ||
            extension in docsExtensions) {
            return Category.README_DOCS
        }
        if (segments.any { it in sourceSegments } || extension in sourceExtensions) {
            return Category.SOURCE
        }
        return Category.OTHER
    }

    private fun isGeneratedOrHeavy(path: String): Boolean =
        path.lowercase(Locale.US).split('/').any { it in generatedSegments }

    fun build(pathMap: WorkspaceAgentReachGitHub.RepositoryPathMap): Index {
        require(pathMap.entries.size <= MAX_INDEX_ENTRIES) {
            "GitHub repository index exceeds Agent Reach entry bound"
        }
        require(safeSha.matches(pathMap.commitSha)) {
            "GitHub repository index commit SHA is invalid"
        }

        var pathChars = 0
        var excluded = 0
        val entries = buildList {
            pathMap.entries.forEach { raw ->
                validatePath(raw.path)
                require(safeSha.matches(raw.sha)) {
                    "GitHub repository index entry SHA is invalid"
                }
                if (isGeneratedOrHeavy(raw.path)) {
                    excluded++
                    return@forEach
                }
                pathChars += raw.path.length
                require(pathChars <= MAX_INDEX_PATH_CHARS) {
                    "GitHub repository index path budget exceeded"
                }
                add(
                    Entry(
                        path = raw.path,
                        kind = raw.kind,
                        objectSha = raw.sha,
                        size = raw.size,
                        category = classify(raw.path),
                    )
                )
            }
        }
        return Index(
            commitSha = pathMap.commitSha.lowercase(Locale.US),
            entries = entries.sortedBy { it.path.lowercase(Locale.US) },
            observedEntries = pathMap.entries.size,
            excludedGeneratedEntries = excluded,
        )
    }
}
