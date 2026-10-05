package com.myra.assistant.data.memory

import com.myra.assistant.ai.ApiKeyStore
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale

data class ReviewedBoundary(val afterSequence: Long, val keep: Boolean, val confidence: Double, val reason: String)
data class ConsolidationCandidate(val memoryId: String, val fact: String, val category: String)
data class EpisodeSemanticAction(val kind: SemanticConsolidationAction, val fact: String,
    val category: String, val targetFactId: String?, val confidence: Double,
    val assertionMode: String = "USER_ASSERTED", val criticalLiterals: List<String> = emptyList(),
    val person: String? = null, val relationship: String? = null,
    val semanticRelationship: String? = null, val goalTitle: String? = null,
    val goalStatus: String? = null,
    val sourceMessageSequences: List<Long> = emptyList(), val sourceSpans: List<String> = emptyList())

data class FinalTurnSemanticInterpretation(
    val displayText: String? = null,
    val operations: List<MemorySemanticFrame> = emptyList()
)

object FinalTurnSemanticCandidateGate {
    private val explicitMemory = Regex("\\b(?:remember|memory|save|yaad|yaad\\s+rakh)\\b", RegexOption.IGNORE_CASE)
    private val selfReference = Regex("\\b(?:i|i'm|im|my|me|mujhe|mera|meri|mere|main|hum|ham|hamara|hamari|hamare)\\b", RegexOption.IGNORE_CASE)
    private val questionCue = Regex("\\b(?:kya|kaun|kab|kahan|kahaan|kyun|kaise|what|who|when|where|why|how|which)\\b", RegexOption.IGNORE_CASE)

    fun shouldInterpret(evidence: AuthoritativeMemoryTurnEvidence): Boolean {
        val text = evidence.canonicalText.trim()
        if (text.length !in 4..500) return false
        if (explicitMemory.containsMatchIn(text)) return true
        if (!selfReference.containsMatchIn(text)) return false
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim()
        val tokenCount = normalized.split(' ').count(String::isNotBlank)
        if (tokenCount < 3) return false
        if (text.contains('?') || questionCue.find(normalized)?.range?.first?.let { it < 18 } == true) return false
        return true
    }
}

object FinalTurnDisplayProjectionPolicy {
    fun select(evidence: AuthoritativeMemoryTurnEvidence, proposed: String?): String? {
        val display = proposed?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (display.length !in 2..500) return null
        if (Regex("[\\u0900-\\u097F\\u3400-\\u4DBF\\u4E00-\\u9FFF]").containsMatchIn(display)) return null
        val sourceNumbers = Regex("\\d+(?:[.,:/-]\\d+)*").findAll(evidence.canonicalText).map { it.value }.toList()
        val displayNumbers = Regex("\\d+(?:[.,:/-]\\d+)*").findAll(display).map { it.value }.toList()
        if (sourceNumbers != displayNumbers) return null
        val protected = (evidence.protectedCanonicalNames + evidence.protectedDisplayNames)
            .map(::normalize).filter(String::isNotBlank).distinct()
        val projected = normalize(display)
        if (protected.any { !projected.contains(it) }) return null
        val sourceTokens = normalize(evidence.sourceText).split(' ').filter { it.length >= 2 }
        val projectedTokens = projected.split(' ').filter { it.length >= 2 }
        if (sourceTokens.isEmpty() || projectedTokens.isEmpty()) return null
        val matched = sourceTokens.count { left -> projectedTokens.any { right -> close(left, right) } }
        val comparable = minOf(sourceTokens.size, projectedTokens.size)
        val required = maxOf(1, (comparable * 2 + 4) / 5)
        return display.takeIf { matched >= required }
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 4 || b.length < 4) return false
        val d = distance(a, b)
        return d <= if (maxOf(a.length, b.length) >= 7) 2 else 1
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1); cur[0] = i + 1
            for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}

interface MemoryReasoningProvider {
    suspend fun interpretFinalTurn(evidence: AuthoritativeMemoryTurnEvidence): FinalTurnSemanticInterpretation = FinalTurnSemanticInterpretation()
    suspend fun classifyPrimitive(messages: List<ConversationTruthEntity>): SegmentClassification?
    suspend fun splitPrimitive(messages: List<ConversationTruthEntity>): List<Long>
    suspend fun resegmentInformative(messages: List<ConversationTruthEntity>, softBoundaries: List<Long>): List<Long>
    suspend fun reviewBoundaries(messages: List<ConversationTruthEntity>, candidates: List<SegmentBoundary>): List<ReviewedBoundary>
    suspend fun predict(title: String, facts: List<ConsolidationCandidate>): String
    suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>): List<EpisodeSemanticAction>
    suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>,
        sourceMessages: List<ConversationTruthEntity>): List<EpisodeSemanticAction> = calibrate(title, content, prediction, facts)
    suspend fun rateEpisodes(context: List<ConversationTruthEntity>, episodes: List<MemoryEntity>, queries: Map<String, List<String>>): Map<String, EpisodeReviewRating>
}

