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

    data class RetentionAudit(
        val currentPackageSha256: List<String>,
        val rollbackPackageSha256: List<String>,
        val reclaimablePackageSha256: List<String>,
        val ignoredEntryCount: Int,
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

    private fun rollbackPointJson(
        point: WorkspaceSkillCatalog.RollbackPoint,
    ): JSONObject =
        JSONObject()
            .put("name", point.name)
            .put("description", point.description)
            .put("contentSha256", point.contentSha256)
            .put("packageSha256", point.packageSha256)
            .put("permissionSha256", point.permissionSha256)
            .put("provenance", provenanceJson(point.provenance))
            .put("installedAtMs", point.installedAtMs)

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
            .put(
                "rollbackPoint",
                entry.rollbackPoint?.let(::rollbackPointJson) ?: JSONObject.NULL,
            )

    private fun parseRollbackPoint(
        root: JSONObject,
        currentName: String,
        currentPackageSha256: String,
    ): WorkspaceSkillCatalog.RollbackPoint {
        val name = root.getString("name")
        require(NAME.matches(name) && name == currentName) {
            "Stored rollback skill name is invalid"
        }
        val description = root.getString("description")
        require(description.isNotBlank() && description.length <= 1_024 &&
            description.none(Char::isISOControl)) {
            "Stored rollback description is invalid"
        }
        val content = root.getString("contentSha256")
        val packageHash = root.getString("packageSha256")
        val permissions = root.getString("permissionSha256")
        require(SHA.matches(content) && SHA.matches(packageHash) &&
            SHA.matches(permissions) && packageHash != currentPackageSha256) {
            "Stored rollback hash is invalid"
        }
        val provenanceJson = root.getJSONObject("provenance")
        val origin = runCatching {
            WorkspaceSkillContract.Origin.valueOf(provenanceJson.getString("origin"))
        }.getOrElse { throw IllegalArgumentException("Stored rollback origin is invalid") }
        val provenance = WorkspaceSkillContract.Provenance(
            origin = origin,
            sourceUrl = provenanceJson.optString("sourceUrl")
                .takeIf { it.isNotBlank() && it != "null" },
            pinnedRevision = provenanceJson.optString("pinnedRevision")
                .takeIf { it.isNotBlank() && it != "null" },
        )
        val installedAt = root.getLong("installedAtMs")
        require(installedAt >= 0L) { "Stored rollback timestamp is invalid" }
        return WorkspaceSkillCatalog.RollbackPoint(
            name, description, content, packageHash, permissions, provenance, installedAt)
    }

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
        val rollbackPoint =
            if (!root.has("rollbackPoint") || root.isNull("rollbackPoint")) null
            else parseRollbackPoint(
                root.getJSONObject("rollbackPoint"), name, packageHash)
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
            rollbackPoint = rollbackPoint,
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

    /**
     * H15 read-only retention audit.
     *
     * Catalog references remain authoritative: active packages and each skill's one rollback point
     * are protected. Unknown, malformed, symlink, or non-directory entries are reported only and are
     * never deletion candidates.
     */
    @Synchronized fun retentionAudit(): RetentionAudit {
        val catalog = readCatalog()
        val current = catalog.entries.map { it.packageSha256 }.distinct().sorted()
        val rollback = catalog.entries.mapNotNull { it.rollbackPoint?.packageSha256 }
            .distinct().sorted()
        val referenced = (current + rollback).toSet()
        val parent = packagesRoot()
        val reclaimable = mutableListOf<String>()
        var ignored = 0

        parent.listFiles().orEmpty()
            .sortedBy { it.name }
            .forEach { child ->
                if (child.name in referenced) return@forEach
                if (!SHA.matches(child.name) ||
                    Files.isSymbolicLink(child.toPath()) ||
                    !child.isDirectory) {
                    ignored += 1
                    return@forEach
                }
                val canonical = runCatching { child.canonicalFile }.getOrNull()
                if (canonical == null || canonical.parentFile != parent) {
                    ignored += 1
                    return@forEach
                }
                reclaimable += child.name
            }

        return RetentionAudit(
            currentPackageSha256 = current,
            rollbackPackageSha256 = rollback,
            reclaimablePackageSha256 = reclaimable,
            ignoredEntryCount = ignored,
        )
    }

    private fun deleteAuditedPackages(packageSha256: List<String>): List<String> {
        val parent = packagesRoot()
        val deleted = mutableListOf<String>()
        packageSha256.distinct().sorted().forEach { hash ->
            require(SHA.matches(hash)) { "Invalid reclaimable skill package hash" }

            // Re-read authoritative catalog before each deletion. A package that became current or
            // rollback-protected after the audit is skipped rather than deleted.
            val fresh = readCatalog()
            val referenced = buildSet {
                fresh.entries.forEach { entry ->
                    add(entry.packageSha256)
                    entry.rollbackPoint?.let { add(it.packageSha256) }
                }
            }
            if (hash in referenced) return@forEach

            val child = File(parent, hash)
            if (!child.exists() ||
                Files.isSymbolicLink(child.toPath()) ||
                !child.isDirectory) return@forEach
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != parent) return@forEach

            deleteTree(canonical)
            if (!canonical.exists()) deleted += hash
        }
        return deleted
    }

    /**
     * H14 automatic post-update hygiene. This never needs provider/model authority and only removes
     * the same catalog-unreferenced package directories surfaced by the H15 audit.
     */
    @Synchronized fun pruneUnreferencedPackages(): List<String> =
        deleteAuditedPackages(retentionAudit().reclaimablePackageSha256)

    /**
     * H15 explicit user cleanup. The confirmation is bound to the exact audit snapshot: if catalog
     * references or the eligible package set changed after review, cleanup fails closed and the user
     * must audit again.
     */
    @Synchronized fun cleanupAuditedPackages(expected: RetentionAudit): List<String> {
        val fresh = retentionAudit()
        require(fresh == expected) {
            "Skill storage audit changed; review the current storage audit again"
        }
        return deleteAuditedPackages(expected.reclaimablePackageSha256)
    }

    /**
     * Package cleanup is storage hygiene, never mutation authority. Once a catalog switch has been
     * verified, cleanup failure must not make callers believe the already-committed update failed.
     */
    private fun pruneAfterSuccessfulMutation() {
        runCatching { pruneUnreferencedPackages() }
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

    /**
     * H7 admission path for a genuinely new local skill name.
     *
     * It deliberately refuses to reuse install() as an update/re-enable operation. A name already
     * present in the catalog must go through the separate update flow.
     */
    @Synchronized fun installNew(
        skill: WorkspaceSkillContract.ParsedSkill,
        packageFiles: Map<String, ByteArray>,
        approval: WorkspaceSkillCatalog.ApprovalRequest,
        approvedToken: String,
        installedAtMs: Long,
    ): Installed {
        val before = readCatalog()
        require(before.entries.none { it.name == skill.name }) {
            "A skill with this name is already installed; use the separate update flow"
        }
        val installed = install(
            skill = skill,
            packageFiles = packageFiles,
            approval = approval,
            approvedToken = approvedToken,
            installedAtMs = installedAtMs,
        )
        require(
            installed.entry.state == WorkspaceSkillCatalog.State.INSTALLED_DISABLED &&
                installed.entry.enabledAtMs == null &&
                installed.entry.enableReadinessSha256 == null &&
                installed.entry.enableEnvironmentSha256 == null &&
                installed.entry.enableBindingSha256 == null
        ) { "Newly installed skill unexpectedly gained activation authority" }
        return installed
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
        val reopened = load(name)
        pruneAfterSuccessfulMutation()
        return reopened
    }

    /**
     * H11 explicit human-approved immutable package replacement.
     *
     * This path is intentionally separate from evidence-backed overlay promotion. It still uses the
     * same single local store, same non-widening permission policy, append-only content-addressed
     * packages and atomic catalog pointer switch. The previous package is retained for rollback.
     */
    @Synchronized fun updateApprovedPackage(
        name: String,
        candidate: WorkspaceSkillUpdatePreview.Candidate,
        request: WorkspaceSkillApprovedUpdate.Request,
        approvedToken: String,
        updatedAtMs: Long,
    ): Installed {
        require(NAME.matches(name) && candidate.skill.name == name) {
            "Skill update name does not match the installed skill"
        }

        val current = load(name)
        val packageFiles = candidate.packageFiles.mapValues { it.value.copyOf() }
        val snapshot = WorkspaceSkillCatalog.snapshot(candidate.skill, packageFiles)
        val candidateApproval =
            WorkspaceSkillCatalog.approvalRequest(candidate.skill, snapshot)
        require(
            snapshot == candidate.snapshot &&
                candidateApproval.permissionSha256 == candidate.permissionSha256
        ) { "Skill update candidate no longer matches the reviewed package" }

        val updated = WorkspaceSkillApprovedUpdate.updatedEntry(
            current = current,
            candidate = candidate,
            request = request,
            approvedToken = approvedToken,
            updatedAtMs = updatedAtMs,
        )

        val before = readCatalog()
        val currentEntry = before.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Skill is not installed")
        require(currentEntry == current.entry) {
            "Skill catalog changed after update approval; preview again"
        }

        // Append-only content-addressed write. The previous immutable package remains untouched.
        writePackage(snapshot, packageFiles)

        // Re-open and hash exactly what landed on disk before the catalog pointer changes.
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
        val persistedApproval =
            WorkspaceSkillCatalog.approvalRequest(persistedSkill, persistedSnapshot)
        require(
            persistedSkill.contentSha256 == candidate.skill.contentSha256 &&
                persistedSnapshot == candidate.snapshot &&
                persistedApproval.permissionSha256 == candidate.permissionSha256
        ) { "Persisted skill update package did not match the human-approved candidate" }

        val after = WorkspaceSkillCatalog.Catalog(
            before.entries.map { if (it.name == name) updated else it }
                .sortedBy { it.name }
        )
        writeCatalog(after)

        val reopened = load(name)
        require(
            reopened.entry.state == WorkspaceSkillCatalog.State.INSTALLED_DISABLED &&
                reopened.entry.enabledAtMs == null &&
                reopened.entry.enableReadinessSha256 == null &&
                reopened.entry.enableEnvironmentSha256 == null &&
                reopened.entry.enableBindingSha256 == null
        ) { "Updated skill unexpectedly retained activation authority" }
        pruneAfterSuccessfulMutation()
        return reopened
    }

    /**
     * H13 exact human-approved rollback.
     *
     * Both the active package and recorded rollback package are re-opened and re-hashed before the
     * catalog pointer changes. No package directory is deleted. The replaced active version becomes
     * the next one-step rollback point and restored activation always starts disabled.
     */
    @Synchronized fun rollbackApproved(
        name: String,
        request: WorkspaceSkillApprovedRollback.Request,
        approvedToken: String,
        rolledBackAtMs: Long,
    ): Installed {
        require(NAME.matches(name)) { "Invalid skill name" }

        // Fresh package verification happens inside these two loads.
        val current = load(name)
        val rollback = loadRollback(name)

        val restored = WorkspaceSkillApprovedRollback.rolledBackEntry(
            current = current,
            rollback = rollback,
            request = request,
            approvedToken = approvedToken,
            rolledBackAtMs = rolledBackAtMs,
        )

        val before = readCatalog()
        val currentEntry = before.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Skill is not installed")
        require(currentEntry == current.entry) {
            "Skill catalog changed after rollback approval; preview again"
        }

        // Revalidate the exact retained bytes immediately before the catalog switch.
        val verifiedTarget = loadRollback(name)
        WorkspaceSkillApprovedRollback.validate(
            WorkspaceSkillApprovedRollback.Prepared(
                request = request,
                approvalSummary = "",
            ),
            current = current,
            rollback = verifiedTarget,
        )

        val after = WorkspaceSkillCatalog.Catalog(
            before.entries.map { if (it.name == name) restored else it }
                .sortedBy { it.name }
        )
        writeCatalog(after)

        val reopened = load(name)
        require(
            reopened.entry.packageSha256 == rollback.entry.packageSha256 &&
                reopened.entry.contentSha256 == rollback.entry.contentSha256 &&
                reopened.entry.permissionSha256 == rollback.entry.permissionSha256 &&
                reopened.entry.provenance == rollback.entry.provenance &&
                reopened.entry.state == WorkspaceSkillCatalog.State.INSTALLED_DISABLED &&
                reopened.entry.enabledAtMs == null &&
                reopened.entry.enableReadinessSha256 == null &&
                reopened.entry.enableEnvironmentSha256 == null &&
                reopened.entry.enableBindingSha256 == null &&
                reopened.entry.rollbackPoint ==
                    WorkspaceSkillCatalog.rollbackPoint(current.entry)
        ) { "Rolled-back skill did not preserve the exact approved disabled transition" }
        return reopened
    }

    /**
     * H17 exact human-approved uninstall.
     *
     * Current and retained rollback packages are freshly re-opened and verified before the catalog
     * entry is removed. Package directories are intentionally retained; H15 remains the only explicit
     * user cleanup authority for catalog-unreferenced immutable packages.
     */
    @Synchronized fun uninstallApproved(
        name: String,
        request: WorkspaceSkillApprovedUninstall.Request,
        approvedToken: String,
    ) {
        require(NAME.matches(name)) { "Invalid skill name" }

        val current = load(name)
        val rollback = if (current.entry.rollbackPoint != null) loadRollback(name) else null
        val dependencyImpact = WorkspaceSkillUninstallDependencyGuard.analyze(
            targetSkillName = name,
            installedSkills = listVerified(),
        )
        WorkspaceSkillUninstallDependencyGuard.requireSafe(dependencyImpact)
        WorkspaceSkillApprovedUninstall.validate(
            current = current,
            rollback = rollback,
            request = request,
            approvedToken = approvedToken,
        )

        val before = readCatalog()
        val currentEntry = before.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Skill is not installed")
        require(currentEntry == current.entry) {
            "Skill catalog changed after uninstall approval; preview again"
        }

        // Freshly verify the exact retained bytes immediately before removing catalog authority.
        val verifiedCurrent = load(name)
        val verifiedRollback =
            if (verifiedCurrent.entry.rollbackPoint != null) loadRollback(name) else null
        WorkspaceSkillApprovedUninstall.validate(
            current = verifiedCurrent,
            rollback = verifiedRollback,
            request = request,
            approvedToken = approvedToken,
        )
        WorkspaceSkillUninstallDependencyGuard.requireSafe(
            WorkspaceSkillUninstallDependencyGuard.analyze(
                targetSkillName = name,
                installedSkills = listVerified(),
            )
        )

        val after = WorkspaceSkillCatalog.Catalog(
            before.entries.filterNot { it.name == name }.sortedBy { it.name }
        )
        writeCatalog(after)

        require(readCatalog().entries.none { it.name == name }) {
            "Skill uninstall catalog removal could not be verified"
        }
        require(packageDir(verifiedCurrent.entry.packageSha256).isDirectory) {
            "Uninstall unexpectedly removed the current immutable package"
        }
        verifiedRollback?.let {
            require(packageDir(it.entry.packageSha256).isDirectory) {
                "Uninstall unexpectedly removed the rollback immutable package"
            }
        }
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

        val declaredDependencies = installed.skill.manifest.dependencySkills
        if (declaredDependencies.isNotEmpty()) {
            val freshByName = listVerified().associateBy { it.entry.name }
            val missing = declaredDependencies - freshByName.keys
            require(missing.isEmpty()) {
                "Skill enablement has missing dependencies: " +
                    missing.sorted().joinToString(",")
            }
            val disabled = declaredDependencies.filter {
                freshByName[it]?.entry?.state != WorkspaceSkillCatalog.State.ENABLED
            }.toSortedSet()
            require(disabled.isEmpty()) {
                "Skill enablement requires enabled dependencies: " +
                    disabled.joinToString(",")
            }
        }

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

    @Synchronized fun disable(
        name: String,
        request: WorkspaceSkillDisableApproval.Request,
        approvedToken: String,
    ): Installed {
        require(NAME.matches(name)) { "Invalid skill name" }
        val installed = load(name)
        WorkspaceSkillDisableApproval.validate(installed, request, approvedToken)
        val updated = WorkspaceSkillEnablement.disabledEntry(installed.entry)
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

    /**
     * H12 read-only verification of the one retained previous immutable version.
     */
    @Synchronized fun loadRollback(name: String): Installed {
        require(NAME.matches(name)) { "Invalid skill name" }
        val current = load(name)
        val point = current.entry.rollbackPoint
            ?: throw IllegalArgumentException("No verified rollback version is recorded")
        val rollbackEntry = WorkspaceSkillCatalog.Entry(
            name = point.name,
            description = point.description,
            contentSha256 = point.contentSha256,
            packageSha256 = point.packageSha256,
            permissionSha256 = point.permissionSha256,
            provenance = point.provenance,
            installedAtMs = point.installedAtMs,
            state = WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
        )
        val verified = verifyEntry(rollbackEntry)
        require(verified.entry.name == current.entry.name &&
            verified.entry.packageSha256 != current.entry.packageSha256) {
            "Rollback package identity is invalid"
        }
        return verified
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
