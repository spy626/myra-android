package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Final presentation compaction only. Never changes source transcript, model evidence,
 * provider selection or execution policy. Trims bullets before shortening visuals.
 */
internal object WorkspaceRichOutputBudget {
    private const val TARGET_CHARS = 4_200
    data class Result(
        val raw: String,
        val before: List<String>,
        val after: List<String>,
        val changes: List<String>,
    )
    internal fun isVisual(block: Block): Boolean =
        block is Block.Table || block is Block.AppCards || block is Block.MockupCard

    private fun words(value: String, count: Int): String =
        value.replace(Regex("""\s+"""), " ").trim().split(" ")
            .filter(String::isNotBlank).take(count).joinToString(" ")

    private fun line(value: String, chars: Int): String =
        value.replace(Regex("""\s+"""), " ").trim().take(chars).trim()

    private fun type(b: Block): String = when (b) {
        is Block.Text -> "text"
        is Block.Heading -> "heading"
        is Block.Bullets -> "list"
        is Block.Table -> "table"
        is Block.AppCards -> "app_cards"
        is Block.MockupCard -> "mockup_card"
        is Block.Callout -> "callout"
        is Block.ImageRow -> "image_row"
        is Block.Options -> "options"
        Block.Divider -> "divider"
    }

    private fun serialize(blocks: List<Block>): String {
        val a = JSONArray()
        blocks.forEach { b ->
            val o = JSONObject().put("type", type(b))
            when (b) {
                is Block.Text -> o.put("style", b.style).put("text", b.text)
                is Block.Heading -> o.put("emoji", b.emoji).put("text", b.text)
                is Block.Bullets -> o.put("items", JSONArray(b.items))
                is Block.Table -> o.put("columns", JSONArray(b.columns))
                    .put("rows", JSONArray().apply {
                        b.rows.forEach { put(JSONArray(it)) }
                    })
                is Block.AppCards -> o.put("items", JSONArray().apply {
                    b.items.forEach { (name, note) ->
                        put(JSONObject().put("name", name).put("note", note))
                    }
                })
                is Block.MockupCard -> o.put("title", b.title)
                    .put("layout", b.layout).put("items", JSONArray(b.items))
                is Block.Callout -> o.put("label", b.label).put("text", b.text)
                is Block.ImageRow -> o.put("query", b.query).put("caption", b.caption)
                is Block.Options -> o.put("question", b.question)
                    .put("choices", JSONArray(b.choices))
                Block.Divider -> Unit
            }
            a.put(o)
        }
        return JSONObject().put("blocks", a).toString()
    }

    fun compact(raw: String, plan: Boolean): Result {
        // Plain Markdown/prose stays plain. Never manufacture a visual/table
        // out of genuine user-facing bullets merely to satisfy an old prompt rule.
        if (!RichBlockParser.isEnvelope(raw))
            return Result(raw, emptyList(), emptyList(), emptyList())
        val source = RichBlockParser.parse(raw)
        if (source.isEmpty()) return Result(raw, emptyList(), emptyList(),
            listOf("No valid blocks"))
        val changes = mutableListOf<String>()
        val before = source.map(::type)
        // Normal replies must not be chopped or rearranged. Preserve the author's
        // five-item tables, natural prose and list lengths when under budget.
        if (raw.length <= TARGET_CHARS)
            return Result(raw, before, before, emptyList())
        // For exceptional oversized replies, trim lengthy prose before other blocks.
        var blocks: List<Block> = source.map { b ->
            when (b) {
                is Block.Bullets -> {
                    val items = b.items.take(7).map { words(it, 18).take(130).trim() }
                    if (items != b.items) changes += "list text/items shortened first"
                    Block.Bullets(items)
                }
                else -> b
            }
        }
        // SECOND pass: compact other prose and enforce strict visual shape limits.
        blocks = blocks.map { b ->
            when (b) {
                is Block.Text -> b.copy(text = line(b.text, 210))
                is Block.Heading -> b.copy(text = line(b.text, 95))
                is Block.Bullets -> b
                is Block.MockupCard -> b.copy(
                    title = line(b.title, 52),
                    items = b.items.take(4).map { line(it, 28) },
                )
                is Block.AppCards -> b.copy(items = b.items.take(3).map { (n, v) ->
                    line(n, 50) to words(v, 12).take(95)
                })
                is Block.Table -> b.copy(
                    columns = b.columns.take(2).map { line(it, 34) },
                    rows = b.rows.take(6).map { row -> row.take(2).map { line(it, 68) } },
                )
                is Block.Callout -> b.copy(label = line(b.label, 32),
                    text = line(b.text, 170))
                is Block.ImageRow -> b.copy(query = line(b.query, 70),
                    caption = line(b.caption, 75))
                is Block.Options -> b.copy(question = line(b.question, 90),
                    choices = b.choices.map { line(it, 50) })
                Block.Divider -> b
            }
        }
        if (blocks != source) changes += "text/visual item limits applied"
        // A prose-only plan is valid. No compulsory visual or fake table.
        // THIRD pass: compact bullets further before EVER considering removal of visuals.
        if (serialize(blocks).length > TARGET_CHARS) {
            blocks = blocks.map { b ->
                if (b is Block.Bullets) b.copy(items = b.items.take(2).map { words(it, 7) })
                else b
            }
            changes += "long bullets shortened again before any visual removal"
        }
        if (serialize(blocks).length > TARGET_CHARS) {
            blocks = blocks.map { b ->
                if (b is Block.Bullets) b.copy(items = b.items.take(1)
                    .map { words(it, 5) }) else b
            }
            changes += "nonvisual bullet content reduced before visual removal"
        }
        if (serialize(blocks).length > TARGET_CHARS) {
            blocks = blocks.filterNot { it is Block.Divider || it is Block.ImageRow }
            changes += "optional divider/image placeholder removed; visual cards retained"
        }
        // Optional visuals are the LAST removable class, never mandatory.
        // Normal prose-first answers almost never enter this oversized branch.
        if (serialize(blocks).length > TARGET_CHARS) {
            val mutable = blocks.toMutableList()
            while (serialize(mutable).length > TARGET_CHARS &&
                mutable.count(::isVisual) > 0) {
                val index = mutable.indexOfLast(::isVisual)
                if (index < 0) break
                changes += "last-resort removed: " + type(mutable[index])
                mutable.removeAt(index)
            }
            blocks = mutable
        }
        // Never discard the user's underlying original reply in storage pre-checks.
        require(serialize(blocks).length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "Compact rich result still exceeds local message limit"
        }
        return Result(serialize(blocks), before, blocks.map(::type), changes.distinct())
    }

    /** Apply the same small card shapes to provisional streamed previews. */
    fun preview(blocks: List<Block>): List<Block> =
        RichBlockParser.parse(compact(serialize(blocks), plan = false).raw)

    fun visualCount(raw: String): Int =
        RichBlockParser.parse(raw).count(::isVisual)
}
