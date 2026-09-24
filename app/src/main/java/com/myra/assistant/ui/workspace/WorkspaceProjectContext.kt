package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Ephemeral, local-only project structure index for token-efficient coding context.
 *
 * It is not a memory/database owner and never writes project files. Other-file contents are
 * inspected locally only to derive bounded structure evidence; the provider projection contains
 * paths/relationships only, never those contents.
 */
object WorkspaceProjectContext {
    private const val MAX_INDEX_FILES = 12
    private const val MAX_SCAN_CHARS_PER_FILE = 6_000
    private const val MAX_SYMBOLS_PER_FILE = 8
    private const val MAX_SELECTED_NEIGHBORS = 3

    data class FileEvidence(
        val path: String,
        val sha256: String,
        val symbols: List<String>,
        val references: Set<String>,
        val rawChars: Int,
        val blockedSensitive: Boolean,
    )

    data class Selection(val path: String, val reason: String)

    data class Projection(
        val projectId: String,
        val targetPath: String,
        val projectUpdatedAtMs: Long,
        val eligibleListingSha256: String,
        val indexed: List<FileEvidence>,
        val selected: List<Selection>,
        val rawCharsRead: Int,
        val projectedChars: Int,
    ) {
        val rawCharsAvoided: Int get() = (rawCharsRead - projectedChars).coerceAtLeast(0)

        fun promptNote(): String {
            if (selected.isEmpty()) return ""
            return buildString {
                appendLine("Relevant project structure (metadata only; no other file contents are shared):")
                selected.forEach { appendLine("- ${it.path} — ${it.reason}") }
                append("Use this only to understand nearby structure. Edit only ${targetPath}.")
            }
        }
    }

