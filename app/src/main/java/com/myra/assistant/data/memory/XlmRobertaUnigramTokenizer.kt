package com.myra.assistant.data.memory

import java.text.Normalizer
import org.json.JSONObject

/** Deterministic XLM-R/SentencePiece unigram inference for the pinned E5 tokenizer. */
class XlmRobertaUnigramTokenizer private constructor(private val root: Node, private val unknownId: Long) {
    private class Node(val next: MutableMap<Char, Node> = hashMapOf(), var id: Long? = null, var score: Double = 0.0)
    private data class Step(val score: Double, val previous: Int, val id: Long)

    fun encode(input: String, maxTokens: Int): LongArray {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).trim()
            .replace(Regex("\\s+"), " ").replace(" ", "▁").let { "▁$it" }
        val best = arrayOfNulls<Step>(normalized.length + 1); best[0] = Step(0.0, -1, -1)
        var position = 0
        while (position < normalized.length) {
            val base = best[position]
            if (base != null) {
                var node = root; var cursor = position
                while (cursor < normalized.length) {
                    node = node.next[normalized[cursor]] ?: break; cursor++
                    node.id?.let { id ->
                        val score = base.score + node.score
                        if (best[cursor] == null || score > best[cursor]!!.score) best[cursor] = Step(score, position, id)
                    }
                }
                val next = position + Character.charCount(Character.codePointAt(normalized, position))
                if (best[next] == null) best[next] = Step(base.score - 100.0, position, unknownId)
            }
            position++
        }
        val reversed = mutableListOf<Long>(); var cursor = normalized.length
        while (cursor > 0) { val step = best[cursor] ?: break; reversed += step.id; cursor = step.previous }
        val pieces = reversed.asReversed().take((maxTokens - 2).coerceAtLeast(0))
        return (listOf(0L) + pieces + 2L).toLongArray()
    }

    companion object {
        fun fromJson(json: String): XlmRobertaUnigramTokenizer {
            val model = JSONObject(json).getJSONObject("model")
            require(model.getString("type") == "Unigram")
            val root = Node(); val vocab = model.getJSONArray("vocab")
            for (id in 0 until vocab.length()) {
                val entry = vocab.getJSONArray(id); val piece = entry.getString(0)
                var node = root; piece.forEach { char -> node = node.next.getOrPut(char) { Node() } }
                node.id = id.toLong(); node.score = entry.getDouble(1)
            }
            return XlmRobertaUnigramTokenizer(root, model.optLong("unk_id", 3L))
        }
    }
}
