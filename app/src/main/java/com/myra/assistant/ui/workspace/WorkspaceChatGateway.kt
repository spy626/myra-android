package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Workspace text and explicitly selected one-turn images. Never uses the voice-only Gemini key. */
internal object WorkspaceChatGateway {
    // Shared by all approved Free text routes. A conversational instruction, not
    // hardcoded responses, a new memory source, or a claim that model output passed.
    private const val CHAT_REPLY_DISCIPLINE =
        "You are LYRA. Answer the user's actual latest message in their language and tone, " +
            "following their requested length. For ordinary friendly conversation, listen first: " +
            "respond to what the user actually shared, with a brief natural reaction and, " +
            "only when useful, one relevant follow-up question. Do not force a question, " +
            "repeat a canned greeting, or end every turn with a farewell. " +
            "If the user briefly acknowledges a previous reply, continue the current " +
            "conversation naturally instead of treating the acknowledgment as goodbye; " +
            "end the conversation only when the user actually signals they are leaving. " +
            "A plan or personal update is not a request for instructions: do not add " +
            "unmentioned activities, companions, places or intentions, or present imagined " +
            "details as the user's facts. Avoid random jokes, forced slang and generic filler " +
            "unless invited. In casual chat, prefer one or two short, connected sentences " +
            "when the user asks for a short reply. Use complete everyday words, not clipped " +
            "fragments. If a plan is cancelled without a replacement being shared, say only " +
            "that no replacement was shared here; do not claim the user decided on nothing. " +
            "For a task, question, story, code, or " +
            "serious topic, fulfill the actual request with its needed detail and format, " +
            "not small talk. Earlier assistant replies can be mistaken and are NOT evidence " +
            "of what the user said. For questions about the user's earlier words, ground " +
            "claims only in earlier USER turns from this same conversation; quote them " +
            "if necessary. If evidence is absent, say so instead of guessing a name, " +
            "place, plan or other fact. Never claim phone testing."
    enum class Provider { OPENROUTER_FREE, GROQ_FREE, CLOUDFLARE_FREE }
    data class Image(val mime: String, val base64: String)
    // One extra try only after specific upstream HTTP rejections. Connection failures and
    // ambiguous timeouts are NOT retried. Retain the existing 35-second total call timeout.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder()
        .addInterceptor(WorkspaceMemoryInterceptor()) // only the approved OpenRouter endpoint
        .addInterceptor(WorkspaceFreeRouteRetry())
        .build()

    /** Each request includes only bounded messages from the explicitly selected project. */
    fun request(provider: Provider, key: String, messages: List<WorkspaceConversationStore.Message>,
                image: Image? = null, cloudflareAccountId: String = "",
                cloudflareModel: String = WorkspaceCloudflareFree.MODEL): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Set a valid provider key in API & Cloud Settings"
        }
        require(messages.isNotEmpty() && messages.last().role == "user") { "A user message is required" }
        // Previous turns are dropped whole when needed; the latest pasted prompt is never sliced.
        require(WorkspaceLongInputPolicy.requestFits(messages)) {
            "Full message exceeds LYRA's 64000-character local message cap; saved locally, nothing sent"
        }
        if (provider == Provider.GROQ_FREE) return WorkspaceGroqFree.request(key, messages, image)
        if (provider == Provider.CLOUDFLARE_FREE) {
            require(image == null) { "Cloudflare Free is text-only; no photo sent" }
            return WorkspaceCloudflareFree.chatRequest(key, cloudflareAccountId, messages, cloudflareModel)
        }
        image?.let {
            require(it.mime == "image/jpeg" || it.mime == "image/png") { "Unsupported photo format" }
            require(it.base64.length in 1..2_700_000 &&
                it.base64.all { char -> char.isLetterOrDigit() || char == '+' || char == '/' || char == '=' }) {
                "Photo is invalid or exceeds the request limit"
            }
        }
        // Inspect earlier user intent locally when needed, but transmit only recent raw turns.
        val body = openRouterBody(messages, image)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(WorkspaceFreeAiSuggestion.ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    fun openRouterBody(messages: List<WorkspaceConversationStore.Message>, image: Image? = null): String {
        val entries = JSONArray()
        val recent = WorkspaceLongInputPolicy.outbound(messages)
        val latest = recent.lastOrNull()?.takeIf { it.role == "user" }?.text
        val revisionKind = WorkspacePromptFollowUp.kind(messages)
        val contextDecision = WorkspacePromptContext.resolve(messages)
        val writingInstructions = when {
            revisionKind != null -> WorkspacePromptFollowUp.instructions(revisionKind)
            contextDecision != null -> WorkspacePromptContext.instructions(contextDecision)
            latest != null && WorkspacePromptWriting.kind(latest) != null ->
                WorkspacePromptWriting.instructions(latest)
            latest != null && WorkspaceStoryScript.isWritingRequest(latest) ->
                WorkspaceStoryScript.writingInstructions(latest)
            else -> ""
        }
        // The selected chat's older USER statements may help a follow-up, but never
        // import other chats or assistant guesses. The existing intent projection already
        // covers ambiguous prompt decisions, so do not duplicate its earlier evidence.
        val earlier = if (revisionKind == null && contextDecision == null)
            WorkspaceContextProjection.earlierUserContext(messages) else ""
        val codeInstructions = latest?.let(WorkspaceCodePrompt::instructions).orEmpty()
        // AIRI-style turn state is a bounded, read-only projection of this same Chat.
        // Dedicated writing, follow-up, coding and task prompts are never replaced.
        val turnFrame = if (revisionKind == null && contextDecision == null &&
            writingInstructions.isBlank() && codeInstructions.isBlank())
            WorkspaceChatTurnFrame.instructions(recent) else ""
        val instructions = listOf(CHAT_REPLY_DISCIPLINE, turnFrame, writingInstructions, earlier, codeInstructions)
            .filter(String::isNotBlank).joinToString("\n\n")
        if (instructions.isNotBlank()) entries.put(JSONObject().put("role", "system")
            .put("content", instructions))
        recent.forEachIndexed { index, message ->
            require(message.role == "user" || message.role == "assistant") { "Invalid chat role" }
            require(message.text.length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) { "Invalid message size" }
            val content: Any = if (image != null && index == recent.lastIndex) {
                JSONArray().put(JSONObject().put("type", "text").put("text", message.text))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${image.mime};base64,${image.base64}")))
            } else message.text
            entries.put(JSONObject().put("role", message.role).put("content", content))
        }
        return JSONObject().put("model", WorkspaceFreeAiSuggestion.MODEL)
            .put("stream", false).put("max_tokens", 2_048)
            // A free label alone is insufficient: reject every endpoint with a nonzero
            // prompt, completion, per-request or image price. Never upgrade silently.
            .put("provider", JSONObject().put("zdr", true).put("data_collection", "deny")
                .put("allow_fallbacks", false)
                .put("max_price", JSONObject().put("prompt", 0).put("completion", 0)
                    .put("request", 0).put("image", 0)))
            // OpenRouter may otherwise compress/truncate the middle on small endpoints.
            // Never permit silent truncation of the user's full pasted prompt.
            .put("plugins", JSONArray().put(JSONObject().put("id", "context-compression")
                .put("enabled", false)))
            .put("messages", entries).toString()
    }

    fun read(provider: Provider, response: Response): String = when (provider) {
        Provider.OPENROUTER_FREE -> WorkspaceFreeAiSuggestion.readResponse(response)
        Provider.GROQ_FREE -> WorkspaceGroqFree.read(response)
        Provider.CLOUDFLARE_FREE -> WorkspaceCloudflareFree.readChat(response)
    }
}
