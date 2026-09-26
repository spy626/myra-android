package com.myra.assistant.ui.workspace

import org.json.JSONObject
import java.net.IDN
import java.security.MessageDigest
import java.util.Locale

/**
 * Slice H admission contract for instruction-only LYRA skills.
 *
 * Parsing never installs/enables a skill. Imported content stays immutable and untrusted until a
 * later catalog/store layer receives explicit user approval. Executable assets are rejected here.
 */
internal object WorkspaceSkillContract {
    private const val MAX_SKILL_MD_CHARS = 64_000
    private const val MAX_FRONTMATTER_CHARS = 12_000
    private const val MAX_MANIFEST_CHARS = 16_000
    private const val MAX_DESCRIPTION_CHARS = 1_024
    private const val MAX_BODY_CHARS = 52_000
    private const val MAX_PACKAGE_FILES = 64
    private const val MAX_PATH_CHARS = 240
    private const val MAX_LIST_ITEMS = 48

    enum class Origin { USER_SUPPLIED, GITHUB_PINNED, LOCAL_DERIVED }
    enum class SourceSharing { NONE, BOUNDED }
    enum class MemoryAccess { NONE, READ, READ_WRITE }

    data class Provenance(
        val origin: Origin,
        val sourceUrl: String? = null,
        val pinnedRevision: String? = null,
    )

    data class Manifest(
        val version: String? = null,
        val author: String? = null,
        val requiredLyraVersion: String? = null,
        val allowedTools: Set<String> = emptySet(),
        val requiredCapabilities: Set<String> = emptySet(),
        val networkDomains: Set<String> = emptySet(),
        val sourceSharing: SourceSharing = SourceSharing.NONE,
        val memoryAccess: MemoryAccess = MemoryAccess.NONE,
        val userInvocable: Boolean = true,
        val modelInvocable: Boolean = false,
        val dependencySkills: Set<String> = emptySet(),
        val maxNestingDepth: Int = 0,
    )

    data class PermissionPreview(
        val tools: List<String>,
        val capabilities: List<String>,
        val networkDomains: List<String>,
        val sourceSharing: SourceSharing,
        val memoryAccess: MemoryAccess,
        val userInvocable: Boolean,
        val modelInvocable: Boolean,
        val dependencies: List<String>,
        val warnings: List<String>,
    )

    data class ParsedSkill(
        val name: String,
        val description: String,
        val declaredLicense: String?,
        val compatibility: String?,
        val body: String,
        val originalSkillMd: String,
        val originalSkillJson: String?,
        val contentSha256: String,
        val provenance: Provenance,
        val manifest: Manifest,
        val hasVerificationGate: Boolean,
        val permissionPreview: PermissionPreview,
    )

    private val skillName = Regex("""[a-z0-9][a-z0-9-]{0,63}""")
    private val token = Regex("""[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}""")
    private val versionText = Regex("""[^\p{Cntrl}]{1,40}""")
    private val publicDomain = Regex(
        """(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+""" +
            """[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"""
    )
    private val commitSha = Regex("""[0-9a-fA-F]{40,64}""")
    private val verificationHeading = Regex(
        """(?im)^#{1,6}\s+(?:verification|verify|exit criteria|acceptance criteria)\b"""
    )

    private val allowedManifestKeys = setOf(
        "version", "author", "requiredLyraVersion", "allowedTools",
        "requiredCapabilities", "networkDomains", "sourceSharing", "memoryAccess",
        "userInvocable", "modelInvocable", "dependencySkills", "maxNestingDepth",
    )
    private val executableManifestKeys = setOf(
        "script", "scripts", "command", "commands", "entrypoint", "executable",
        "shell", "apk", "dex", "jni", "nativeLibrary", "gradlePlugin",
    )
    private val executableExtensions = setOf(
        "sh", "bash", "zsh", "fish", "py", "pyc", "js", "mjs", "cjs", "ts",
        "jar", "apk", "dex", "class", "so", "dll", "dylib", "exe", "bat",
        "cmd", "ps1", "kts", "gradle", "wasm",
    )
    private val allowedAssetExtensions = setOf(
        "md", "txt", "json", "png", "jpg", "jpeg", "webp",
    )

    private data class Frontmatter(
        val values: Map<String, String>,
        val body: String,
    )

