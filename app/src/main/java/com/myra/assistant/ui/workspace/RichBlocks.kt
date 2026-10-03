package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject

/** Strict nine-block presentation model, independent of the Android view toolkit. */
internal sealed class Block {
    data class Text(val style: String, val text: String) : Block()
    data class Heading(val emoji: String, val text: String) : Block()
    data class Bullets(val items: List<String>) : Block()
    data class Table(val columns: List<String>, val rows: List<List<String>>) : Block()
    data class ImageRow(val query: String, val caption: String) : Block()
    data class AppCards(val items: List<Pair<String, String>>) : Block()
    data class Callout(val label: String, val text: String) : Block()
    object Divider : Block()
    data class Options(val question: String, val choices: List<String>) : Block()
}

/** The model knows the schema, never the Android UI or any execution capability. */
internal object WorkspaceRichBlocksContract {
    const val MARKER = "LYRA_RICH_BLOCKS_V1"
    val INSTRUCTIONS = """
        LYRA_RICH_BLOCKS_V1 — For this normal chat turn, output ONLY valid JSON:
        {"blocks":[...]}. No markdown fences, commentary outside JSON, tools, actions or extra keys.
        Types and fields ONLY:
        text {type:"text",style:"opener"|"closer",text:string};
        heading {type:"heading",emoji:string,text:string};
        list {type:"list",items:[string]};
        table {type:"table",columns:[string],rows:[[string]]};
        image_row {type:"image_row",query:string,caption:string};
        app_cards {type:"app_cards",items:[{name:string,note:string}]};
        callout {type:"callout",label:string,text:string};
        divider {type:"divider"};
        options {type:"options",question:string,choices:[string]}.
        Casual hi, how are you, short talk: one short opener text block only.
        For plans, comparisons, how-to or advice: 3–6 useful blocks, first a short friendly
        opener and last a short actionable closer. Vary block type/order naturally.
        Tables ONLY for real comparable data, lists ONLY for steps or points.
        image_row is an image SEARCH QUERY ONLY (never generate or claim to have seen an image).
        app_cards ONLY when suggesting actual apps, tools or products.
        options ONLY for a genuine user decision, 2–4 choices.
        callout ONLY for one essential warning or tip.
        Roman Hinglish, natural friendly tone, occasional light joke, factual accuracy.
        Keep opener/closer brief: speech will use ONLY those two fields.
        Never describe UI implementation, code or JSON to the user.
        Latest user instructions, privacy, source grounding, tool/execution boundaries and
        no-implementation requests remain authoritative. Use JSON only for PRESENTATION.
    """.trimIndent()
    fun enabled(extra: String?): Boolean = extra?.contains(MARKER) == true
}

internal object RichBlockParser {
    private fun clean(raw: String): String = raw.trim()
        .removePrefix("&#65279;").removePrefix("&#xFEFF;")
        .removePrefix("&#xfeff;").removePrefix("\uFEFF")
        .removePrefix("&#96;&#96;&#96;json")
        .removePrefix("\u0060\u0060\u0060json").removePrefix("\u0060\u0060\u0060")
        .removeSuffix("\u0060\u0060\u0060").trim()

    fun isEnvelope(raw: String): Boolean = runCatching {
        JSONObject(clean(raw)).optJSONArray("blocks") != null
    }.getOrDefault(false)

    fun looksLikeEnvelope(raw: String): Boolean =
        clean(raw).startsWith("{") && clean(raw).contains("\"blocks\"")

    fun parse(raw: String): List<Block> {
        val document = runCatching { JSONObject(clean(raw)) }.getOrNull()
            ?: return if (looksLikeEnvelope(raw)) emptyList() else fallback(raw)
        val entries = document.optJSONArray("blocks") ?: return fallback(raw)
        return (0 until minOf(entries.length(), 24)).mapNotNull {
            parseBlock(entries.optJSONObject(it))
        }
    }

