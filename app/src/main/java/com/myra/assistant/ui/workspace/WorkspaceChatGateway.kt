package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Workspace text and explicitly selected one-turn images. Never uses the voice-only Gemini key. */
internal object WorkspaceChatGateway {
    // Shared by all approved Free text routes. A conversational instruction, not
    // hardcoded responses, a new memory source, or a claim that model output passed.
    private const val CHAT_REPLY_DISCIPLINE =
        "You are LYRA. Answer the user's actual latest message in the requested detail and tone, " +
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
            "when the user asks for a short reply. In Roman Hindi/Hinglish, use natural " +
            "grammar and complete familiar words; no fake slang or arbitrary proper names. " +
            "Do not propose meeting the user unless invited. If a plan is cancelled " +
            "without a replacement being shared, say only " +
            "that no replacement was shared here; do not claim the user decided on nothing. " +
            "For a task, question, story, code, or " +
            "serious topic, fulfill the actual request with its needed detail and format, " +
            "not small talk. Earlier assistant replies can be mistaken and are NOT evidence " +
            "of what the user said. For questions about the user's earlier words, ground " +
            "claims only in earlier USER turns from this same conversation; quote them " +
            "if necessary. If evidence is absent, say so instead of guessing a name, " +
            "place, plan or other fact. Present genuinely multi-part information as " +
            "short contextual Markdown sections, real bullet/numbered lists, or a small " +
            "comparison table only when that structure adds clarity. Don't flatten a " +
            "multi-part answer into one run-on paragraph. On comparisons, preserve " +
            "real values in an aligned 2–4-column Markdown table when that improves " +
            "clarity; otherwise use prose. On beginner tasks, state one coherent " +
            "direction and reason before specific actions and expected outcomes. " +
            "For sourced answers, separate evidence from inference: do not invent " +
            "media, chart data or citations. Don't repeat the same rigid " +
            "Where/What/Result or Kahan/Kya/Result fields in every answer. Match the " +
            "format to the specific question, not a canned template. A short personal " +
            "reply should remain natural conversation, not become a report. Don't claim " +
            "to render external app logos, screenshots or interactive elements that " +
            "were never actually supplied. Never claim phone testing."
    enum class Provider { OPENROUTER_FREE, GROQ_FREE, LLM7_FREE }
    data class Image(val mime: String, val base64: String)
    data class NativeVideo(val mime: String, val base64: String)
    data class Audio(val mime: String, val base64: String)
    // One extra try only after specific upstream HTTP rejections. Connection failures and
    // ambiguous timeouts are NOT retried. Retain the existing 35-second total call timeout.
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder()
        .addInterceptor(WorkspaceMemoryInterceptor()) // existing opt-in OpenRouter projection
        .addInterceptor(WorkspaceRichUserContextInterceptor()) // same opt-in, rich Chat only
        .addInterceptor(WorkspaceFreeRouteRetry())
        .build()

