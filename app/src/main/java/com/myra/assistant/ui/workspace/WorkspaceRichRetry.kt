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
        LYRA_RICH_BLOCKS_V1: Return ONLY valid JSON {"blocks":[...]}; no external text.
        Keep EXACT latest user instructions, no-code, phone-only, privacy and free rules.
        Very small rich reply, maximum 8 compact blocks. If N steps are requested,
        use N separate heading blocks, each followed by one brief content block.
        Never lose all visuals: at least one mockup_card, app_cards or table.
        Choose types independently by step purpose, not in fixed example order.
        Prefer a TWO-column TABLE for comparison/checklist/sample data.
        mockup_card(title,items:[2-4 short strings],layout:"grid"|"list");
        app_cards(items:[{name,note}]) max 2, each note max FIVE words.
        table(columns:[2 strings],rows:[up to 3 pairs]); callout(label,text) one line;
        list(items:[short strings, each max 8 words]); heading(emoji,text).
        text(style:"opener"|"closer",text) one line; divider allowed.
        Never fabricate live prices: label examples SAMPLE. Do not use long bullets.
        Full JSON example is NOT a fixed order; change blocks to fit this request:
        {"blocks":[{"type":"heading","text":"Step 1 — Screens"},
          {"type":"mockup_card","title":"Layout","layout":"grid","items":["Home","Cart"]},
          {"type":"heading","text":"Step 2 — Tools"},
          {"type":"app_cards","items":[{"name":"Google Keep","note":"Plan now"}]},
          {"type":"heading","text":"Step 3 — Samples"},
          {"type":"table","columns":["Item","Sample ₹"],"rows":[["Rice","65"]]},
          {"type":"text","style":"closer","text":"Meri advice: notes first."}]}
        Natural Roman Hinglish; no invented actions or execution claims.
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
