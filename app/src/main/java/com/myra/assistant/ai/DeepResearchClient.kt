package com.myra.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepResearchClient {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun search(query: String, apiKey: String, endpoint: String, depth: String): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result(false, "Add your Tavily API key in Deep Research Settings.")
        val body = JSONObject().put("query", query).put("search_depth", if (depth == "advanced") "advanced" else "basic")
            .put("include_answer", "advanced").put("max_results", if (depth == "advanced") 7 else 5).put("include_raw_content", false)
        val request = Request.Builder().url(endpoint.trim().ifBlank { "https://api.tavily.com/search" })
            .header("Authorization", "Bearer $apiKey").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext Result(false, when (response.code) { 401, 403 -> "Tavily rejected the API key."; 429 -> "Tavily credit or rate limit reached."; else -> "Deep Research failed (HTTP ${response.code})." })
                val json = JSONObject(raw); val answer = json.optString("answer").trim(); val results = json.optJSONArray("results")
                val sources = buildList {
                    if (results != null) for (i in 0 until minOf(results.length(), 5)) {
                        val item = results.optJSONObject(i) ?: continue
                        val title = item.optString("title", "Source ${i + 1}").trim(); val url = item.optString("url").trim()
                        if (url.isNotBlank()) add("${i + 1}. $title\n$url")
                    }
                }
                val fallback = if (results != null && results.length() > 0) results.optJSONObject(0)?.optString("content").orEmpty() else ""
                val summary = answer.ifBlank { fallback }.ifBlank { "Tavily returned sources but no summary." }
                val report = buildString { append("Deep Research: ").append(query).append("\n\n").append(summary); if (sources.isNotEmpty()) append("\n\nSources\n").append(sources.joinToString("\n\n")) }
                Result(true, report, summary.take(650))
            }
        } catch (e: Exception) { Result(false, "Deep Research connection failed: ${e.message ?: "unknown error"}") }
    }

    data class Result(val success: Boolean, val report: String, val spokenSummary: String = report)
}