    /** Each request includes only bounded messages from the explicitly selected project. */
    fun request(
        provider: Provider,
        key: String,
        messages: List<WorkspaceConversationStore.Message>,
        image: Image? = null,
        extraSystemInstructions: String? = null,
        images: List<Image> = emptyList(),
        video: NativeVideo? = null,
        audio: Audio? = null,
    ): Request {
        require(key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)) {
            "Set a valid provider key in API & Cloud Settings"
        }
        require(messages.isNotEmpty() && messages.last().role == "user") { "A user message is required" }
        // Previous turns are dropped whole when needed; the latest pasted prompt is never sliced.
        require(WorkspaceLongInputPolicy.requestFits(messages)) {
            "Full message exceeds LYRA's 64000-character local message cap; saved locally, nothing sent"
        }
        val media = listOfNotNull(image) + images
        require(media.size <= WorkspaceMediaLimits.MAX_PHOTOS) {
            "At most ten photos per request"
        }
        require((if (media.isNotEmpty()) 1 else 0) +
            (if (video != null) 1 else 0) + (if (audio != null) 1 else 0) <= 1) {
            "Choose up to ten photos OR one original video OR one audio file"
        }
        if (provider == Provider.GROQ_FREE) {
            require(media.isEmpty() && video == null && audio == null) {
                "Groq Free Chat is text-only; media requires an approved OpenRouter Free route"
            }
            return WorkspaceGroqFree.request(key, messages, null, extraSystemInstructions)
        }
        if (provider == Provider.LLM7_FREE) {
            require(media.isEmpty() && video == null && audio == null) {
                "LLM7 Free Chat is text-only; media requires an approved OpenRouter Free route"
            }
            return WorkspaceLlm7Free.request(key, messages, null, extraSystemInstructions)
        }
        require(media.isEmpty() ||
            WorkspaceMediaLimits.imageEnvelopeSizes(media.map { it.base64.length })) {
            "The selected photos exceed the bounded ten-photo Free request budget"
        }
        media.forEach {
            require(it.mime == "image/jpeg" || it.mime == "image/png") { "Unsupported image format" }
            require(it.base64.all { ch -> ch.isLetterOrDigit() || ch == '+' || ch == '/' || ch == '=' }) {
                "Invalid photo bytes"
            }
        }
        video?.let {
            require(it.mime == "video/mp4" || it.mime == "video/webm") { "Unsupported video type" }
            require(it.base64.length in 1..WorkspaceMediaLimits.MAX_NATIVE_VIDEO_BASE64 &&
                it.base64.all { ch -> ch.isLetterOrDigit() || ch == '+' || ch == '/' || ch == '=' }) {
                "Original video exceeds LYRA's safe full-file Free upload budget"
            }
        }
        audio?.let {
            require(it.mime == "audio/mpeg" || it.mime == "audio/mp3" ||
                it.mime == "audio/wav" || it.mime == "audio/x-wav") { "Only MP3 and WAV audio are supported" }
            require(it.base64.length in 1..WorkspaceMediaLimits.MAX_AUDIO_BASE64 &&
                it.base64.all { ch -> ch.isLetterOrDigit() || ch == '+' || ch == '/' || ch == '=' }) {
                "Audio exceeds LYRA's safe Free upload budget"
            }
        }
        // Inspect earlier user intent locally when needed, but transmit only recent raw turns.
        val body = openRouterBody(messages, image, extraSystemInstructions, images, video, audio)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(WorkspaceFreeAiSuggestion.ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    internal fun openAiMessages(
        messages: List<WorkspaceConversationStore.Message>,
        image: Image? = null,
        extraSystemInstructions: String? = null,
        compactForGroq: Boolean = false,
        images: List<Image> = emptyList(),
        video: NativeVideo? = null,
        audio: Audio? = null,
    ): JSONArray {
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
        val extra = extraSystemInstructions?.trim().orEmpty()
        require(extra.length <= 24_000 && !extra.contains('\u0000')) {
            "One-turn system instructions are invalid or exceed the bounded prompt limit"
        }
        // AIRI-style turn state is a bounded, read-only projection of this same Chat.
        // Dedicated writing, follow-up, coding and task prompts are never replaced.
        val normalConversation = revisionKind == null && contextDecision == null &&
            writingInstructions.isBlank() && codeInstructions.isBlank()
        val semanticTurnIntent = if (normalConversation && latest != null)
            WorkspaceSemanticTurnIntent.instructions(
                WorkspaceSemanticTurnIntent.propose(latest)
            ) else ""
        val semanticTaskFrame = if (normalConversation)
            WorkspaceSemanticTaskFrame.instructions(messages) else ""
        val turnFrame = if (normalConversation)
            WorkspaceChatTurnFrame.instructions(recent) else ""
        // Advice-only turns may mention "coding" solely to prohibit it, which activates
        // the existing code-format cue. Still project practical planning guidance here.
        val practicalPlanning = if (revisionKind == null && contextDecision == null &&
            writingInstructions.isBlank() && latest != null
        ) {
            if (compactForGroq) WorkspacePracticalPlanningGuide.compactInstructions(latest)
            else WorkspacePracticalPlanningGuide.instructions(latest)
        } else ""
        // Scoped facts explicitly supplied by the user for app planning. This is NOT
        // AIRI memory access and it must not leak into casual chat or task execution.
        val shortProjectContext = if (WorkspaceRichBlocksContract.enabled(extra))
            WorkspaceRichBlocksContract.shortProjectContext(latest) else ""
        // One planning contract owns CURRENT-turn requirements in both normal
        // and Groq-compact projections; no second competing AnswerBoundary prompt.
        val instructions = (if (compactForGroq) listOf(
            CHAT_REPLY_DISCIPLINE,
            WorkspaceHinglishReply.PROMPT_RULE,
            extra,
            shortProjectContext,
            writingInstructions,
            codeInstructions,
            practicalPlanning,
        ) else listOf(
            CHAT_REPLY_DISCIPLINE,
            WorkspaceHinglishReply.PROMPT_RULE,
            extra,
            shortProjectContext,
            semanticTurnIntent,
            semanticTaskFrame,
            turnFrame,
            writingInstructions,
            earlier,
            codeInstructions,
            // Shared planning contract appears once, after generic reply guidance.
            practicalPlanning,
        )).filter(String::isNotBlank).joinToString("\n\n") +
            if (WorkspaceRichBlocksContract.enabled(extra))
                "\n\nFINAL RESPONSE FORMAT: Follow LYRA_RICH_BLOCKS_V1 JSON ONLY, overriding earlier Markdown formatting suggestions." else ""
        if (instructions.isNotBlank()) entries.put(JSONObject().put("role", "system")
            .put("content", instructions))
        recent.forEachIndexed { index, message ->
            require(message.role == "user" || message.role == "assistant") { "Invalid chat role" }
            require(message.text.length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) { "Invalid message size" }
            val media = listOfNotNull(image) + images
            val content: Any = if ((media.isNotEmpty() || video != null || audio != null) &&
                index == recent.lastIndex) {
                JSONArray().put(JSONObject().put("type", "text").put("text", message.text)).apply {
                    media.forEach { frame ->
                        put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject()
                                .put("url", "data:${frame.mime};base64,${frame.base64}")))
                    }
                    video?.let { original ->
                        put(JSONObject().put("type", "video_url")
                            .put("video_url", JSONObject()
                                .put("url", "data:${original.mime};base64,${original.base64}")))
                    }
                    audio?.let { sample ->
                        put(JSONObject().put("type", "input_audio")
                            .put("input_audio", JSONObject()
                                .put("data", sample.base64)
                                .put("format", if (sample.mime.contains("wav")) "wav" else "mp3")))
                    }
                }
            } else message.text
            entries.put(JSONObject().put("role", message.role).put("content", content))
        }
        return entries
    }

    fun openRouterBody(
        messages: List<WorkspaceConversationStore.Message>,
        image: Image? = null,
        extraSystemInstructions: String? = null,
        images: List<Image> = emptyList(),
        video: NativeVideo? = null,
        audio: Audio? = null,
    ): String {
        val entries = openAiMessages(messages, image, extraSystemInstructions,
            images = images, video = video, audio = audio)
        return JSONObject().put("model", WorkspaceFreeAiSuggestion.MODEL)
            .put("stream", WorkspaceRichBlocksContract.enabled(extraSystemInstructions)).put("max_tokens", 2_048)
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

    fun client(provider: Provider): OkHttpClient = when (provider) {
        Provider.LLM7_FREE -> WorkspaceLlm7Free.client
        else -> client
    }

    fun networkFailure(provider: Provider, error: IOException): String = when (provider) {
        Provider.LLM7_FREE -> WorkspaceLlm7Free.networkFailure(error)
        else -> WorkspaceFreeAiSuggestion.networkFailure(error)
    }

    fun read(provider: Provider, response: Response): String = when (provider) {
        Provider.OPENROUTER_FREE -> WorkspaceFreeAiSuggestion.readResponse(response)
        Provider.GROQ_FREE -> WorkspaceGroqFree.read(response)
        Provider.LLM7_FREE -> WorkspaceLlm7Free.read(response)
    }
}
