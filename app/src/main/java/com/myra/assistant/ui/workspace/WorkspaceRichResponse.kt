package com.myra.assistant.ui.workspace

import okhttp3.Response
import org.json.JSONObject

/**
 * Actual OpenAI-compatible SSE content stream. Never invents a typing animation or
 * displays incomplete model JSON. Non-SSE providers keep their existing safe reader.
 */
internal object WorkspaceRichResponse {
    fun read(
        response: Response,
        fallback: (Response) -> String,
        onBlocks: (List<Block>) -> Unit,
    ): String {
        if (!response.isSuccessful ||
            !response.header("Content-Type").orEmpty().contains("text/event-stream", true)
        ) return fallback(response)
        return response.use { sourceResponse ->
            val source = requireNotNull(sourceResponse.body).source()
            val complete = StringBuilder()
            val incremental = RichBlockIncrementalParser()
            val event = mutableListOf<String>()
            var completed = false
            var done = false
            var receivedChars = 0
            fun consume() {
                if (event.isEmpty()) return
                val data = event.joinToString("\n")
                event.clear()
                if (data.trim() == "[DONE]") { done = true; return }
                val json = JSONObject(data)
                require(!json.has("error")) { "Provider returned a streaming error" }
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
                val finish = choice.optString("finish_reason")
                if (finish.isNotBlank() && finish != "null") {
                    require(finish == "stop") { "Provider did not complete its reply: " + finish }
                    completed = true
                }
                val delta = choice.optJSONObject("delta")?.opt("content")
                if (delta is String && delta.isNotEmpty()) {
                    complete.append(delta)
                    require(complete.length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH &&
                        complete.length <= 96_000) { "Streamed reply exceeds local limit" }
                    val ready = incremental.update(complete.toString())
                    if (ready.isNotEmpty()) onBlocks(ready)
                }
            }
            while (!done) {
                val line = source.readUtf8Line() ?: break
                receivedChars += line.length
                require(receivedChars <= 192_000) { "Provider stream exceeds response budget" }
                if (line.isBlank()) { consume(); continue }
                if (line.startsWith("data:")) event.add(line.substring(5).trimStart())
            }
            if (event.isNotEmpty()) consume()
            require(completed && complete.isNotBlank()) {
                "Provider stream ended without a complete reply; no partial reply saved"
            }
            val raw = complete.toString().trim()
            // Partial streamed blocks are display-only. Full result must be a valid envelope,
            // unless the provider genuinely sent plain text (single-text-block fallback).
            require(!RichBlockParser.looksLikeEnvelope(raw) || RichBlockParser.isEnvelope(raw)) {
                "Provider sent incomplete rich JSON; no incomplete reply saved"
            }
            raw
        }
    }
}
