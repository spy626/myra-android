package com.myra.assistant.ui.workspace

/** Deterministic client-side mirror of the broker's C2 write bounds. */
internal object WorkspaceGitHubWritePolicy {
    const val MAX_FILES = 12
    const val MAX_FILE_BYTES = 220_000
    const val MAX_TOTAL_BYTES = 650_000

    private val sha = Regex("[0-9a-fA-F]{40,64}")
    private val privateKey = Regex("-----BEGIN (?:RSA )?PRIVATE KEY-----")
    private val githubToken = Regex("\\b(?:github_pat_[A-Za-z0-9_]{20,}|gh[oprsu]_[A-Za-z0-9]{20,})\\b")

    data class FileChange(val path: String, val content: String)
    data class CommitPlan(
        val expectedHead: String,
        val message: String,
        val files: List<FileChange>,
    )
    data class PullRequestPlan(val title: String, val body: String)

    fun requireExpectedHead(value: String): String {
        val clean = value.trim().lowercase()
        require(sha.matches(clean)) { "Expected feature-branch head is invalid" }
        return clean
    }

    fun requirePath(value: String): String {
        val clean = value.trim().trimStart('/')
        require(clean.isNotBlank() && clean.length <= 1_024 && !clean.endsWith("/")) {
            "GitHub write path is invalid"
        }
        val parts = clean.split('/')
        require(parts.all { part ->
            part.isNotBlank() && part != "." && part != ".." && part.length <= 255 &&
                part.none { it.isISOControl() }
        }) { "GitHub write path contains an unsafe segment" }

        val lower = clean.lowercase()
        val base = parts.last().lowercase()
        require(parts.none { it.equals(".git", ignoreCase = true) } &&
            lower != ".github/workflows" &&
            !lower.startsWith(".github/workflows/") &&
            base != ".env" &&
            !base.startsWith(".env.") &&
            base !in setOf("id_rsa", "id_ed25519") &&
            !base.endsWith(".pem") &&
            !base.endsWith(".key") &&
            !base.endsWith(".p12") &&
            !base.endsWith(".pfx")) {
            "Sensitive or workflow paths are blocked by LYRA connector policy"
        }
        return clean
    }

    fun requireContent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "GitHub write file exceeds the size bound" }
        require(!value.contains('\u0000') &&
            !privateKey.containsMatchIn(value) &&
            !githubToken.containsMatchIn(value)) {
            "Potential secret material is blocked by LYRA connector policy"
        }
        return value
    }

    fun requireMessage(value: String): String {
        val clean = value.trim()
        require(clean.isNotBlank() && clean.length <= 180 &&
            clean.filterNot { it == '\n' }.none { it.isISOControl() }) {
            "GitHub commit message is invalid"
        }
        return clean
    }

    fun commitPlan(
        expectedHead: String,
        message: String,
        files: List<FileChange>,
    ): CommitPlan {
        require(files.size in 1..MAX_FILES) { "GitHub write file count is outside the C2 bound" }
        var total = 0
        val seen = mutableSetOf<String>()
        val cleanFiles = files.map { change ->
            val path = requirePath(change.path)
            require(seen.add(path.lowercase())) { "GitHub write contains duplicate paths" }
            val content = requireContent(change.content)
            total += content.toByteArray(Charsets.UTF_8).size
            require(total <= MAX_TOTAL_BYTES) { "GitHub write exceeds the total size bound" }
            FileChange(path, content)
        }
        return CommitPlan(
            expectedHead = requireExpectedHead(expectedHead),
            message = requireMessage(message),
            files = cleanFiles,
        )
    }

    fun pullRequestPlan(title: String, body: String): PullRequestPlan {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        require(cleanTitle.isNotBlank() && cleanTitle.length <= 180 &&
            cleanTitle.none { it.isISOControl() }) {
            "GitHub pull request title is invalid"
        }
        require(cleanBody.length <= 12_000 && !cleanBody.contains('\u0000')) {
            "GitHub pull request body is invalid"
        }
        return PullRequestPlan(cleanTitle, cleanBody)
    }
}
