package com.myra.assistant.ui.workspace

import okhttp3.Response
import org.json.JSONObject

/**
 * Strict OpenAI-compatible SSE reader for direct Z.ai text chat.
 *
 * Partial chunks are surfaced to the caller for transient display only. A durable reply
 * is returned only after a standard data: [DONE] terminator and finish_reason=stop.
 */
internal object WorkspaceZaiStream {
    private const val MAX_VISIBLE_CHARS = 6_000
    private const val MAX_STREAM_LINE_CHARS = 24_000
    private const val MAX_STREAM_TOTAL_CHARS = 96_000

    data class Event(
        val delta: String = "",
        val finishReason: String? = null,
        val done: Boolean = false,
    )

    fun parseData(data: String): Event {
        val trimmed = data.trim()
        if (trimmed == "[DONE]") return Event(done = true)
        require(trimmed.isNotBlank() && trimmed.length <= MAX_STREAM_LINE_CHARS) {
            "Z.ai stream event is empty or too large"
        }
        val root = runCatching { JSONObject(trimmed) }
            .getOrElse { throw IllegalArgumentException("Z.ai returned an invalid stream event") }
        require(!root.has("error")) { "Z.ai returned a stream error; no partial reply saved" }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalArgumentException("Z.ai stream event had no completion choice")
        val deltaObject = choice.optJSONObject("delta")
        val rawContent = deltaObject?.opt("content")
        require(rawContent == null || rawContent === JSONObject.NULL || rawContent is String) {
            "Z.ai stream returned unsupported text content"
        }
        return Event(
            delta = if (rawContent is String) rawContent else "",
            finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() },
        )
    }

    fun read(response: Response, onPartial: (String) -> Unit): String {
        val body = response.body ?: throw IllegalArgumentException("Z.ai stream body is empty")
        val source = body.source()
        val text = StringBuilder()
        val eventData = StringBuilder()
        var totalChars = 0
        var sawDone = false
        var finishReason: String? = null

        fun applyEvent() {
            if (eventData.isEmpty()) return
            val event = parseData(eventData.toString())
            eventData.setLength(0)
            if (event.done) {
                sawDone = true
                return
            }
            event.finishReason?.let { finishReason = it }
            if (event.delta.isNotEmpty()) {
                require(text.length + event.delta.length <= MAX_VISIBLE_CHARS) {
                    "Z.ai streamed reply exceeds LYRA's safe visible size"
                }
                text.append(event.delta)
                onPartial(text.toString())
            }
        }

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            totalChars += line.length
            require(totalChars <= MAX_STREAM_TOTAL_CHARS) {
                "Z.ai stream exceeds LYRA's safe transport size"
            }
            when {
                line.isBlank() -> applyEvent()
                line.startsWith("data:") -> {
                    if (eventData.isNotEmpty()) eventData.append('\n')
                    eventData.append(line.removePrefix("data:").trimStart())
                }
                line.startsWith(":") -> Unit // SSE keepalive/comment.
                else -> Unit // Ignore standard SSE metadata such as event:/id:/retry:.
            }
            if (sawDone) break
        }
        applyEvent()

        require(sawDone) { "Z.ai stream ended before data: [DONE]; no partial reply saved" }
        require(finishReason == "stop") {
            "Z.ai stream was incomplete or filtered; no partial reply saved"
        }
        val final = text.toString().trim()
        require(final.isNotEmpty()) { "Z.ai stream returned no visible text" }
        return final
    }
}
