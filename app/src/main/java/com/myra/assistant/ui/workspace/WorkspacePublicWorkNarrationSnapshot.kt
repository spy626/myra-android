package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, presentation-only snapshot for the public work narration of one exact GitHub turn.
 *
 * This is not execution state and grants no authority. The GitHub checkpoint remains the source of
 * truth for resume. The snapshot only lets the visible human-facing narration survive UI/process
 * recreation and is accepted only for the exact project + USER turn that owns that checkpoint.
 */
internal object WorkspacePublicWorkNarrationSnapshot {
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 8
    private const val MAX_SERIALIZED_CHARS = 8_000

    fun encode(
        projectId: String,
        messageId: String,
        messages: List<WorkspacePublicWorkMessage>,
    ): String {
        val project = requireProjectId(projectId)
        val message = requireMessageId(messageId)
        val array = JSONArray()
        messages.takeLast(MAX_ENTRIES).forEach { raw ->
            checked(raw)?.let { item ->
                array.put(
                    JSONObject()
                        .put("key", item.key)
                        .put("statusLabel", item.statusLabel)
                        .put("text", item.text)
                        .put("atMs", item.atMs)
                )
            }
        }
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("projectId", project)
            .put("messageId", message)
            .put("entries", array)
            .toString()
        require(encoded.length <= MAX_SERIALIZED_CHARS) { "Public narration snapshot is too large" }
        return encoded
    }

    fun decode(
        raw: String?,
        expectedProjectId: String,
        expectedMessageId: String,
    ): List<WorkspacePublicWorkMessage> {
        val project = runCatching { requireProjectId(expectedProjectId) }.getOrNull()
            ?: return emptyList()
        val message = runCatching { requireMessageId(expectedMessageId) }.getOrNull()
            ?: return emptyList()
        val input = raw?.takeIf { it.isNotBlank() && it.length <= MAX_SERIALIZED_CHARS }
            ?: return emptyList()

        return runCatching {
            val document = JSONObject(input)
            if (document.getInt("schemaVersion") != SCHEMA_VERSION ||
                document.getString("projectId") != project ||
                document.getString("messageId") != message) {
                return@runCatching emptyList()
            }
            val entries = document.getJSONArray("entries")
            if (entries.length() > MAX_ENTRIES) return@runCatching emptyList()
            buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    checked(
                        WorkspacePublicWorkMessage(
                            key = item.getString("key"),
                            statusLabel = item.getString("statusLabel"),
                            text = item.getString("text"),
                            atMs = item.getLong("atMs"),
                        )
                    )?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun checked(raw: WorkspacePublicWorkMessage): WorkspacePublicWorkMessage? {
        if (raw.atMs < 0L ||
            WorkspaceSourceContext.containsPossibleSecret(raw.statusLabel) ||
            WorkspaceSourceContext.containsPossibleSecret(raw.text)) {
            return null
        }
        val key = WorkspaceWorkTrace.safeText(raw.key, 80)
            .takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?: return null
        val status = WorkspaceWorkTrace.safeText(raw.statusLabel, 90)
            .takeIf(String::isNotBlank)
            ?: return null
        val text = WorkspaceWorkTrace.safeText(raw.text, 420)
            .takeIf(String::isNotBlank)
            ?: return null
        return WorkspacePublicWorkMessage(key, status, text, raw.atMs)
    }

    private fun requireProjectId(projectId: String): String {
        val id = projectId.trim()
        require(Regex("^[A-Za-z0-9_-]{1,80}$").matches(id)) {
            "Public narration project id is invalid"
        }
        return id
    }

    private fun requireMessageId(messageId: String): String {
        val id = messageId.trim()
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) {
            "Public narration message id is invalid"
        }
        return id
    }
}