    private fun sha256(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEachIndexed { index, value ->
            if (index > 0) digest.update(0)
            digest.update(value.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun unquote(value: String): String {
        val clean = value.trim()
        if (clean.length >= 2 &&
            ((clean.first() == '"' && clean.last() == '"') ||
                (clean.first() == '\'' && clean.last() == '\''))) {
            return clean.substring(1, clean.lastIndex)
        }
        return clean
    }

    private fun parseFrontmatter(skillMd: String): Frontmatter {
        require(skillMd.length in 1..MAX_SKILL_MD_CHARS) {
            "SKILL.md is empty or exceeds the LYRA skill bound"
        }
        val normalized = skillMd.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        require(lines.firstOrNull()?.trim() == "---") {
            "SKILL.md must start with YAML frontmatter"
        }
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: throw IllegalArgumentException("SKILL.md frontmatter is not closed")
        val frontLines = lines.subList(1, end)
        require(frontLines.joinToString("\n").length <= MAX_FRONTMATTER_CHARS) {
            "SKILL.md frontmatter is too large"
        }

        val values = linkedMapOf<String, String>()
        var i = 0
        while (i < frontLines.size) {
            val line = frontLines[i]
            require('\t' !in line) { "Tabs are not allowed in SKILL.md frontmatter" }
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }
            // Nested metadata is intentionally ignored in this first phase.
            if (line.firstOrNull()?.isWhitespace() == true) {
                i++
                continue
            }
            val colon = line.indexOf(':')
            require(colon in 1 until line.lastIndex + 1) {
                "Invalid SKILL.md frontmatter line"
            }
            val key = line.substring(0, colon).trim()
            require(key.matches(Regex("""[A-Za-z][A-Za-z0-9_-]{0,63}"""))) {
                "Invalid SKILL.md frontmatter key"
            }
            require(key !in values) { "Duplicate SKILL.md frontmatter key: $key" }
            val raw = line.substring(colon + 1).trim()

            if (raw == "|" || raw == ">") {
                val block = mutableListOf<String>()
                var j = i + 1
                while (j < frontLines.size &&
                    (frontLines[j].isBlank() ||
                        frontLines[j].firstOrNull()?.isWhitespace() == true)) {
                    block += frontLines[j].trim()
                    j++
                }
                val joined = if (raw == ">") block.joinToString(" ")
                    else block.joinToString("\n")
                values[key] = joined.trim()
                i = j
                continue
            }

            require(!raw.startsWith("&") && !raw.startsWith("*") &&
                !raw.startsWith("!") && key != "<<") {
                "YAML anchors/tags/merge keys are not supported in LYRA skills"
            }
            values[key] = unquote(raw)
            i++
        }

        val body = lines.drop(end + 1).joinToString("\n").trim()
        require(body.isNotBlank() && body.length <= MAX_BODY_CHARS) {
            "SKILL.md body is empty or too large"
        }
        return Frontmatter(values, body)
    }

    private fun parseFrontmatterTools(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        return value.removePrefix("[").removeSuffix("]")
            .split(',', ' ')
            .map { unquote(it).trim() }
            .filter(String::isNotBlank)
            .onEach { requireToken(it, "allowed tool") }
            .toSortedSet()
    }

    private fun requireToken(value: String, label: String): String {
        val clean = value.trim()
        require(token.matches(clean)) { "$label is invalid" }
        return clean
    }

    private fun jsonString(root: JSONObject, key: String): String? =
        if (!root.has(key) || root.isNull(key)) null
        else root.getString(key).trim().also {
            require(versionText.matches(it)) { "$key is invalid" }
        }

    private fun jsonStrings(root: JSONObject, key: String): Set<String> {
        if (!root.has(key)) return emptySet()
        val array = root.getJSONArray(key)
        require(array.length() <= MAX_LIST_ITEMS) { "$key has too many values" }
        return buildSet {
            for (i in 0 until array.length()) {
                val value = array.getString(i).trim()
                add(requireToken(value, key))
            }
        }
    }

    private fun networkDomains(root: JSONObject): Set<String> {
        if (!root.has("networkDomains")) return emptySet()
        val array = root.getJSONArray("networkDomains")
        require(array.length() <= MAX_LIST_ITEMS) { "networkDomains has too many values" }
        return buildSet {
            for (i in 0 until array.length()) {
                val raw = array.getString(i).trim()
                require("://" !in raw && '/' !in raw && ':' !in raw &&
                    '*' !in raw && '@' !in raw) {
                    "Skill network domains must be exact public hostnames"
                }
                val ascii = runCatching { IDN.toASCII(raw.trimEnd('.')) }
                    .getOrElse { throw IllegalArgumentException("Skill network domain is invalid") }
                    .lowercase(Locale.US)
                require(publicDomain.matches(ascii) &&
                    !WorkspaceAgentReachPolicy.isBlockedHost(ascii)) {
                    "Skill network domain is not a public hostname"
                }
                add(ascii)
            }
        }
    }

    private fun parseManifest(skillJson: String?, frontmatterTools: Set<String>): Manifest {
        if (skillJson.isNullOrBlank()) {
            return Manifest(allowedTools = frontmatterTools)
        }
        require(skillJson.length <= MAX_MANIFEST_CHARS) { "skill.json is too large" }
        val root = runCatching { JSONObject(skillJson) }
            .getOrElse { throw IllegalArgumentException("skill.json is invalid JSON") }
        val keys = root.keys().asSequence().toSet()
        require(keys.none { it in executableManifestKeys }) {
            "Executable skill manifest fields are disabled in this LYRA phase"
        }
        require(keys.all { it in allowedManifestKeys }) {
            "skill.json contains unsupported fields"
        }

        val tools = jsonStrings(root, "allowedTools") + frontmatterTools
        val capabilities = jsonStrings(root, "requiredCapabilities")
        val dependencies = jsonStrings(root, "dependencySkills").onEach {
            require(skillName.matches(it)) { "dependencySkills contains an invalid skill name" }
        }.toSet()

        val sourceSharing = root.optString("sourceSharing", "NONE")
            .trim().uppercase(Locale.US).let {
                runCatching { SourceSharing.valueOf(it) }
                    .getOrElse { throw IllegalArgumentException("sourceSharing is invalid") }
            }
        val memoryAccess = root.optString("memoryAccess", "NONE")
            .trim().uppercase(Locale.US).let {
                runCatching { MemoryAccess.valueOf(it) }
                    .getOrElse { throw IllegalArgumentException("memoryAccess is invalid") }
            }
        val nesting = root.optInt("maxNestingDepth", 0)
        require(nesting in 0..3) { "maxNestingDepth must be 0..3" }

        return Manifest(
            version = jsonString(root, "version"),
            author = jsonString(root, "author"),
            requiredLyraVersion = jsonString(root, "requiredLyraVersion"),
            allowedTools = tools,
            requiredCapabilities = capabilities,
            networkDomains = networkDomains(root),
            sourceSharing = sourceSharing,
            memoryAccess = memoryAccess,
            userInvocable = if (root.has("userInvocable"))
                root.getBoolean("userInvocable") else true,
            modelInvocable = if (root.has("modelInvocable"))
                root.getBoolean("modelInvocable") else false,
            dependencySkills = dependencies,
            maxNestingDepth = nesting,
        )
    }

    private fun validateProvenance(provenance: Provenance) {
        when (provenance.origin) {
            Origin.USER_SUPPLIED -> {
                require(provenance.pinnedRevision == null) {
                    "User-supplied skills do not claim a GitHub pinned revision"
                }
            }
            Origin.GITHUB_PINNED -> {
                val url = provenance.sourceUrl
                    ?: throw IllegalArgumentException("Pinned GitHub skill needs a source URL")
                val target = WorkspaceAgentReachPolicy.parse(url)
                require(target.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
                    "Pinned skill source must be GitHub"
                }
                require(commitSha.matches(provenance.pinnedRevision.orEmpty())) {
                    "Pinned GitHub skill needs an immutable commit SHA"
                }
            }
            Origin.LOCAL_DERIVED -> {
                val url = provenance.sourceUrl
                val revision = provenance.pinnedRevision
                require((url == null) == (revision == null)) {
                    "Locally derived skill ancestry must include both source URL and revision or neither"
                }
                if (url != null) {
                    val target = WorkspaceAgentReachPolicy.parse(url)
                    require(target.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
                        "Locally derived upstream source must be GitHub when ancestry is recorded"
                    }
                    require(commitSha.matches(revision.orEmpty())) {
                        "Locally derived GitHub ancestry needs the immutable upstream revision"
                    }
                }
            }
        }
    }

    fun validatePackagePaths(paths: Collection<String>) {
        require(paths.isNotEmpty() && paths.size <= MAX_PACKAGE_FILES) {
            "Skill package file count is invalid"
        }
        val normalized = paths.map { it.trim().replace('\\', '/') }
        require(normalized.toSet().size == normalized.size) { "Duplicate skill package paths" }
        require(normalized.any { it == "SKILL.md" }) { "Skill package must contain SKILL.md" }

        normalized.forEach { path ->
            require(path.isNotBlank() && path.length <= MAX_PATH_CHARS &&
                !path.startsWith('/') && !path.contains("//")) {
                "Skill package contains an invalid path"
            }
            val segments = path.split('/')
            require(segments.none { it.isBlank() || it == "." || it == ".." }) {
                "Skill package contains path traversal"
            }
            val lower = path.lowercase(Locale.US)
            require(!lower.startsWith("scripts/") && !lower.contains("/scripts/")) {
                "Skill scripts are disabled in this LYRA phase"
            }
            val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
            require(ext !in executableExtensions) {
                "Executable skill asset is disabled: $path"
            }
            if (path != "SKILL.md" && path != "skill.json") {
                require(
                    lower.startsWith("references/") ||
                        lower.startsWith("examples/") ||
                        lower.startsWith("assets/")
                ) { "Unsupported skill package path: $path" }
                require(ext in allowedAssetExtensions) {
                    "Unsupported skill asset type: $path"
                }
            }
        }
    }

    fun parse(
        skillMd: String,
        skillJson: String? = null,
        provenance: Provenance = Provenance(Origin.USER_SUPPLIED),
        packagePaths: Collection<String> = listOf("SKILL.md"),
    ): ParsedSkill {
        validateProvenance(provenance)
        validatePackagePaths(packagePaths)
        val normalizedPaths = packagePaths.map { it.trim().replace('\\', '/') }
        val declaresManifest = normalizedPaths.any { it == "skill.json" }
        require(declaresManifest == (skillJson != null)) {
            "skill.json package path and bytes must be present together"
        }
        if (skillJson != null) {
            require(skillJson.isNotBlank()) { "skill.json is empty" }
        }
        val front = parseFrontmatter(skillMd)
        val name = front.values["name"]?.trim().orEmpty()
        require(skillName.matches(name)) {
            "Skill name must be lowercase letters/numbers/hyphens and at most 64 chars"
        }
        val description = front.values["description"]?.trim().orEmpty()
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_CHARS) {
            "Skill description is required and must be at most 1024 chars"
        }
        val declaredLicense = front.values["license"]?.trim()?.takeIf(String::isNotBlank)
        val compatibility = front.values["compatibility"]?.trim()?.takeIf(String::isNotBlank)
        declaredLicense?.let {
            require(versionText.matches(it)) { "Skill license declaration is invalid" }
        }
        compatibility?.let {
            require(it.length <= 256 && it.none(Char::isISOControl)) {
                "Skill compatibility declaration is invalid"
            }
        }

        val manifest = parseManifest(
            skillJson,
            parseFrontmatterTools(front.values["allowed-tools"]),
        )
        require(name !in manifest.dependencySkills) { "Skill cannot depend on itself" }
        val hasVerification = verificationHeading.containsMatchIn(front.body)

        val warnings = buildList {
            add("Skill is parsed only; installation/enabling still requires explicit approval.")
            add("Scripts, APK/DEX/JNI/native/shell execution are disabled.")
            if (!hasVerification) add(
                "No explicit verification/exit-criteria section was detected; evaluate before enabling.")
            if (manifest.modelInvocable) add(
                "Skill requests model invocation; this does not grant automatic enablement.")
            if (manifest.sourceSharing != SourceSharing.NONE) add(
                "Skill requests bounded project-source sharing and needs a separate source gate.")
            if (manifest.memoryAccess != MemoryAccess.NONE) add(
                "Skill requests memory access and needs an independent memory permission gate.")
            if (manifest.networkDomains.isNotEmpty()) add(
                "Skill requests network access only to the exact declared domains.")
        }
        val preview = PermissionPreview(
            tools = manifest.allowedTools.sorted(),
            capabilities = manifest.requiredCapabilities.sorted(),
            networkDomains = manifest.networkDomains.sorted(),
            sourceSharing = manifest.sourceSharing,
            memoryAccess = manifest.memoryAccess,
            userInvocable = manifest.userInvocable,
            modelInvocable = manifest.modelInvocable,
            dependencies = manifest.dependencySkills.sorted(),
            warnings = warnings,
        )

        return ParsedSkill(
            name = name,
            description = description,
            declaredLicense = declaredLicense,
            compatibility = compatibility,
            body = front.body,
            originalSkillMd = skillMd,
            originalSkillJson = skillJson,
            contentSha256 = sha256(skillMd, skillJson.orEmpty()),
            provenance = provenance,
            manifest = manifest,
            hasVerificationGate = hasVerification,
            permissionPreview = preview,
        )
    }
}
