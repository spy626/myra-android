package com.myra.assistant.ui.workspace

import android.content.Context
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/** Private metadata only. No request bodies, credentials, transcripts or partial reply text. */
internal object WorkspaceRichDiagnostics {
    private const val PREFS = "workspace_rich_request_trace_v1"
    data class Trace(
        val richPromptInOutboundBody: Boolean,
        val streamRequested: Boolean,
        val rulesPresent: Boolean,
        val route: String,
        val model: String = "unavailable",
    ) {
        fun display(): String = "Route: " + route + "\nModel: " + model +
            "\nRich prompt: " + richPromptInOutboundBody +
            "\nStep/visual rules: " + rulesPresent +
            "\nStreaming: " + streamRequested
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun inspect(request: Request, route: String): Trace {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val first = json.optJSONArray("messages")?.optJSONObject(0)
        val prompt = if (first?.optString("role") == "system")
            first.optString("content") else ""
        return Trace(
            richPromptInOutboundBody = prompt.contains(WorkspaceRichBlocksContract.MARKER),
            streamRequested = json.optBoolean("stream"),
            rulesPresent = prompt.contains(WorkspaceRichBlocksContract.MARKER) &&
                prompt.contains("mockup_card") && prompt.contains("app_cards") &&
                prompt.contains("table") && prompt.contains("Meri advice:"),
            route = route.take(64),
            model = json.optString("model", "unavailable").take(100),
        )
    }

    fun record(context: Context, sourceUserTurnId: String, request: Request, route: String,
               retry: Boolean = false) {
        val trace = runCatching { inspect(request, route) }.getOrNull() ?: return
        val root = if (retry) runCatching {
            JSONObject(prefs(context).getString(sourceUserTurnId, "{}")!!)
        }.getOrElse { JSONObject() } else JSONObject()
        val attempts = root.optJSONArray("attempts") ?: JSONArray()
        attempts.put(JSONObject()
            .put("number", attempts.length() + 1)
            .put("route", trace.route)
            .put("model", trace.model)
            .put("endpoint", request.url.host.take(80))
            .put("rich", trace.richPromptInOutboundBody)
            .put("rules", trace.rulesPresent)
            .put("stream", trace.streamRequested)
            .put("mode", if (retry) "short retry, once" else "original")
            .put("status", "request prepared"))
        root.put("attempts", attempts)
        prefs(context).edit().putString(sourceUserTurnId, root.toString()).apply()
    }

    private fun updateLast(context: Context, id: String, apply: (JSONObject) -> Unit) {
        val root = runCatching {
            JSONObject(prefs(context).getString(id, "{}")!!)
        }.getOrNull() ?: return
        val attempts = root.optJSONArray("attempts") ?: return
        val last = attempts.optJSONObject(attempts.length() - 1) ?: return
        apply(last)
        prefs(context).edit().putString(id, root.toString()).apply()
    }

    fun actual(context: Context, id: String, request: Request) {
        val trace = runCatching { inspect(request, request.url.host) }.getOrNull() ?: return
        updateLast(context, id) {
            it.put("actual_endpoint", request.url.host.take(80))
                .put("actual_model", trace.model)
        }
    }

    fun metrics(context: Context, id: String, value: WorkspaceRichResponse.Metrics) {
        updateLast(context, id) {
            it.put("sse_transport_chars", value.wireChars)
                .put("model_content_chars", value.contentChars)
                .put("sse_events", value.eventCount)
        }
    }

    fun failure(context: Context, id: String, cause: String) {
        updateLast(context, id) { it.put("status", cause.take(120)) }
    }

    fun output(context: Context, id: String, result: WorkspaceRichOutputBudget.Result) {
        val remaining = result.after.toMutableList()
        val removed = result.before.filter { type -> !remaining.remove(type) }
        updateLast(context, id) {
            it.put("removed_block_types", JSONArray(removed))
                .put("status", "complete")
                .put("raw_block_types", JSONArray(result.before))
                .put("saved_block_types", JSONArray(result.after))
                .put("saved_content_chars", result.raw.length)
                .put("compaction", JSONArray(result.changes))
                .put("visuals_after", WorkspaceRichOutputBudget.visualCount(result.raw))
        }
    }

    fun show(context: Context, sourceUserTurnId: String?, rawResponse: String): String {
        val saved = sourceUserTurnId?.let { prefs(context).getString(it, null) }
        val root = saved?.let { runCatching { JSONObject(it) }.getOrNull() }
        val attempts = root?.optJSONArray("attempts")
        val prefix = if (attempts != null) buildString {
            for (i in 0 until attempts.length()) {
                val a = attempts.optJSONObject(i) ?: continue
                append("Attempt " + a.optInt("number", i + 1) + ": " +
                    a.optString("mode") + "\n")
                append("Provider route: " + a.optString("route", "unavailable") + "\n")
                append("Request model: " + a.optString("model", "unavailable") + "\n")
                append("Actual endpoint: " + a.optString("actual_endpoint",
                    a.optString("endpoint")) + "\n")
                append("Actual model: " + a.optString("actual_model", "unavailable") + "\n")
                append("Result: " + a.optString("status", "unavailable") + "\n")
                append("SSE transport characters: " +
                    (if (a.has("sse_transport_chars")) a.optInt("sse_transport_chars")
                     else "not captured") + "\n")
                append("Model content characters: " +
                    (if (a.has("model_content_chars")) a.optInt("model_content_chars")
                     else "not captured") + "\n")
                append("SSE events: " + (if (a.has("sse_events"))
                    a.optInt("sse_events") else "not captured") + "\n")
                append("Raw block types: " + (a.optJSONArray("raw_block_types")
                    ?: "not captured") + "\n")
                append("Saved block types: " + (a.optJSONArray("saved_block_types")
                    ?: "not captured") + "\n")
                append("Blocks removed by output compactor: " +
                    (a.optJSONArray("removed_block_types") ?: "not captured") + "\n")
                append("Compaction/truncation: " + (a.optJSONArray("compaction")
                    ?: "not captured") + "\n")
                append("Visual blocks saved: " + (if (a.has("visuals_after"))
                    a.optInt("visuals_after") else "not captured") + "\n\n")
            }
        } else "Per-attempt provider/model/size logs unavailable in this older build.\n\n"
        val shape = when {
            RichBlockParser.isEnvelope(rawResponse) -> "JSON blocks"
            RichBlockParser.looksLikeEnvelope(rawResponse) -> "Incomplete/invalid JSON"
            else -> "Markdown/plain text"
        }
        val parsed = if (RichBlockParser.isEnvelope(rawResponse))
            RichBlockParser.parse(rawResponse) else emptyList()
        val incoming = runCatching {
            JSONObject(rawResponse.trim()).optJSONArray("blocks")?.length()
        }.getOrNull()
        val summary = incoming?.let {
            "\nRaw blocks: " + it + "; accepted: " + parsed.size +
                "; invalid/unknown: " + (it - parsed.size)
        }.orEmpty()
        return prefix + "Saved format: " + shape + summary +
            "\n\nRAW SAVED ASSISTANT CONTENT (verbatim):\n" + rawResponse
    }
}
