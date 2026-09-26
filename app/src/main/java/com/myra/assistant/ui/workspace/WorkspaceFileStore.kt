package com.myra.assistant.ui.workspace

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Workspace-only source-file authority. Never accesses AIRI memory or arbitrary phone folders. */
class WorkspaceFileStore(private val projects: WorkspaceProjectStore) {
    data class Entry(val path: String, val folder: Boolean, val depth: Int)

    companion object {
        const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_ENTRIES = 500
        private const val MAX_DEPTH = 12
    }

    private fun root(id: String): File {
        require(projects.getProject(id) != null) { "Workspace project is unavailable" }
        val root = projects.projectRoot(id)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Project root is unavailable" }
        return root.canonicalFile
    }

    private fun parts(path: String): List<String> {
        require(path.isNotBlank() && path.length <= 600 && !path.startsWith('/')) { "Enter a relative project path" }
        require('\\' !in path && ':' !in path) { "Invalid project path" }
        val segments = path.split('/')
        require(segments.size <= MAX_DEPTH && segments.all { part ->
            part.length in 1..100 && part != "." && part != ".." && part != ".lyra" &&
                !part.startsWith(".lyra-write-") && part.none { it.isISOControl() }
        }) { "Invalid or protected project path" }
        return segments
    }

    private fun resolve(id: String, path: String): File {
        val root = root(id)
        var current = root
        for (part in parts(path)) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "Symbolic links are not supported" }
            require(current.canonicalFile.toPath().startsWith(root.toPath())) { "Path escaped the project" }
        }
        require(current.canonicalFile != root) { "Cannot operate on the project root" }
        return current
    }

    fun list(id: String): List<Entry> {
        val root = root(id)
        val found = mutableListOf<Entry>()
        fun visit(dir: File, prefix: String, depth: Int) {
            if (depth >= MAX_DEPTH || found.size >= MAX_ENTRIES) return
            val children = dir.listFiles().orEmpty().filter { child ->
                !Files.isSymbolicLink(child.toPath()) && child.name != ".lyra" &&
                    !child.name.startsWith(".lyra-write-") && !child.name.contains('\\')
            }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            for (child in children) {
                if (found.size >= MAX_ENTRIES) break
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (runCatching { resolve(id, path) }.isFailure) continue
                found.add(Entry(path, child.isDirectory, depth))
                if (child.isDirectory) visit(child, path, depth + 1)
            }
        }
        visit(root, "", 0)
        return found
    }

    fun createFile(id: String, path: String) {
        val file = resolve(id, path)
        require(file.parentFile?.isDirectory == true) { "Create the parent folder first" }
        require(!file.exists()) { "File already exists" }
        check(file.createNewFile()) { "Cannot create file" }
        projects.touchProject(id)
    }

    fun createFolder(id: String, path: String) {
        val dir = resolve(id, path)
        require(dir.parentFile?.isDirectory == true) { "Create the parent folder first" }
        require(!dir.exists()) { "File or folder already exists" }
        check(dir.mkdir()) { "Cannot create folder" }
        projects.touchProject(id)
    }

    fun readFile(id: String, path: String): String {
        val file = resolve(id, path)
        require(file.isFile && file.length() <= MAX_FILE_BYTES) { "File is unavailable or too large for the phone editor" }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_FILE_BYTES) { "File exceeds the editor size limit" }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun saveFile(id: String, path: String, text: String) {
        val file = resolve(id, path)
        require(file.isFile) { "File has been moved or deleted" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "File must stay below 256 KB" }
        val temp = File(file.parentFile, ".lyra-write-${UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            require(!Files.isSymbolicLink(file.toPath())) { "File changed unexpectedly" }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            projects.touchProject(id)
        } finally {
            temp.delete()
        }
    }

    fun rememberActive(id: String, path: String?) {
        if (path != null) require(resolve(id, path).isFile) { "Active file is unavailable" }
        projects.setActiveFile(id, path)
    }

    fun rename(id: String, oldPath: String, newPath: String): String {
        val source = resolve(id, oldPath)
        val target = resolve(id, newPath)
        require(source.exists() && target.parentFile?.isDirectory == true) { "Source or target folder is unavailable" }
        require(!target.exists()) { "Target already exists" }
        require(!target.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "Cannot move a folder inside itself" }
        Files.move(source.toPath(), target.toPath())
        val active = projects.getProject(id)?.activeFilePath
        if (active != null && (active == oldPath || active.startsWith("$oldPath/"))) {
            projects.setActiveFile(id, newPath + active.removePrefix(oldPath))
        } else {
            projects.touchProject(id)
        }
        return newPath
    }

    fun delete(id: String, path: String) {
        val target = resolve(id, path)
        require(target.exists()) { "File or folder no longer exists" }
        fun remove(node: File) {
            require(!Files.isSymbolicLink(node.toPath())) { "Cannot delete a symbolic link" }
            if (node.isDirectory) node.listFiles().orEmpty().forEach(::remove)
            check(node.delete()) { "Could not delete ${node.name}" }
        }
        remove(target)
        val active = projects.getProject(id)?.activeFilePath
        if (active != null && (active == path || active.startsWith("$path/"))) {
            projects.setActiveFile(id, null)
        } else {
            projects.touchProject(id)
        }
    }

    /** Explicit opt-in starter; refuses to overwrite ANY existing project file. */
    fun addWebsiteStarter(id: String) {
        require(projects.getProject(id)?.type == WorkspaceProjectType.WEBSITE) { "Website project required" }
        val names = listOf("index.html", "style.css", "script.js")
        require(names.all { !resolve(id, it).exists() }) { "Starter files already exist; no files were overwritten" }
        val templates = listOf(
            "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>My Website</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>Hello, Workspace!</h1>\n  <script src=\"script.js\"></script>\n</body>\n</html>\n",
            "body { margin: 0; padding: 2rem; font-family: system-ui, sans-serif; }\n",
            "// Your JavaScript starts here.\n",
        )
        val created = mutableListOf<String>()
        try {
            names.zip(templates).forEach { (name, text) ->
                createFile(id, name)
                created.add(name)
                saveFile(id, name, text)
            }
        } catch (error: Exception) {
            created.forEach { runCatching { resolve(id, it).delete() } }
            throw error
        }
    }
}
