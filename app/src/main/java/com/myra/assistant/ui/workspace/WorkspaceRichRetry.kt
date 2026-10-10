package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject

/**
 * One explicit same-request, same-provider short retry after a completed HTTP stream
 * reaches our local response budget. It never changes the model, endpoint, key,
 * original user message, runtime truth, provider consent, permissions or cost policy.
 */
internal object WorkspaceRichRetry {
    private val SHORT_CONTRACT = """
        LYRA_RICH_BLOCKS_V1: return ONLY valid JSON {"blocks":[...]}.
        This is a compact natural ChatGPT-style answer, not a cards dashboard.
        Every object has type. Allowed fields:
        text(style:"opener"|"body"|"closer",text); heading(emoji,text);
        list(items); table(columns,rows); app_cards(items:[{name,note}]);
        callout(label,text); mockup_card(title,items,layout:"grid"|"list");
        divider; image_row(query,caption); options(question,choices).
        Honor latest user's exact N steps with N brief headings and their
        own natural content: prose/list, actual comparison table or inline
        app-logo rows if relevant. A visual is OPTIONAL. Do not add fake tables.
        No mockup_card unless user explicitly asks for a rough screen sketch.
        Keep total blocks minimal (about seven), text short but complete;
        table up to five useful rows, two columns; no oversized visual panels.
        Use plain "Baaki"/"Ho gaya" for status, not colored status emoji.
        Free phone-only examples: Google Keep for planning NOW,
        SPCK Editor code LATER, Chrome preview LATER; obey no-code requests.
        Example prices SAMPLE, never verified/live. End naturally with
        "Meri advice:" if appropriate. No invented actions or paid providers.
        The format example is not a mandatory sequence:
        {"blocks":[{"type":"heading","text":"1. Basic features"},
         {"type":"list","items":["Home, products and cart","Simple search"]},
         {"type":"heading","text":"2. Sample items"},
         {"type":"table","columns":["Item","Sample ₹"],"rows":[["Rice","65"],["Milk","30"]]},
         {"type":"heading","text":"3. Phone tools"},
         {"type":"app_cards","items":[{"name":"Google Keep","note":"Plan today"}]},
         {"type":"text","style":"closer","text":"Meri advice: notes first."}]}
        Natural Roman Hinglish; only opener/closer spoken.
        Latest request, consent, runtime, privacy/security and Free gates prevail.
    """.trimIndent()

    fun request(original: Request): Request? = runCatching {
        if (original.header("X-Lyra-Rich-Short-Retry") == "1") return@runCatching null
        val buffer = Buffer()
        original.body?.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        if (!json.optBoolean("stream")) return@runCatching null
        val messages = json.optJSONArray("messages") ?: return@runCatching null
        val system = messages.optJSONObject(0) ?: return@runCatching null
        if (system.optString("role") != "system") return@runCatching null
        val content = system.optString("content")
        val full = WorkspaceRichBlocksContract.INSTRUCTIONS
        val compact = WorkspaceRichBlocksContract.COMPACT_GROQ_INSTRUCTIONS
        val updated = when {
            content.contains(full) -> content.replace(full, SHORT_CONTRACT)
            content.contains(compact) -> content.replace(compact, SHORT_CONTRACT)
            else -> return@runCatching null
        }
        system.put("content", updated)
        // Use the exact same provider/model, same latest user message and all
        // zero-price/ZDR/fallback restrictions from the original request body.
        if (json.has("max_completion_tokens"))
            json.put("max_completion_tokens", 1050)
        if (json.has("max_tokens"))
            json.put("max_tokens", 1050)
        original.newBuilder()
            .header("X-Lyra-Rich-Short-Retry", "1")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }.getOrNull()
}
