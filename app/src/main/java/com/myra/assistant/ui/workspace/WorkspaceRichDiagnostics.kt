package com.myra.assistant.ui.workspace

import android.content.Context
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject

/**
 * Device-private reply inspection for the user. No raw transcripts, API credentials,
 * saved memory, or full request payload go to Logcat/CI or diagnostics preferences.
 * Raw provider text is read from the EXISTING selected-chat conversation record.
 */
internal object WorkspaceRichDiagnostics {
    private const val PREFS = "workspace_rich_request_trace_v1"

    data class Trace(
        val richPromptInOutboundBody: Boolean,
        val streamRequested: Boolean,
        val rulesPresent: Boolean,
        val route: String,
    ) {
        fun display(): String =
            "Route: " + route + "\n" +
                "Rich prompt in outgoing request: " +
                (if (richPromptInOutboundBody) "YES" else "NO") + "\n" +
                "Step/visual presentation rules present: " +
                (if (rulesPresent) "YES" else "NO") + "\n" +
                "Streaming requested: " + (if (streamRequested) "YES" else "NO")
    }

    /** Verifies the assembled HTTP JSON body itself, not merely a local enable flag. */
    internal fun inspect(request: Request, route: String): Trace {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val first = json.optJSONArray("messages")?.optJSONObject(0)
        val prompt = if (first?.optString("role") == "system") first.optString("content") else ""
        return Trace(
            richPromptInOutboundBody = prompt.contains(WorkspaceRichBlocksContract.MARKER),
            streamRequested = json.optBoolean("stream", false),
            rulesPresent = prompt.contains("LYRA_RICH_BLOCKS_V1") &&
                prompt.contains("callout") && prompt.contains("heading") &&
                prompt.contains("mockup_card") && prompt.contains("Meri advice:") &&
                (prompt.contains("Each step MUST have its OWN heading block") ||
                    prompt.contains("Do NOT compress three steps into one list")),
            route = route.take(64),
        )
    }

    fun record(context: Context, sourceUserTurnId: String, request: Request, route: String) {
        val trace = runCatching { inspect(request, route) }.getOrNull() ?: return
        val serialized = JSONObject()
            .put("marker", trace.richPromptInOutboundBody)
            .put("stream", trace.streamRequested)
            .put("rules", trace.rulesPresent)
            .put("route", trace.route)
            .toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(sourceUserTurnId, serialized).apply()
    }

    fun show(context: Context, sourceUserTurnId: String?, rawResponse: String): String {
        val saved = sourceUserTurnId?.let {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(it, null)
        }
        val trace = saved?.let { runCatching {
            val json = JSONObject(it)
            Trace(json.optBoolean("marker"), json.optBoolean("stream"),
                json.optBoolean("rules"), json.optString("route"))
        }.getOrNull() }
        val shape = when {
            RichBlockParser.isEnvelope(rawResponse) -> "JSON blocks"
            RichBlockParser.looksLikeEnvelope(rawResponse) -> "Incomplete/invalid JSON blocks"
            else -> "Markdown/plain text"
        }
        val parsed = if (RichBlockParser.isEnvelope(rawResponse))
            RichBlockParser.parse(rawResponse) else emptyList()
        val incomingCount = runCatching {
            JSONObject(rawResponse.trim()).optJSONArray("blocks")?.length()
        }.getOrNull()
        val countNote = incomingCount?.let {
            "\nBlocks in raw model JSON: " + it +
                "\nBlocks accepted by parser: " + parsed.size +
                "\nBlocks skipped as invalid/unknown: " + (it - parsed.size)
        }.orEmpty()
        return (trace?.display()
            ?: "Original request trace unavailable (older app build or local-only answer).") +
            "\nActual saved provider reply format: " + shape + countNote +
            "\n\nRAW SAVED ASSISTANT CONTENT (verbatim):\n" + rawResponse
    }
}
