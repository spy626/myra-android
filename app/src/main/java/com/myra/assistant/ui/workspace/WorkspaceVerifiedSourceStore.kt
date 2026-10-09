package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Durable presentation metadata for runtime-grounded public sources.
 *
 * This is not model memory and is never projected to a provider. Callers may save only sources
 * obtained from an authoritative runtime read/lookup. Model-authored links are never promoted here.
 */
internal class WorkspaceVerifiedSourceStore(
    private val root: File,
) {
    data class Source(
        val title: String,
        val url: String,
        val snippet: String,
        val observedAtMs: Long,
        val verifiedLabel: String? = null,
    ) {
        val host: String get() = WorkspaceAgentReachPolicy.parse(url).host
    }

    companion object {
        const val MAX_SOURCES_PER_MESSAGE = 12
        private const val MAX_FILE_BYTES = 512_000L
        private val safeId = Regex("""[A-Za-z0-9_-]{1,120}""")
    }

    private fun checkedId(value: String, label: String): String {
        val clean = value.trim()
        require(safeId.matches(clean)) { "Invalid " + label + " identity" }
        return clean
    }

    private fun file(projectId: String): File {
        val project = checkedId(projectId, "project")
        require(root.mkdirs() || root.isDirectory) { "Verified source storage unavailable" }
        require(!Files.isSymbolicLink(root.toPath())) { "Verified source storage link is forbidden" }
        val base = root.canonicalFile
        val result = File(base, project + ".json")
        require(result.canonicalFile.parentFile == base && !Files.isSymbolicLink(result.toPath())) {
            "Verified source storage escaped its root"
        }
        return result
    }

    private fun validate(source: Source): Source {
        val title = source.title.trim().replace(Regex("""\s+"""), " ")
        val snippet = source.snippet.trim().replace(Regex("""\s+"""), " ")
        require(title.length in 1..180 && snippet.length <= 360 &&
            title.none(Char::isISOControl) && snippet.none(Char::isISOControl)) {
            "Verified source text is invalid"
        }
        require(source.observedAtMs > 0L) { "Verified source timestamp is invalid" }
        val target = WorkspaceAgentReachPolicy.parse(source.url)
        require(target.canonicalUrl.startsWith("https://")) { "Verified source must be public HTTPS" }
        val label = source.verifiedLabel?.trim()?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 60 && it.none(Char::isISOControl)) {
                "Verified source label is invalid"
            }
        }
        return Source(title, target.canonicalUrl, snippet, source.observedAtMs, label)
    }

    @Synchronized fun put(
        projectId: String,
        assistantMessageId: String,
        sources: List<Source>,
    ) {
        val messageId = checkedId(assistantMessageId, "assistant message")
        require(sources.isNotEmpty() && sources.size <= MAX_SOURCES_PER_MESSAGE) {
            "Verified source count is invalid"
        }
        val clean = sources.map(::validate).distinctBy { it.url }
        require(clean.isNotEmpty()) { "No verified sources to store" }

        val target = file(projectId)
        val document = if (target.exists()) {
            require(target.isFile && target.length() <= MAX_FILE_BYTES) {
                "Verified source metadata is unavailable or too large"
            }
            JSONObject(target.readText(Charsets.UTF_8)).also {
                require(it.optInt("schemaVersion") == 1) {
                    "Verified source metadata schema mismatch"
                }
            }
        } else JSONObject().put("schemaVersion", 1).put("messages", JSONObject())
        val messages = document.optJSONObject("messages") ?: JSONObject().also {
            document.put("messages", it)
        }
        val array = JSONArray()
        clean.forEach { source ->
            array.put(
                JSONObject()
                    .put("title", source.title)
                    .put("url", source.url)
                    .put("snippet", source.snippet)
                    .put("observedAtMs", source.observedAtMs)
                    .put("verifiedLabel", source.verifiedLabel ?: JSONObject.NULL)
            )
        }
        messages.put(messageId, array)
        writeAtomic(target, document)
    }

    @Synchronized fun get(projectId: String, assistantMessageId: String): List<Source> {
        val messageId = checkedId(assistantMessageId, "assistant message")
        val target = file(projectId)
        if (!target.exists()) return emptyList()
        require(target.isFile && target.length() <= MAX_FILE_BYTES) {
            "Verified source metadata is unavailable or too large"
        }
        val document = JSONObject(target.readText(Charsets.UTF_8))
        require(document.optInt("schemaVersion") == 1) {
            "Verified source metadata schema mismatch"
        }
        val array = document.optJSONObject("messages")?.optJSONArray(messageId)
            ?: return emptyList()
        require(array.length() <= MAX_SOURCES_PER_MESSAGE) {
            "Verified source metadata exceeds message limit"
        }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            validate(
                Source(
                    title = item.getString("title"),
                    url = item.getString("url"),
                    snippet = item.optString("snippet"),
                    observedAtMs = item.getLong("observedAtMs"),
                    verifiedLabel = item.optString("verifiedLabel").takeIf { it.isNotBlank() },
                )
            )
        }
    }

    @Synchronized fun deleteProject(projectId: String) {
        val target = file(projectId)
        if (target.exists()) {
            require(target.isFile && !Files.isSymbolicLink(target.toPath())) {
                "Verified source metadata changed unexpectedly"
            }
            check(target.delete()) { "Verified source metadata could not be deleted" }
        }
    }

    private fun writeAtomic(target: File, document: JSONObject) {
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_FILE_BYTES) { "Verified source metadata is too large" }
        val temporary = File(target.parentFile, ".verified-sources-" + UUID.randomUUID() + ".tmp")
        try {
            java.io.FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }
}