    private val words = Regex("[A-Za-z0-9_]{3,}")
    private val symbol = Regex(
        """(?m)\b(?:class|interface|object|fun|function|const|let|var|type|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )
    private val quotedPath = Regex(
        """(?i)["']([^"'\n\\]{1,160}\.(?:html|css|js|jsx|ts|tsx|json|md|kt|xml))["']"""
    )
    private val stop = setOf(
        "the", "and", "for", "with", "this", "that", "from", "make", "change", "update",
        "file", "website", "android", "app", "bro", "please", "karo", "karna", "chahiye"
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun terms(value: String): Set<String> = words.findAll(value.lowercase())
        .map { it.value }.filterNot { it in stop }.take(48).toSet()

    private fun sameDir(a: String, b: String): Boolean =
        a.substringBeforeLast('/', "") == b.substringBeforeLast('/', "")

    private fun pathScore(path: String, targetPath: String, queryTerms: Set<String>): Int {
        if (path == targetPath) return Int.MAX_VALUE
        val pathTerms = terms(path.replace('/', ' '))
        var score = pathTerms.count(queryTerms::contains) * 12
        if (sameDir(path, targetPath)) score += 6
        if (path.substringAfterLast('.') == targetPath.substringAfterLast('.')) score += 2
        val targetName = targetPath.substringAfterLast('/').substringBeforeLast('.')
        if (targetName.length >= 3 && path.lowercase().contains(targetName.lowercase())) score += 4
        if (targetPath == "index.html" && path in setOf("style.css", "script.js")) score += 10
        return score
    }

    private fun normalizeReference(raw: String): String =
        raw.substringBefore('#').substringBefore('?').removePrefix("./").trimStart('/')

    private fun references(scan: String, allPaths: Set<String>): Set<String> {
        val basenames = allPaths.groupBy { it.substringAfterLast('/') }
        return quotedPath.findAll(scan).mapNotNull { match ->
            val ref = normalizeReference(match.groupValues[1])
            when {
                ref in allPaths -> ref
                basenames[ref.substringAfterLast('/')]?.size == 1 ->
                    basenames[ref.substringAfterLast('/')]!!.single()
                else -> null
            }
        }.toSet()
    }

    private fun listingSha(paths: List<String>): String = sha256(paths.joinToString("\n"))

    fun build(
        files: WorkspaceFileStore,
        projects: WorkspaceProjectStore,
        projectId: String,
        targetPath: String,
        query: String,
    ): Projection {
        val project = requireNotNull(projects.getProject(projectId)) { "Workspace project is unavailable" }
        val allPaths = files.list(projectId).asSequence()
            .filter { !it.folder && WorkspaceSourceContext.isEligibleProjectPath(it.path) }
            .map { it.path }.distinct().sorted().toList()
        require(targetPath in allPaths) { "Target source is unavailable from current project context" }

        val queryTerms = terms(query + " " + targetPath)
        val scanPaths = allPaths.sortedWith(
            compareByDescending<String> { pathScore(it, targetPath, queryTerms) }.thenBy { it }
        ).take(MAX_INDEX_FILES)
        val allPathSet = allPaths.toSet()

        val indexed = scanPaths.map { path ->
            val text = files.readFile(projectId, path)
            val hash = sha256(text)
            val sensitive = WorkspaceSourceContext.containsPossibleSecret(text)
            if (sensitive) {
                FileEvidence(path, hash, emptyList(), emptySet(), text.length, true)
            } else {
                val scan = text.take(MAX_SCAN_CHARS_PER_FILE)
                val symbols = symbol.findAll(scan).map { it.groupValues[1] }.distinct()
                    .take(MAX_SYMBOLS_PER_FILE).toList()
                FileEvidence(path, hash, symbols, references(scan, allPathSet), text.length, false)
            }
        }

        val target = indexed.firstOrNull { it.path == targetPath }
        val targetRefs = target?.references.orEmpty()
        val targetBase = targetPath.substringAfterLast('/')
        val ranked = indexed.asSequence().filter { it.path != targetPath && !it.blockedSensitive }
            .map { evidence ->
                var score = pathScore(evidence.path, targetPath, queryTerms)
                var reason = "nearby project file"
                when {
                    evidence.path in targetRefs -> {
                        score += 100
                        reason = "referenced by target"
                    }
                    evidence.references.any { it == targetPath || it.substringAfterLast('/') == targetBase } -> {
                        score += 90
                        reason = "references target"
                    }
                    terms(evidence.path + " " + evidence.symbols.joinToString(" "))
                        .any(queryTerms::contains) -> {
                        score += 25
                        reason = "matches task terms"
                    }
                    sameDir(evidence.path, targetPath) -> {
                        score += 8
                        reason = "same source area"
                    }
                }
                evidence to (score to reason)
            }.filter { it.second.first > 0 }
            .sortedWith(compareByDescending<Pair<FileEvidence, Pair<Int, String>>> { it.second.first }
                .thenBy { it.first.path })
            .take(MAX_SELECTED_NEIGHBORS)
            .map { Selection(it.first.path, it.second.second) }
            .toList()

        val note = if (ranked.isEmpty()) "" else buildString {
            appendLine("Relevant project structure (metadata only; no other file contents are shared):")
            ranked.forEach { appendLine("- ${it.path} — ${it.reason}") }
            append("Use this only to understand nearby structure. Edit only ${targetPath}.")
        }

        return Projection(
            projectId = projectId,
            targetPath = targetPath,
            projectUpdatedAtMs = project.updatedAtMs,
            eligibleListingSha256 = listingSha(allPaths),
            indexed = indexed,
            selected = ranked,
            rawCharsRead = indexed.sumOf { it.rawChars },
            projectedChars = note.length,
        )
    }

    fun stillCurrent(
        files: WorkspaceFileStore,
        projects: WorkspaceProjectStore,
        projection: Projection,
    ): Boolean = runCatching {
        val project = requireNotNull(projects.getProject(projection.projectId))
        if (project.updatedAtMs != projection.projectUpdatedAtMs) return@runCatching false
        val currentPaths = files.list(projection.projectId).asSequence()
            .filter { !it.folder && WorkspaceSourceContext.isEligibleProjectPath(it.path) }
            .map { it.path }.distinct().sorted().toList()
        if (listingSha(currentPaths) != projection.eligibleListingSha256) return@runCatching false
        projection.indexed.all { evidence ->
            evidence.path in currentPaths &&
                sha256(files.readFile(projection.projectId, evidence.path)) == evidence.sha256
        }
    }.getOrDefault(false)
}
