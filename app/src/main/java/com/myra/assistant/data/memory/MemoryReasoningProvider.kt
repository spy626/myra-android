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

data class ReviewedBoundary(val afterSequence: Long, val keep: Boolean, val confidence: Double, val reason: String)
data class ConsolidationCandidate(val memoryId: String, val fact: String, val category: String)
data class EpisodeSemanticAction(val kind: SemanticConsolidationAction, val fact: String,
    val category: String, val targetFactId: String?, val confidence: Double,
    val assertionMode: String = "USER_ASSERTED")

interface MemoryReasoningProvider {
    suspend fun reviewBoundaries(messages: List<ConversationTruthEntity>, candidates: List<SegmentBoundary>): List<ReviewedBoundary>
    suspend fun predict(title: String, facts: List<ConsolidationCandidate>): String
    suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>): List<EpisodeSemanticAction>
    suspend fun rateEpisodes(context: List<ConversationTruthEntity>, episodes: List<MemoryEntity>, queries: Map<String, List<String>>): Map<String, EpisodeReviewRating>
}

object UnavailableMemoryReasoningProvider : MemoryReasoningProvider {
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

    override suspend fun calibrate(title: String, content: String, prediction: String?, facts: List<ConsolidationCandidate>): List<EpisodeSemanticAction> {
        val suppliedIds = facts.map { it.memoryId }.toSet()
        val input = JSONObject().put("episode_title", title).put("actual_episode", content.take(12_000))
            .put("prediction", prediction.orEmpty()).put("active_facts", JSONArray(facts.take(20).map {
                JSONObject().put("target_fact_id", it.memoryId).put("fact", it.fact).put("category", it.category) }))
        val action = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("kind", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("NEW", "REINFORCE", "UPDATE", "INVALIDATE"))))
            .put("fact", JSONObject().put("type", "STRING"))
            .put("category", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("IDENTITY", "PREFERENCE", "INTEREST", "PERSONALITY", "RELATIONSHIP", "EXPERIENCE", "GOAL", "GUIDELINE"))))
            .put("target_fact_id", JSONObject().put("type", "STRING"))
            .put("confidence", JSONObject().put("type", "NUMBER"))
            .put("assertion_mode", JSONObject().put("type", "STRING").put("enum",
                JSONArray(listOf("USER_ASSERTED", "HYPOTHETICAL", "REPORTED", "UNCERTAIN")))))
            .put("required", JSONArray(listOf("kind", "fact", "category", "target_fact_id", "confidence", "assertion_mode")))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("actions", JSONObject().put("type", "ARRAY").put("maxItems", 20).put("items", action)))
            .put("required", JSONArray(listOf("actions")))
        val prompt = "Produce durable atomic semantic actions only. Cold start permits NEW only. REINFORCE/UPDATE/INVALIDATE must use an exact supplied target_fact_id. INVALIDATE fact must be empty. Skip secrets, hypothetical/reported speech, temporary chatter, and uncertain claims."
        return generate(prompt, input, schema).optJSONArray("actions").objects().mapNotNull { row ->
            val kind = runCatching { SemanticConsolidationAction.valueOf(row.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            val target = row.optString("target_fact_id").takeIf(String::isNotBlank)
            if (kind != SemanticConsolidationAction.NEW && target !in suppliedIds) return@mapNotNull null
            if (facts.isEmpty() && kind != SemanticConsolidationAction.NEW) return@mapNotNull null
            EpisodeSemanticAction(kind, row.optString("fact").trim(), row.optString("category"), target,
                row.optDouble("confidence", 0.0).coerceIn(0.0, 1.0), row.optString("assertion_mode"))
        }
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

    companion object {
        private const val MODEL = "gemini-2.5-flash"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
