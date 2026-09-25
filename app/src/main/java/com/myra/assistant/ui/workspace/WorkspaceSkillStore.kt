package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Private on-device persistence for approved instruction-only skills.
 *
 * Imported package bytes live under a content-addressed immutable directory. Enabling is allowed only
 * after exact readiness approval; this store never invokes or executes a skill. Package/catalog
 * integrity is revalidated on every load.
 */
internal class WorkspaceSkillStore(
    private val root: File,
) {
    data class Installed(
        val entry: WorkspaceSkillCatalog.Entry,
        val skill: WorkspaceSkillContract.ParsedSkill,
        val snapshot: WorkspaceSkillCatalog.PackageSnapshot,
    )

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val LEGACY_SCHEMA_VERSION = 1
        private const val CATALOG_FILE = "catalog.json"
        internal const val APP_DIRECTORY = "workspace-skills"
        private const val PACKAGES_DIR = "packages"
        private const val MAX_CATALOG_BYTES = 512 * 1024L
        private const val MAX_ENTRIES = 128
        private val SHA = Regex("""[0-9a-f]{64}""")
        private val NAME = Regex("""[a-z0-9][a-z0-9-]{0,63}""")
    }

    init {
        canonicalRoot()
    }

    private fun canonicalRoot(): File {
        require(root.mkdirs() || root.isDirectory) { "Skill storage root is unavailable" }
        require(!Files.isSymbolicLink(root.toPath())) { "Skill storage root link is forbidden" }
        return root.canonicalFile
    }

    private fun packagesRoot(): File {
        val base = canonicalRoot()
        val dir = File(base, PACKAGES_DIR)
        require(!Files.isSymbolicLink(dir.toPath())) { "Skill package root link is forbidden" }
        require(dir.mkdirs() || dir.isDirectory) { "Skill package root is unavailable" }
        require(dir.canonicalFile.parentFile == base) { "Skill package root escaped storage" }
        return dir.canonicalFile
    }

    private fun packageDir(packageSha256: String): File {
        require(SHA.matches(packageSha256)) { "Invalid skill package hash" }
        val parent = packagesRoot()
        val dir = File(parent, packageSha256)
        require(!Files.isSymbolicLink(dir.toPath())) { "Skill package link is forbidden" }
        require(dir.canonicalFile.parentFile == parent) { "Skill package escaped storage" }
        return dir.canonicalFile
    }

    private fun safeSegments(path: String): List<String> {
        val clean = path.trim().replace('\\', '/')
        require(clean.isNotBlank() && !clean.startsWith('/') && !clean.contains("//")) {
            "Invalid stored skill path"
        }
        val parts = clean.split('/')
        require(parts.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." &&
                segment.none(Char::isISOControl)
        }) { "Invalid stored skill path" }
        return parts
    }

    private fun resolvePackageFile(packageRoot: File, path: String): File {
        var current = packageRoot
        safeSegments(path).forEach { segment ->
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) {
                "Skill package contains a symbolic link"
            }
            require(current.canonicalFile.toPath().startsWith(packageRoot.canonicalFile.toPath())) {
                "Skill package path escaped storage"
            }
        }
        require(current.canonicalFile != packageRoot.canonicalFile) {
            "Skill package root is not a file"
        }
        return current.canonicalFile
    }

    private fun strictUtf8(bytes: ByteArray, label: String): String =
        runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { throw IllegalArgumentException("$label is not valid UTF-8") }

    private fun provenanceJson(value: WorkspaceSkillContract.Provenance): JSONObject =
        JSONObject()
            .put("origin", value.origin.name)
            .put("sourceUrl", value.sourceUrl ?: JSONObject.NULL)
            .put("pinnedRevision", value.pinnedRevision ?: JSONObject.NULL)

    private fun entryJson(entry: WorkspaceSkillCatalog.Entry): JSONObject =
        JSONObject()
            .put("name", entry.name)
            .put("description", entry.description)
            .put("contentSha256", entry.contentSha256)
            .put("packageSha256", entry.packageSha256)
            .put("permissionSha256", entry.permissionSha256)
            .put("provenance", provenanceJson(entry.provenance))
            .put("installedAtMs", entry.installedAtMs)
            .put("state", entry.state.name)
            .put("enabledAtMs", entry.enabledAtMs ?: JSONObject.NULL)
            .put("enableReadinessSha256", entry.enableReadinessSha256 ?: JSONObject.NULL)
            .put("enableEnvironmentSha256", entry.enableEnvironmentSha256 ?: JSONObject.NULL)
            .put("enableBindingSha256", entry.enableBindingSha256 ?: JSONObject.NULL)

    private fun parseEntry(
        root: JSONObject,
        schemaVersion: Int,
    ): WorkspaceSkillCatalog.Entry {
        val name = root.getString("name")
        require(NAME.matches(name)) { "Stored skill name is invalid" }
        val description = root.getString("description")
        require(description.isNotBlank() && description.length <= 1_024 &&
            description.none(Char::isISOControl)) {
            "Stored skill description is invalid"
        }
        val content = root.getString("contentSha256")
        val packageHash = root.getString("packageSha256")
        val permissions = root.getString("permissionSha256")
        require(SHA.matches(content) && SHA.matches(packageHash) && SHA.matches(permissions)) {
            "Stored skill hash is invalid"
        }
        val provenanceJson = root.getJSONObject("provenance")
        val origin = runCatching {
            WorkspaceSkillContract.Origin.valueOf(provenanceJson.getString("origin"))
        }.getOrElse { throw IllegalArgumentException("Stored skill origin is invalid") }
        val provenance = WorkspaceSkillContract.Provenance(
            origin = origin,
            sourceUrl = provenanceJson.optString("sourceUrl")
                .takeIf { it.isNotBlank() && it != "null" },
            pinnedRevision = provenanceJson.optString("pinnedRevision")
                .takeIf { it.isNotBlank() && it != "null" },
        )
        val installedAt = root.getLong("installedAtMs")
        require(installedAt >= 0L) { "Stored skill timestamp is invalid" }
        val state = runCatching {
            WorkspaceSkillCatalog.State.valueOf(root.getString("state"))
        }.getOrElse { throw IllegalArgumentException("Stored skill state is invalid") }
        val enabledAt = if (!root.has("enabledAtMs") || root.isNull("enabledAtMs"))
            null else root.getLong("enabledAtMs")
        val readiness = if (!root.has("enableReadinessSha256") ||
            root.isNull("enableReadinessSha256")) null
        else root.getString("enableReadinessSha256")
        val environment = if (!root.has("enableEnvironmentSha256") ||
            root.isNull("enableEnvironmentSha256")) null
        else root.getString("enableEnvironmentSha256")
        val binding = if (!root.has("enableBindingSha256") ||
            root.isNull("enableBindingSha256")) null
        else root.getString("enableBindingSha256")
        if (schemaVersion == LEGACY_SCHEMA_VERSION) {
            require(state == WorkspaceSkillCatalog.State.INSTALLED_DISABLED &&
                enabledAt == null && readiness == null && environment == null && binding == null) {
                "Legacy skill catalog cannot contain enabled entries"
            }
        }
        val entry = WorkspaceSkillCatalog.Entry(
            name = name,
            description = description,
            contentSha256 = content,
            packageSha256 = packageHash,
            permissionSha256 = permissions,
            provenance = provenance,
            installedAtMs = installedAt,
            state = state,
            enabledAtMs = enabledAt,
            enableReadinessSha256 = readiness,
            enableEnvironmentSha256 = environment,
            enableBindingSha256 = binding,
        )
        WorkspaceSkillEnablement.validateStoredState(entry)
        return entry
    }

    @Synchronized fun readCatalog(): WorkspaceSkillCatalog.Catalog {
        val base = canonicalRoot()
        val file = File(base, CATALOG_FILE)
        require(!Files.isSymbolicLink(file.toPath())) { "Skill catalog link is forbidden" }
        if (!file.exists()) return WorkspaceSkillCatalog.Catalog()
        require(file.isFile && file.length() in 1..MAX_CATALOG_BYTES) {
            "Skill catalog is unavailable or too large"
        }
        val document = JSONObject(file.readText(Charsets.UTF_8))
        val schemaVersion = document.getInt("schemaVersion")
        require(schemaVersion == LEGACY_SCHEMA_VERSION || schemaVersion == SCHEMA_VERSION) {
            "Unsupported skill catalog schema"
        }
        val array = document.getJSONArray("entries")
        require(array.length() <= MAX_ENTRIES) { "Skill catalog exceeds local entry limit" }
        val entries = (0 until array.length()).map {
            parseEntry(array.getJSONObject(it), schemaVersion)
        }
        require(entries.map { it.name }.toSet().size == entries.size) {
            "Skill catalog contains duplicate names"
        }
        require(entries == entries.sortedBy { it.name }) {
            "Skill catalog ordering is invalid"
        }
        return WorkspaceSkillCatalog.Catalog(entries)
    }

    private fun writeCatalog(catalog: WorkspaceSkillCatalog.Catalog) {
        require(catalog.entries.size <= MAX_ENTRIES) { "Skill catalog exceeds local entry limit" }
        require(catalog.entries == catalog.entries.sortedBy { it.name }) {
            "Skill catalog must be deterministically ordered"
        }
        val base = canonicalRoot()
        val file = File(base, CATALOG_FILE)
        require(!Files.isSymbolicLink(file.toPath())) { "Skill catalog link is forbidden" }
        val array = JSONArray()
        catalog.entries.forEach { array.put(entryJson(it)) }
        val bytes = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("entries", array)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_CATALOG_BYTES) { "Skill catalog is too large" }

        val temp = File(base, ".skill-catalog-${UUID.randomUUID()}.tmp")
        try {
            java.io.FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temp.delete()
        }
    }

    private fun writePackage(
        snapshot: WorkspaceSkillCatalog.PackageSnapshot,
        packageFiles: Map<String, ByteArray>,
    ) {
        val target = packageDir(snapshot.packageSha256)
        if (target.exists()) return
        val parent = packagesRoot()
        val temp = File(parent, ".skill-install-${UUID.randomUUID()}")
        require(temp.mkdir()) { "Could not create temporary skill package" }
        try {
            packageFiles.forEach { (path, rawBytes) ->
                val destination = resolvePackageFile(temp.canonicalFile, path)
                val parentDir = destination.parentFile
                    ?: throw IllegalArgumentException("Skill package parent is unavailable")
                require(parentDir.mkdirs() || parentDir.isDirectory) {
                    "Could not create skill package directory"
                }
                require(!Files.isSymbolicLink(parentDir.toPath())) {
                    "Skill package directory link is forbidden"
                }
                val bytes = rawBytes.copyOf()
                require(bytes.size <= WorkspaceSkillCatalog.MAX_FILE_BYTES) {
                    "Skill package file is too large"
                }
                java.io.FileOutputStream(destination).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath())
            }
        } finally {
            if (temp.exists()) deleteTree(temp)
        }
    }

    private fun deleteTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::deleteTree)
        file.delete()
    }

    private fun readPackageFiles(entry: WorkspaceSkillCatalog.Entry): Map<String, ByteArray> {
        val dir = packageDir(entry.packageSha256)
        require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath())) {
            "Installed skill package is unavailable"
        }
        val found = linkedMapOf<String, ByteArray>()
        var total = 0L

        fun visit(node: File, prefix: String) {
            require(!Files.isSymbolicLink(node.toPath())) {
                "Installed skill package contains a symbolic link"
            }
            node.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                require(!Files.isSymbolicLink(child.toPath())) {
                    "Installed skill package contains a symbolic link"
                }
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                safeSegments(relative)
                if (child.isDirectory) {
                    visit(child, relative)
                } else {
                    require(child.isFile && child.length() <= WorkspaceSkillCatalog.MAX_FILE_BYTES) {
                        "Installed skill file is unavailable or too large"
                    }
                    val bytes = child.readBytes()
                    require(bytes.size <= WorkspaceSkillCatalog.MAX_FILE_BYTES) {
                        "Installed skill file exceeds its bound"
                    }
                    total += bytes.size
                    require(total <= WorkspaceSkillCatalog.MAX_PACKAGE_BYTES) {
                        "Installed skill package exceeds its bound"
                    }
                    found[relative] = bytes
                    require(found.size <= 64) { "Installed skill package has too many files" }
                }
            }
        }
        visit(dir, "")
        WorkspaceSkillContract.validatePackagePaths(found.keys)
        return found
    }

    @Synchronized fun install(
        skill: WorkspaceSkillContract.ParsedSkill,
        packageFiles: Map<String, ByteArray>,
        approval: WorkspaceSkillCatalog.ApprovalRequest,
        approvedToken: String,
        installedAtMs: Long,
    ): Installed {
        val snapshot = WorkspaceSkillCatalog.snapshot(skill, packageFiles)
        val before = readCatalog()
        val after = WorkspaceSkillCatalog.admit(
            before, skill, snapshot, approval, approvedToken, installedAtMs)
        writePackage(snapshot, packageFiles)

        val provisionalEntry = after.entries.first { it.name == skill.name }
        verifyEntry(provisionalEntry)
        if (after != before) writeCatalog(after)
        return load(skill.name)
    }

    @Synchronized fun update(
        name: String,
        candidate: WorkspaceSkillContract.ParsedSkill,
        packageFiles: Map<String, ByteArray>,
        promotion: WorkspaceSkillOverlayPromotion.Promotion,
        request: WorkspaceSkillUpdate.Request,
        approvedToken: String,
        updatedAtMs: Long,
    ): Installed {
        require(NAME.matches(name) && candidate.name == name) {
            "Skill update name does not match the installed skill"
        }
        val current = load(name)
        val snapshot = WorkspaceSkillCatalog.snapshot(candidate, packageFiles)
        val updated = WorkspaceSkillUpdate.updatedEntry(
            current = current,
            candidate = candidate,
            candidateSnapshot = snapshot,
            promotion = promotion,
            request = request,
            approvedToken = approvedToken,
            updatedAtMs = updatedAtMs,
        )

        val before = readCatalog()
        val currentEntry = before.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Skill is not installed")
        require(currentEntry == current.entry) {
            "Skill catalog changed during update; retry from fresh state"
        }

        // Content-addressed package write is append-only for a new hash. The old package directory
        // remains untouched for a separately approval-bound rollback path.
        writePackage(snapshot, packageFiles)

        // Re-open exactly what was persisted before switching the catalog pointer.
        val persistedFiles = readPackageFiles(updated)
        val persistedSkill = WorkspaceSkillContract.parse(
            skillMd = strictUtf8(
                persistedFiles["SKILL.md"]
                    ?: throw IllegalArgumentException("Updated skill is missing SKILL.md"),
                "Updated SKILL.md",
            ),
            skillJson = persistedFiles["skill.json"]?.let {
                strictUtf8(it, "Updated skill.json")
            },
            provenance = updated.provenance,
            packagePaths = persistedFiles.keys,
        )
        val persistedSnapshot = WorkspaceSkillCatalog.snapshot(persistedSkill, persistedFiles)
        require(persistedSkill.contentSha256 == candidate.contentSha256 &&
            persistedSnapshot == snapshot) {
            "Persisted skill update package did not match the approved candidate"
        }

        val after = WorkspaceSkillCatalog.Catalog(
            before.entries.map { if (it.name == name) updated else it }
                .sortedBy { it.name }
        )
        writeCatalog(after)
        return load(name)
    }

    @Synchronized fun enable(
        name: String,
        environment: WorkspaceSkillEnablement.Environment,
        request: WorkspaceSkillEnablement.EnableRequest,
        approvedToken: String,
        enabledAtMs: Long,
    ): Installed {
        require(NAME.matches(name)) { "Invalid skill name" }
        val installed = load(name)
        val updated = WorkspaceSkillEnablement.enabledEntry(
            installed, environment, request, approvedToken, enabledAtMs)
        val before = readCatalog()
        val current = before.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Skill is not installed")
        require(current == installed.entry) {
            "Skill catalog changed during enablement; retry from fresh state"
        }
        val after = WorkspaceSkillCatalog.Catalog(
            before.entries.map { if (it.name == name) updated else it }
                .sortedBy { it.name }
        )
        writeCatalog(after)
        return load(name)
    }

    @Synchronized fun disable(name: String): Installed {
        require(NAME.matches(name)) { "Invalid skill name" }
        val installed = load(name)
        val updated = WorkspaceSkillEnablement.disabledEntry(installed.entry)
        if (updated == installed.entry) return installed
        val before = readCatalog()
        val current = before.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Skill is not installed")
        require(current == installed.entry) {
            "Skill catalog changed during disablement; retry from fresh state"
        }
        val after = WorkspaceSkillCatalog.Catalog(
            before.entries.map { if (it.name == name) updated else it }
                .sortedBy { it.name }
        )
        writeCatalog(after)
        return load(name)
    }

    @Synchronized fun load(name: String): Installed {
        require(NAME.matches(name)) { "Invalid skill name" }
        val entry = readCatalog().entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Skill is not installed")
        return verifyEntry(entry)
    }

    @Synchronized fun listVerified(): List<Installed> =
        readCatalog().entries.map { verifyEntry(it) }

    private fun verifyEntry(entry: WorkspaceSkillCatalog.Entry): Installed {
        val files = readPackageFiles(entry)
        val skillMd = strictUtf8(
            files["SKILL.md"] ?: throw IllegalArgumentException("Installed skill is missing SKILL.md"),
            "Installed SKILL.md",
        )
        val skillJson = files["skill.json"]?.let { strictUtf8(it, "Installed skill.json") }
        val parsed = WorkspaceSkillContract.parse(
            skillMd = skillMd,
            skillJson = skillJson,
            provenance = entry.provenance,
            packagePaths = files.keys,
        )
        val snapshot = WorkspaceSkillCatalog.snapshot(parsed, files)
        val approval = WorkspaceSkillCatalog.approvalRequest(parsed, snapshot)
        require(parsed.name == entry.name &&
            parsed.description == entry.description &&
            parsed.contentSha256 == entry.contentSha256 &&
            snapshot.packageSha256 == entry.packageSha256 &&
            approval.permissionSha256 == entry.permissionSha256) {
            "Installed skill package/catalog integrity check failed"
        }
        return Installed(entry, parsed, snapshot)
    }
}
