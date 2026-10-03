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

    /** Derive a visual solely from actual bullet text when the model omitted visuals. */
    private fun ensureVisual(blocks: List<Block>, plan: Boolean, changes: MutableList<String>):
        List<Block> {
        if (!plan || blocks.any(::isVisual)) return blocks
        val firstList = blocks.indexOfFirst { it is Block.Bullets }
        if (firstList < 0) return blocks
        val source = blocks[firstList] as Block.Bullets
        val rows = source.items.take(3).mapIndexed { i, item ->
            listOf((i + 1).toString(), line(item, 80))
        }
        changes += "existing list presented as two-column table (no new factual content)"
        return blocks.toMutableList().apply {
            set(firstList, Block.Table(listOf("Item", "Action"), rows))
        }
    }

    fun compact(raw: String, plan: Boolean): Result {
        if (!RichBlockParser.isEnvelope(raw)) {
            if (plan && !RichBlockParser.looksLikeEnvelope(raw)) {
                // An unstructured provider reply can still become a faithful visual
                // using only its own existing lines; no new recommendation or fact.
                val lines = raw.lines().map(String::trim).filter(String::isNotBlank)
                val bullets = lines.mapNotNull {
                    Regex("""^(?:[-*•]|\d+[.)])\s+(.+)$""").matchEntire(it)
                        ?.groupValues?.get(1)
                }
                val data = if (bullets.isNotEmpty()) bullets else lines.filterNot {
                    it.startsWith("#")
                }
                if (data.isNotEmpty()) {
                    val title = lines.firstOrNull { it.startsWith("#") }
                        ?.trimStart('#', ' ')?.take(70) ?: "Plan highlights"
                    val generated = listOf(
                        Block.Heading("", title),
                        Block.Table(listOf("Item", "Action"),
                            data.take(3).mapIndexed { i, item ->
                                listOf((i + 1).toString(), line(item, 70))
                            }),
                    )
                    return Result(serialize(generated), listOf("markdown/plain"),
                        generated.map(::type), listOf(
                            "existing model words presented as a table; no facts added"))
                }
            }
            return Result(raw, emptyList(), emptyList(),
                listOf("Provider did not supply a valid rich JSON envelope"))
        }
        val source = RichBlockParser.parse(raw)
        if (source.isEmpty()) return Result(raw, emptyList(), emptyList(),
            listOf("No valid blocks"))
        val changes = mutableListOf<String>()
        val before = source.map(::type)
        // FIRST pass: trim the cheapest text (bullets) before touching visual blocks.
        var blocks: List<Block> = source.map { b ->
            when (b) {
                is Block.Bullets -> {
                    val items = b.items.take(3).map { words(it, 12).take(85).trim() }
                    if (items != b.items) changes += "list text/items shortened first"
                    Block.Bullets(items)
                }
                else -> b
            }
        }
        // SECOND pass: compact other prose and enforce strict visual shape limits.
        blocks = blocks.map { b ->
            when (b) {
                is Block.Text -> b.copy(text = line(b.text, 130))
                is Block.Heading -> b.copy(text = line(b.text, 76))
                is Block.Bullets -> b
                is Block.MockupCard -> b.copy(
                    title = line(b.title, 52),
                    items = b.items.take(4).map { line(it, 28) },
                )
                is Block.AppCards -> b.copy(items = b.items.take(3).map { (n, v) ->
                    line(n, 50) to words(v, 5).take(65)
                })
                is Block.Table -> b.copy(
                    columns = b.columns.take(2).map { line(it, 34) },
                    rows = b.rows.take(3).map { row -> row.take(2).map { line(it, 55) } },
                )
                is Block.Callout -> b.copy(label = line(b.label, 28),
                    text = line(b.text, 115))
                is Block.ImageRow -> b.copy(query = line(b.query, 70),
                    caption = line(b.caption, 75))
                is Block.Options -> b.copy(question = line(b.question, 90),
                    choices = b.choices.map { line(it, 50) })
                Block.Divider -> b
            }
        }
        if (blocks != source) changes += "text/visual item limits applied"
        blocks = ensureVisual(blocks, plan, changes)
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
        // Visuals are the LAST removable class, and never remove the final visual
        // from a plan. Normal small plans will not need this branch.
        if (serialize(blocks).length > TARGET_CHARS) {
            val mutable = blocks.toMutableList()
            while (serialize(mutable).length > TARGET_CHARS &&
                mutable.count(::isVisual) > if (plan) 1 else 0) {
                val index = mutable.indexOfLast(::isVisual)
                if (index < 0) break
                changes += "last-resort removed: " + type(mutable[index])
                mutable.removeAt(index)
            }
            blocks = mutable
        }
        // We do not silently discard the final visual block to reach a size target.
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
