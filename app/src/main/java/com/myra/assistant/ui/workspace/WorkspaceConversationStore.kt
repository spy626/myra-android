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
        const val MAX_MESSAGE_LENGTH = WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS
        private const val MAX_MESSAGES = 160
        private const val MAX_FILE_BYTES = 16_000_000L
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

    private fun checkedText(text: String): String = text.also {
        require(it.isNotBlank() && it.length <= MAX_MESSAGE_LENGTH) { "Message must contain 1–${MAX_MESSAGE_LENGTH} characters" }
    }

    /** Revisions are limited to the newest user turn, protecting later conversation and project work. */
    @Synchronized fun reviseNewestUser(projectId: String, expectedUserId: String, text: String): Message {
        val content = checkedText(text)
        val previous = read(projectId)
        val lastUser = previous.indexOfLast { it.role == "user" }
        require(lastUser >= 0 && previous[lastUser].id == expectedUserId) { "Only the newest user message can be edited" }
        require(lastUser == previous.lastIndex ||
            (lastUser == previous.lastIndex - 1 && previous.last().role == "assistant")) {
            "Conversation changed; edit cancelled"
        }
        val revised = previous[lastUser].copy(text = content)
        // The previous answer no longer belongs to this edited prompt. Do not affect other turns.
        write(projectId, previous.take(lastUser) + revised)
        return revised
    }

    /** Keep the original answer on network failure, replacing it only after a verified retry succeeds. */
    @Synchronized fun replaceNewestAssistant(projectId: String, expectedAssistantId: String,
                                              expectedUserId: String, text: String): Message {
        val content = checkedText(text)
        val previous = read(projectId)
        require(previous.size >= 2 && previous.last().role == "assistant" &&
            previous.last().id == expectedAssistantId &&
            previous[previous.lastIndex - 1].role == "user" &&
            previous[previous.lastIndex - 1].id == expectedUserId) {
            "Conversation changed; retried response was not applied"
        }
        val revised = previous.last().copy(text = content)
        write(projectId, previous.dropLast(1) + revised)
        return revised
    }

    /** Attach a coding outcome only to its exact user turn; never overwrite newer work.
     * Reattempts of the same turn replace only that turn's preceding assistant outcome.
     */
    @Synchronized fun completeCodingTurn(projectId: String, expectedUserId: String, text: String): Message {
        require(projects.getProject(projectId)?.type != WorkspaceProjectType.CHAT) {
            "Coding result requires an existing project"
        }
        val content = checkedText(text)
        val history = read(projectId)
        val last = history.lastOrNull()
        if (last?.role == "user" && last.id == expectedUserId) {
            val saved = Message(UUID.randomUUID().toString(), "assistant", content, now())
            write(projectId, (history + saved).takeLast(MAX_MESSAGES))
            return saved
        }
        if (last?.role == "assistant" && history.getOrNull(history.lastIndex - 1)?.let {
                it.role == "user" && it.id == expectedUserId
            } == true) {
            val updated = last.copy(text = content)
            write(projectId, history.dropLast(1) + updated)
            return updated
        }
        error("Coding turn changed; stale result was not added to chat")
    }

    @Synchronized fun append(projectId: String, role: String, text: String): Message {
        require(role == "user" || role == "assistant") { "Invalid conversation role" }
        val content = checkedText(text)
        val previous = read(projectId)
        val message = Message(UUID.randomUUID().toString(), role, content, now())
        write(projectId, (previous + message).takeLast(MAX_MESSAGES))
        return message
    }

    private fun write(projectId: String, records: List<Message>) {
        require(records.size <= MAX_MESSAGES) { "Conversation exceeds local limit" }
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
    }
}