object UnavailableMemoryReasoningProvider : MemoryReasoningProvider {
    override suspend fun classifyPrimitive(messages: List<ConversationTruthEntity>) = null
    override suspend fun splitPrimitive(messages: List<ConversationTruthEntity>) = emptyList<Long>()
    override suspend fun resegmentInformative(messages: List<ConversationTruthEntity>, softBoundaries: List<Long>) = emptyList<Long>()
    override suspend fun reviewBoundaries(messages: List<ConversationTruthEntity>, candidates: List<SegmentBoundary>) = emptyList<ReviewedBoundary>()
    override suspend fun predict(title: String, facts: List<ConsolidationCandidate>) = ""
    override suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>) = emptyList<EpisodeSemanticAction>()
    override suspend fun rateEpisodes(context: List<ConversationTruthEntity>, episodes: List<MemoryEntity>, queries: Map<String, List<String>>) = emptyMap<String, EpisodeReviewRating>()
}

/** Existing Gemini API/key integration adapted to Plast-Mem background reasoning. */
class GeminiMemoryReasoningProvider(context: Context) : MemoryReasoningProvider {
    private val keyStore = ApiKeyStore(context.applicationContext)
    private val gate = Mutex() // strict one-call-at-a-time back-pressure
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    override suspend fun interpretFinalTurn(evidence: AuthoritativeMemoryTurnEvidence): FinalTurnSemanticInterpretation {
        val operation = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("intent", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf(
                "ADD_FACT", "ADD_RELATIONSHIP", "UPDATE_FACT", "SUPERSEDE_FACT",
                "ADD_GOAL", "UPDATE_GOAL", "ADD_PROJECT", "ADD_IDEA", "ADD_SOLUTION",
                "ADD_WORKFLOW", "TRANSIENT_CONTEXT", "NONE"
            ))))
            .put("person", JSONObject().put("type", "STRING"))
            .put("semantic_relationship", JSONObject().put("type", "STRING")
                .put("enum", JSONArray(listOf("", "FRIEND", "GOOD_FRIEND", "BEST_FRIEND"))))
            .put("temporal_scope", JSONObject().put("type", "STRING")
                .put("enum", JSONArray(listOf("CURRENT", "HISTORICAL", "TEMPORARY", "RECURRING", "UNSPECIFIED"))))
            .put("assertion_mode", JSONObject().put("type", "STRING")
                .put("enum", JSONArray(listOf("USER_ASSERTED", "HYPOTHETICAL", "REPORTED_SPEECH", "QUESTION"))))
            .put("fact", JSONObject().put("type", "STRING"))
            .put("category", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf(
                "IDENTITY", "PREFERENCE", "PROJECT", "GOAL", "HABIT", "LIFE_EVENT",
                "COMMUNICATION_STYLE", "WORKFLOW", "APP_USAGE", "IDEA", "SOLUTION"
            ))))
            .put("memory_key", JSONObject().put("type", "STRING"))
            .put("source_span", JSONObject().put("type", "STRING"))
            .put("critical_literals", JSONObject().put("type", "ARRAY").put("maxItems", 8)
                .put("items", JSONObject().put("type", "STRING")))
            .put("goal_title", JSONObject().put("type", "STRING"))
            .put("goal_status", JSONObject().put("type", "STRING"))
            .put("confidence", JSONObject().put("type", "NUMBER")))
            .put("required", JSONArray(listOf("intent", "source_span", "confidence", "assertion_mode")))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("display_text", JSONObject().put("type", "STRING"))
            .put("operations", JSONObject().put("type", "ARRAY").put("maxItems", 4).put("items", operation)))
            .put("required", JSONArray(listOf("display_text", "operations")))
        val input = JSONObject()
            .put("canonical_final_user_transcript", evidence.canonicalText)
            .put("current_display_projection", evidence.displayText)
        val prompt = "You are the final-turn semantic projection stage in a Plast-Mem-style memory pipeline. " +
            "For display_text, render the SAME utterance as natural Roman Hinglish/English. Correct only clear ASR/transliteration spelling or grammar damage; preserve meaning, names, numbers, dates, amounts and uncertainty. If unsure, copy current_display_projection unchanged. Never add facts. " +
            "For operations, extract only high-value durable user semantic facts useful in future turns. Stable preferences, communication style, identity, relationships, projects, goals, habits and workflows may be durable. Skip temporary chatter, acknowledgements, questions, hypotheticals, reported speech, guesses and secrets. " +
            "Use normalized meaning in fact, but source_span MUST be an exact substring copied from canonical_final_user_transcript, including noisy ASR spelling. Use a stable semantic memory_key and put every critical literal in critical_literals. Android independently validates and authorizes every write."
        val out = generate(prompt, input, schema)
        return FinalTurnSemanticInterpretation(
            displayText = out.optString("display_text").trim().takeIf(String::isNotEmpty),
            operations = GeminiMemoryOperationParser.parse(out).map {
                it.copy(sourceTurnId = evidence.turnId, sourceSessionId = evidence.sessionId)
            }
        )
    }

    override suspend fun classifyPrimitive(messages: List<ConversationTruthEntity>): SegmentClassification? {
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("classification", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("LOW_INFO", "INFORMATIVE")))))
            .put("required", JSONArray(listOf("classification")))
        return SegmentClassification.valueOf(generate(
            "Classify this complete message segment. LOW_INFO is acknowledgements, backchannels, thin coordination, bookkeeping, or weak retrieval value. INFORMATIVE is durable facts, plans, decisions, events, constraints, preferences, or commitments.",
            messageInput(messages), schema).getString("classification"))
    }

    override suspend fun splitPrimitive(messages: List<ConversationTruthEntity>): List<Long> = splitStarts(
        "Return the first message sequence of each later child segment at meaningful topic, intent, activity, or surprise discontinuities. Do not return the first sequence.", messages, emptyList())

    override suspend fun resegmentInformative(messages: List<ConversationTruthEntity>, softBoundaries: List<Long>): List<Long> = splitStarts(
        "All messages are informative. Re-segment across soft temporal boundaries, retaining only meaningful topic, intent, activity, or surprise discontinuities. Do not return the first sequence.", messages, softBoundaries)

    override suspend fun reviewBoundaries(messages: List<ConversationTruthEntity>, candidates: List<SegmentBoundary>): List<ReviewedBoundary> {
        if (candidates.isEmpty()) return emptyList()
        val candidateIds = candidates.map { it.afterSequence }.toSet()
        val input = JSONObject().put("messages", JSONArray(messages.map { JSONObject()
            .put("sequence", it.sequence).put("role", it.role).put("text", it.content.take(1500)) }))
            .put("candidates", JSONArray(candidates.map { JSONObject().put("after_sequence", it.afterSequence)
                .put("signal", it.reason.name).put("geometry_score", it.score) }))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("boundaries",
            JSONObject().put("type", "ARRAY").put("maxItems", candidates.size).put("items", JSONObject()
                .put("type", "OBJECT").put("properties", JSONObject()
                    .put("after_sequence", JSONObject().put("type", "INTEGER"))
                    .put("keep", JSONObject().put("type", "BOOLEAN"))
                    .put("confidence", JSONObject().put("type", "NUMBER"))
                    .put("reason", JSONObject().put("type", "STRING")))
                .put("required", JSONArray(listOf("after_sequence", "keep", "confidence", "reason"))))))
            .put("required", JSONArray(listOf("boundaries")))
        val out = generate("Review only the supplied candidate event boundaries. Keep a boundary when topic, intent, activity, or temporal episode changes. Merge short fragments that belong to one event. Never invent a sequence.", input, schema)
        return out.optJSONArray("boundaries").objects().mapNotNull { row ->
            row.optLong("after_sequence").takeIf(candidateIds::contains)?.let {
                ReviewedBoundary(it, row.optBoolean("keep"), row.optDouble("confidence", .0).coerceIn(0.0, 1.0), row.optString("reason").take(80))
            }
        }
    }

    override suspend fun predict(title: String, facts: List<ConsolidationCandidate>): String {
        if (facts.isEmpty()) return ""
        val input = JSONObject().put("episode_title", title).put("active_facts", JSONArray(facts.take(10).map {
            JSONObject().put("target_fact_id", it.memoryId).put("fact", it.fact).put("category", it.category) }))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("prediction", JSONObject().put("type", "STRING"))).put("required", JSONArray(listOf("prediction")))
        return generate("Predict what the episode should contain from its title and supplied active knowledge. Preserve names and state uncertainty.", input, schema).optString("prediction")
    }

    override suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>): List<EpisodeSemanticAction> =
        calibrate(title, content, prediction, facts, emptyList())

    override suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>,
        sourceMessages: List<ConversationTruthEntity>): List<EpisodeSemanticAction> {
        val suppliedIds = facts.map { it.memoryId }.toSet()
        val input = JSONObject().put("episode_title", title).put("actual_episode", content.take(12_000))
            .put("source_messages", JSONArray(sourceMessages.filter { it.role == "user" }.map { message ->
                JSONObject().put("sequence", message.sequence).put("text", message.canonicalText.take(1500))
            }))
            .put("prediction", prediction.orEmpty()).put("active_facts", JSONArray(facts.take(20).map {
                JSONObject().put("target_fact_id", it.memoryId).put("fact", it.fact).put("category", it.category) }))
        val action = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("kind", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("NEW", "REINFORCE", "UPDATE", "INVALIDATE"))))
            .put("fact", JSONObject().put("type", "STRING"))
            .put("category", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("IDENTITY", "PREFERENCE", "INTEREST", "PERSONALITY", "RELATIONSHIP", "EXPERIENCE", "GOAL", "GUIDELINE"))))
            .put("target_fact_id", JSONObject().put("type", "STRING"))
            .put("confidence", JSONObject().put("type", "NUMBER"))
            .put("assertion_mode", JSONObject().put("type", "STRING").put("enum",
                JSONArray(listOf("USER_ASSERTED", "HYPOTHETICAL", "REPORTED", "UNCERTAIN"))))
            .put("critical_literals", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")))
            .put("person", JSONObject().put("type", "STRING"))
            .put("semantic_relationship", JSONObject().put("type", "STRING").put("description",
                "The single canonical relationship strength expressed by the exact USER source span. Required only for RELATIONSHIP NEW/UPDATE.")
                .put("enum", JSONArray(listOf("", "FRIEND", "GOOD_FRIEND", "BEST_FRIEND"))))
            .put("goal_title", JSONObject().put("type", "STRING"))
            .put("goal_status", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("", "ACTIVE", "COMPLETED", "ABANDONED"))))
            .put("source_message_sequences", JSONObject().put("type", "ARRAY").put("minItems", 1).put("items", JSONObject().put("type", "INTEGER")))
            .put("source_spans", JSONObject().put("type", "ARRAY").put("minItems", 1).put("items", JSONObject().put("type", "STRING"))))
            .put("required", JSONArray(listOf("kind", "fact", "category", "target_fact_id", "confidence", "assertion_mode", "critical_literals", "person", "semantic_relationship", "goal_title", "goal_status", "source_message_sequences", "source_spans")))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("actions", JSONObject().put("type", "ARRAY").put("maxItems", 20).put("items", action)))
            .put("required", JSONArray(listOf("actions")))
        val prompt = "Produce durable atomic semantic actions only. Cold start permits NEW only. REINFORCE/UPDATE/INVALIDATE must use an exact supplied target_fact_id. INVALIDATE fact must be empty. Mark hypothetical, reported, or uncertain claims accurately. critical_literals MUST contain every person, location, project/product, username, number, date, amount, or ID in every structured field. For RELATIONSHIP NEW/UPDATE provide person and semantic_relationship classified from the exact USER source spans. semantic_relationship is the single canonical expressed strength; there is no second model enum to compare against it. REINFORCE/INVALIDATE use the canonical target and may leave semantic_relationship and person empty. For GOAL provide goal_title and goal_status: ACTIVE unless the supported user evidence explicitly completes or abandons it. source_message_sequences and source_spans must identify exact USER messages/spans supporting this action. Skip secrets and temporary chatter."
        val parsed = generate(prompt, input, schema).optJSONArray("actions").objects().mapNotNull { row ->
            val kind = runCatching { SemanticConsolidationAction.valueOf(row.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            val target = row.optString("target_fact_id").takeIf(String::isNotBlank)
            if (kind != SemanticConsolidationAction.NEW && target !in suppliedIds) return@mapNotNull null
            if (facts.isEmpty() && kind != SemanticConsolidationAction.NEW) return@mapNotNull null
            EpisodeSemanticAction(kind, row.optString("fact").trim(), row.optString("category"), target,
                row.optDouble("confidence", 0.0).coerceIn(0.0, 1.0), row.optString("assertion_mode"),
                row.optJSONArray("critical_literals").strings(), row.optString("person").takeIf(String::isNotBlank),
                null,
                row.optString("semantic_relationship").takeIf(String::isNotBlank),
                row.optString("goal_title").takeIf(String::isNotBlank),
                row.optString("goal_status").takeIf(String::isNotBlank),
                row.optJSONArray("source_message_sequences").longs(), row.optJSONArray("source_spans").strings())
        }
        val untargeted = parsed.filter { it.targetFactId == null }
        val targeted = parsed.filter { it.targetFactId != null }.groupBy { it.targetFactId!! }.values.map { group ->
            group.maxBy { actionPriority(it.kind) }
        }
        return (untargeted + targeted).take(20)
    }

    override suspend fun rateEpisodes(context: List<ConversationTruthEntity>, episodes: List<MemoryEntity>, queries: Map<String, List<String>>): Map<String, EpisodeReviewRating> {
        if (episodes.isEmpty()) return emptyMap()
        val allowed = episodes.map { it.id }.toSet()
        val input = JSONObject().put("context", JSONArray(context.takeLast(32).map { JSONObject().put("role", it.role).put("text", it.content.take(1500)) }))
            .put("memories", JSONArray(episodes.map { JSONObject().put("memory_id", it.id).put("content", it.fact.take(3000))
                .put("matched_queries", JSONArray(queries[it.id].orEmpty().distinct().take(8))) }))
        val rating = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("memory_id", JSONObject().put("type", "STRING"))
            .put("rating", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("AGAIN", "HARD", "GOOD", "EASY")))))
            .put("required", JSONArray(listOf("memory_id", "rating")))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("ratings", JSONObject().put("type", "ARRAY").put("maxItems", episodes.size).put("items", rating)))
            .put("required", JSONArray(listOf("ratings")))
        return generate("Rate retrieved episodic memories: AGAIN unused noise, HARD tangential, GOOD directly relevant, EASY indispensable. Use only supplied IDs.", input, schema)
            .optJSONArray("ratings").objects().mapNotNull { row ->
                val id = row.optString("memory_id"); if (id !in allowed) null else runCatching {
                    id to EpisodeReviewRating.valueOf(row.optString("rating"))
                }.getOrNull()
            }.toMap()
    }

    private suspend fun generate(system: String, input: JSONObject, schema: JSONObject): JSONObject = gate.withLock {
        val apiKey = keyStore.get(ApiKeyStore.GEMINI); check(apiKey.isNotBlank()) { "Gemini API key unavailable" }
        var failure: Throwable? = null
        repeat(3) { attempt ->
            try {
                return@withLock withContext(Dispatchers.IO) {
                    val body = JSONObject().put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", input.toString())))))
                        .put("generationConfig", JSONObject().put("temperature", 0.0).put("responseMimeType", "application/json").put("responseSchema", schema))
                    val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
                        .header("x-goog-api-key", apiKey).post(body.toString().toRequestBody(JSON)).build()
                    client.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty(); check(response.isSuccessful) { "Gemini HTTP ${response.code}" }
                        val text = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                            .getJSONArray("parts").getJSONObject(0).getString("text")
                        JSONObject(text)
                    }
                }
            } catch (error: Throwable) { failure = error; if (attempt < 2) delay((attempt + 1) * 1000L) }
        }
        throw failure ?: IllegalStateException("Gemini memory reasoning failed")
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    private fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else (0 until length()).map { optLong(it) }.filter { it >= 0L }

    private fun messageInput(messages: List<ConversationTruthEntity>) = JSONObject().put("messages", JSONArray(messages.map {
        JSONObject().put("sequence", it.sequence).put("role", it.role).put("text", it.content.take(1500))
    }))

    private suspend fun splitStarts(system: String, messages: List<ConversationTruthEntity>, hints: List<Long>): List<Long> {
        if (messages.size <= 1) return emptyList()
        val allowed = messages.drop(1).map { it.sequence }.toSet()
        val input = messageInput(messages).put("soft_boundary_right_starts", JSONArray(hints))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("split_start_sequences",
            JSONObject().put("type", "ARRAY").put("maxItems", messages.size - 1).put("items", JSONObject().put("type", "INTEGER"))))
            .put("required", JSONArray(listOf("split_start_sequences")))
        val output = generate(system, input, schema).optJSONArray("split_start_sequences") ?: return emptyList()
        return (0 until output.length()).map { output.optLong(it) }.filter(allowed::contains).distinct().sorted()
    }
    private fun actionPriority(kind: SemanticConsolidationAction) = when (kind) {
        SemanticConsolidationAction.NEW -> 0
        SemanticConsolidationAction.REINFORCE -> 1
        SemanticConsolidationAction.INVALIDATE -> 2
        SemanticConsolidationAction.UPDATE -> 3
    }

    companion object {
        private const val MODEL = "gemini-2.5-flash"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
