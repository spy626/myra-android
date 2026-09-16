package com.myra.assistant.ui.workspace

import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * App-private, project-scoped storage for Workspace metadata.
 *
 * Each project owns exactly one authoritative manifest at:
 *   <projectsRoot>/<projectId>/.lyra/project.json
 *
 * There is intentionally no second database or duplicate recent-project index.
 * Listing/opening is derived from those manifests, so Workspace project truth
 * cannot become another personal-memory owner.
 */
class WorkspaceProjectStore(
    private val projectsRoot: File,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        private const val METADATA_DIR = ".lyra"
        private const val MANIFEST_FILE = "project.json"
        private const val MAX_PROJECT_NAME_LENGTH = 80
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
    }

    init {
        require(projectsRoot.mkdirs() || projectsRoot.isDirectory) {
            "Workspace project root is unavailable"
        }
    }

    fun createProject(rawName: String, type: WorkspaceProjectType): WorkspaceProject {
        val name = normalizeProjectName(rawName)
        val projectId = nextProjectId()
        val projectDir = safeProjectDir(projectId)
        check(projectDir.mkdir()) { "Unable to create Workspace project root" }

        val metadataDir = File(projectDir, METADATA_DIR)
        check(metadataDir.mkdir()) { "Unable to create Workspace metadata directory" }

        val now = nowMillis()
        val project = WorkspaceProject(
            projectId = projectId,
            name = name,
            type = type,
            rootRelativePath = projectId,
            createdAtMs = now,
            updatedAtMs = now,
            lastOpenedAtMs = now,
        )
        writeManifest(project)
        return project
    }

    fun listProjects(): List<WorkspaceProject> {
        val root = canonicalProjectsRoot()
        val children = root.listFiles().orEmpty()
        return children.asSequence()
            .filter { it.isDirectory }
            .mapNotNull { loadProjectFromDirectory(it) }
            .sortedWith(
                compareByDescending<WorkspaceProject> { it.lastOpenedAtMs }
                    .thenByDescending { it.updatedAtMs }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
    }

    fun getProject(projectId: String): WorkspaceProject? =
        runCatching { loadProjectFromDirectory(safeProjectDir(projectId)) }.getOrNull()

    fun markOpened(projectId: String): WorkspaceProject? {
        val existing = getProject(projectId) ?: return null
        val opened = existing.copy(lastOpenedAtMs = nowMillis())
        writeManifest(opened)
        return opened
    }

    fun deleteProject(projectId: String): Boolean {
        val projectDir = runCatching { safeProjectDir(projectId) }.getOrNull() ?: return false
        if (!projectDir.exists()) return false
        if (getProject(projectId) == null) return false
        return deleteTreeSafely(projectDir)
    }

    fun projectRoot(projectId: String): File = safeProjectDir(projectId)

    private fun nextProjectId(): String {
        repeat(8) {
            val candidate = idFactory().trim()
            if (SAFE_ID.matches(candidate) && !safeProjectDir(candidate).exists()) return candidate
        }
        error("Unable to allocate a unique Workspace project ID")
    }

    private fun normalizeProjectName(rawName: String): String {
        val normalized = rawName.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank()) { "Project name is required" }
        require(normalized.length <= MAX_PROJECT_NAME_LENGTH) {
            "Project name must be $MAX_PROJECT_NAME_LENGTH characters or fewer"
        }
        require(normalized.none { it.isISOControl() }) { "Project name contains invalid characters" }
        return normalized
    }

    private fun loadProjectFromDirectory(projectDir: File): WorkspaceProject? {
        val root = canonicalProjectsRoot()
        val canonicalDir = projectDir.canonicalFile
        if (canonicalDir.parentFile?.canonicalFile != root) return null
        val projectId = canonicalDir.name
        if (!SAFE_ID.matches(projectId)) return null

        val manifest = File(File(canonicalDir, METADATA_DIR), MANIFEST_FILE)
        if (!manifest.isFile) return null

        return runCatching {
            val json = JSONObject(manifest.readText(Charsets.UTF_8))
            val schemaVersion = json.getInt("schemaVersion")
            if (schemaVersion != WorkspaceProject.CURRENT_SCHEMA_VERSION) return null

            val manifestId = json.getString("projectId")
            if (manifestId != projectId || !SAFE_ID.matches(manifestId)) return null

            val name = normalizeProjectName(json.getString("name"))
            val type = WorkspaceProjectType.fromStorage(json.getString("projectType")) ?: return null
            val rootRelativePath = json.getString("rootRelativePath")
            if (rootRelativePath != projectId) return null

            val activeFilePath = if (json.has("activeFilePath") && !json.isNull("activeFilePath")) {
                json.getString("activeFilePath").also {
                    if (!isSafeRelativeFilePath(it)) return null
                }
            } else {
                null
            }

            val createdAt = json.getLong("createdAtMs")
            val updatedAt = json.getLong("updatedAtMs")
            val lastOpenedAt = json.getLong("lastOpenedAtMs")
            if (createdAt < 0L || updatedAt < createdAt || lastOpenedAt < 0L) return null

            WorkspaceProject(
                schemaVersion = schemaVersion,
                projectId = manifestId,
                name = name,
                type = type,
                rootRelativePath = rootRelativePath,
                activeFilePath = activeFilePath,
                createdAtMs = createdAt,
                updatedAtMs = updatedAt,
                lastOpenedAtMs = lastOpenedAt,
            )
        }.getOrNull()
    }

    private fun writeManifest(project: WorkspaceProject) {
        require(SAFE_ID.matches(project.projectId)) { "Invalid Workspace project ID" }
        require(project.rootRelativePath == project.projectId) { "Workspace root identity mismatch" }
        project.activeFilePath?.let {
            require(isSafeRelativeFilePath(it)) { "Invalid active-file path" }
        }

        val projectDir = safeProjectDir(project.projectId)
        require(projectDir.isDirectory) { "Workspace project root is missing" }
        val metadataDir = File(projectDir, METADATA_DIR)
        require(metadataDir.mkdirs() || metadataDir.isDirectory) { "Workspace metadata directory is unavailable" }

        val manifest = File(metadataDir, MANIFEST_FILE)
        val temp = File(metadataDir, "$MANIFEST_FILE.tmp")
        val json = JSONObject()
            .put("schemaVersion", project.schemaVersion)
            .put("projectId", project.projectId)
            .put("name", project.name)
            .put("projectType", project.type.storageValue)
            .put("rootRelativePath", project.rootRelativePath)
            .put("activeFilePath", project.activeFilePath ?: JSONObject.NULL)
            .put("createdAtMs", project.createdAtMs)
            .put("updatedAtMs", project.updatedAtMs)
            .put("lastOpenedAtMs", project.lastOpenedAtMs)

        temp.writeText(json.toString(2), Charsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                manifest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safeProjectDir(projectId: String): File {
        require(SAFE_ID.matches(projectId)) { "Invalid Workspace project ID" }
        val root = canonicalProjectsRoot()
        val candidate = File(root, projectId).canonicalFile
        require(candidate.parentFile?.canonicalFile == root) { "Workspace project escaped its root" }
        return candidate
    }

    private fun canonicalProjectsRoot(): File {
        require(projectsRoot.mkdirs() || projectsRoot.isDirectory) { "Workspace project root is unavailable" }
        return projectsRoot.canonicalFile
    }

    private fun isSafeRelativeFilePath(path: String): Boolean {
        if (path.isBlank() || File(path).isAbsolute) return false
        return path.replace('\\', '/').split('/').none { it == ".." || it == "." || it.isBlank() }
    }

    private fun deleteTreeSafely(file: File): Boolean {
        val root = canonicalProjectsRoot().toPath()
        val canonical = file.canonicalFile
        if (!canonical.toPath().startsWith(root) || canonical == canonicalProjectsRoot()) return false
        if (canonical.isDirectory) {
            for (child in canonical.listFiles().orEmpty()) {
                if (!deleteTreeSafely(child)) return false
            }
        }
        return canonical.delete()
    }
}
