package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Private Workspace transcripts, keyed by an existing project manifest; not personal AIRI memory. */
class WorkspaceConversationStore(
    private val projects: WorkspaceProjectStore,
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Message(val id: String, val role: String, val text: String, val createdAtMs: Long)
    companion object {
        const val MAX_MESSAGE_LENGTH = 6_000
        private const val MAX_MESSAGES = 160
        private const val MAX_FILE_BYTES = 1_200_000L
    }

    private fun transcript(projectId: String): File {
        requireNotNull(projects.getProject(projectId)) { "Select an existing Workspace project" }
        require(Regex("^[A-Za-z0-9_-]{1,80}$").matches(projectId)) { "Invalid project identity" }
        require(root.mkdirs() || root.isDirectory) { "Conversation storage unavailable" }
        require(!Files.isSymbolicLink(root.toPath())) { "Conversation storage link is forbidden" }
        val base = root.canonicalFile
        val directory = File(base, projectId)
        require(!Files.isSymbolicLink(directory.toPath())) { "Conversation link is forbidden" }
        require(directory.mkdirs() || directory.isDirectory) { "Conversation directory unavailable" }
        require(directory.canonicalFile.parentFile == base) { "Conversation escaped its project" }
        val file = File(directory, "conversation.json")
        require(!Files.isSymbolicLink(file.toPath())) { "Conversation link is forbidden" }
        return file
    }

    @Synchronized fun read(projectId: String): List<Message> {
        val file = transcript(projectId)
        if (!file.exists()) return emptyList()
        require(file.isFile && file.length() <= MAX_FILE_BYTES) { "Conversation is unavailable or too large" }
        val document = JSONObject(file.readText(Charsets.UTF_8))
        require(document.getInt("schemaVersion") == 1 && document.getString("projectId") == projectId) {
            "Conversation project identity mismatch"
        }
        val array = document.getJSONArray("messages")
        require(array.length() <= MAX_MESSAGES) { "Conversation exceeds local limit" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val role = item.getString("role")
            val text = item.getString("text")
            require(role == "user" || role == "assistant") { "Invalid conversation role" }
            require(text.isNotBlank() && text.length <= MAX_MESSAGE_LENGTH) { "Invalid conversation message" }
            Message(item.getString("id"), role, text, item.getLong("createdAtMs"))
        }
    }

    /** Delete only this transcript. Project manifests, source files, tasks and Undo/Keep remain untouched. */
    @Synchronized fun deleteChat(projectId: String): Boolean {
        val file = transcript(projectId)
        if (!file.exists()) return false
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Conversation changed unexpectedly" }
        check(file.delete()) { "Conversation could not be deleted" }
        return true
    }

    @Synchronized fun append(projectId: String, role: String, text: String): Message {
        require(role == "user" || role == "assistant") { "Invalid conversation role" }
        val content = text.trim()
        require(content.isNotBlank() && content.length <= MAX_MESSAGE_LENGTH) { "Message must contain 1–6000 characters" }
        val previous = read(projectId)
        val message = Message(UUID.randomUUID().toString(), role, content, now())
        val records = (previous + message).takeLast(MAX_MESSAGES)
        val document = JSONObject().put("schemaVersion", 1).put("projectId", projectId)
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().put("id", record.id).put("role", record.role)
                .put("text", record.text).put("createdAtMs", record.createdAtMs))
        }
        document.put("messages", array)
        val file = transcript(projectId)
        val temporary = File(file.parentFile, ".conversation-${UUID.randomUUID()}.tmp")
        try {
            val bytes = document.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size.toLong() <= MAX_FILE_BYTES) { "Conversation is too large" }
            java.io.FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            require(!Files.isSymbolicLink(file.toPath())) { "Conversation changed unexpectedly" }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        return message
    }
}