    fun parseBlock(o: JSONObject?): Block? {
        if (o == null) return null
        return runCatching {
            when (o.optString("type")) {
                "text" -> {
                    val style = o.optString("style")
                    if (style !in setOf("opener", "closer")) null
                    else required(o, "text")?.let { Block.Text(style, it) }
                }
                "heading" -> required(o, "text")?.let {
                    Block.Heading(o.optString("emoji").take(8), it)
                }
                "list" -> strings(o.optJSONArray("items")).takeIf { it.isNotEmpty() }
                    ?.let(Block::Bullets)
                "table" -> {
                    val columns = strings(o.optJSONArray("columns"))
                    val rows = o.optJSONArray("rows")
                    if (columns.size !in 2..6 || rows == null) null
                    else {
                        val validRows = (0 until minOf(rows.length(), 24)).mapNotNull { index ->
                            rows.optJSONArray(index)?.let(::strings)?.takeIf {
                                it.size == columns.size
                            }
                        }
                        if (validRows.isEmpty()) null else Block.Table(columns, validRows)
                    }
                }
                "image_row" -> required(o, "query")?.let {
                    Block.ImageRow(it, o.optString("caption").take(240))
                }
                "app_cards" -> {
                    val a = o.optJSONArray("items")
                    val cards = if (a == null) emptyList() else (0 until minOf(a.length(), 12))
                        .mapNotNull { index ->
                            a.optJSONObject(index)?.let { card ->
                                required(card, "name")?.let { name ->
                                    name to card.optString("note").take(240)
                                }
                            }
                        }
                    cards.takeIf { it.isNotEmpty() }?.let(Block::AppCards)
                }
                "callout" -> required(o, "text")?.let {
                    Block.Callout(o.optString("label").take(70), it)
                }
                "divider" -> Block.Divider
                "options" -> {
                    val question = required(o, "question")
                    val choices = strings(o.optJSONArray("choices")).distinct()
                    if (question == null || choices.size !in 2..4) null
                    else Block.Options(question, choices)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun required(o: JSONObject, key: String): String? =
        (o.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4000 }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until minOf(a.length(), 32)).mapNotNull { index ->
            (a.opt(index) as? String)?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 4000
            }
        }
    }

    private fun fallback(raw: String): List<Block> =
        raw.trim().takeIf(String::isNotEmpty)?.let { listOf(Block.Text("opener", it)) }
            ?: emptyList()

    /** Spoken output is NEVER a table, card, heading, image, callout or option. */
    fun spokenText(blocks: List<Block>): String = blocks.filterIsInstance<Block.Text>()
        .filter { it.style == "opener" || it.style == "closer" }
        .joinToString(" ") { it.text }

    /** Plain content for existing conservative final-turn checks, not a second AI prompt. */
    fun visibleText(blocks: List<Block>): String = blocks.joinToString("\n") {
        when (it) {
            is Block.Text -> it.text
            is Block.Heading -> it.text
            is Block.Bullets -> it.items.joinToString("\n")
            is Block.Table -> (listOf(it.columns) + it.rows).joinToString("\n") { r ->
                r.joinToString(" | ")
            }
            is Block.ImageRow -> it.caption
            is Block.AppCards -> it.items.joinToString("\n") { card ->
                card.first + ": " + card.second
            }
            is Block.Callout -> it.label + " " + it.text
            Block.Divider -> ""
            is Block.Options -> it.question + " " + it.choices.joinToString(" / ")
        }
    }
}

/** Complete, syntactically closed objects only. No raw partial JSON is ever rendered. */
internal class RichBlockIncrementalParser {
    private var delivered = 0

    fun update(raw: String): List<Block> {
        val prefix = Regex("\"blocks\"\\s*:\\s*\\[").find(raw) ?: return emptyList()
        val found = mutableListOf<Block>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        var ended = false
        for (index in prefix.range.last + 1 until raw.length) {
            val char = raw[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        found += RichBlockParser.parseBlock(runCatching {
                            JSONObject(raw.substring(start, index + 1))
                        }.getOrNull()) ?: continue
                        start = -1
                    }
                }
                ']' -> if (depth == 0) { ended = true; break }
            }
        }
        if (found.size < delivered) return emptyList()
        val new = found.drop(delivered)
        delivered = found.size
        return new
    }
}
