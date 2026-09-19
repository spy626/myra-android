package com.myra.assistant.ui.workspace

import android.content.Context
import com.myra.assistant.MyApplication
import com.myra.assistant.data.memory.MemoryBrainCoordinator
import com.myra.assistant.data.memory.SavedMemoryContextFormatter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The existing AIRI owner is READ ONLY here. A single user-controlled setting gates
 * third-party sharing of a few non-sensitive saved facts. No new model, Room store,
 * passive learning, project source, other chat transcript or voice recording is sent.
 * OkHttp executes this interceptor off the UI thread and before the retry interceptor.
 */
internal class WorkspaceMemoryInterceptor(
    private val contextProvider: () -> Context? = { MyApplication.contextOrNull() },
    private val cards: suspend (Context) -> List<com.myra.assistant.data.memory.MemoryEntity> = {
        MemoryBrainCoordinator.get(it).activeCards(80)
    },
) : Interceptor {
    companion object {
        const val PREFERENCE_KEY = "workspace_saved_memory_context"
        private const val PREFERENCES = "workspace_ui"
        private const val MAX_LOOKUP_MS = 1_200L

        /** Pure JSON boundary for tests; original request and local memory never change. */
        internal fun enrich(json: JSONObject, facts: List<String>): JSONObject {
            val note = SavedMemoryContextFormatter.format(facts, 4)
            if (note.isBlank()) return json
            val messages = json.optJSONArray("messages") ?: return json
            if (messages.length() == 0) return json
            val first = messages.optJSONObject(0)
            if (first?.optString("role") == "system") {
                first.put("content", first.optString("content") + "\n" + note)
            } else {
                val appended = JSONArray().put(JSONObject().put("role", "system")
                    .put("content", note.trim()))
                for (i in 0 until messages.length()) appended.put(messages.get(i))
                json.put("messages", appended)
            }
            return json
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method != "POST" || original.url.toString() != WorkspaceFreeAiSuggestion.ENDPOINT ||
            chain.call().isCanceled()) return chain.proceed(original)
        val context = contextProvider() ?: return chain.proceed(original)
        if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(PREFERENCE_KEY, false)) return chain.proceed(original)
        val outgoing = runCatching {
            val buffer = Buffer()
            original.body?.writeTo(buffer) ?: return@runCatching original
            val payload = JSONObject(buffer.readUtf8())
            val messages = payload.optJSONArray("messages") ?: return@runCatching original
            val last = messages.optJSONObject(messages.length() - 1) ?: return@runCatching original
            if (last.optString("role") != "user" || last.opt("content") !is String)
                return@runCatching original // photos / multipart contents are not enriched
            val latest = last.getString("content")
            if (latest.contains("\n\nDocument ")) return@runCatching original
            val saved = runBlocking { withTimeoutOrNull(MAX_LOOKUP_MS) { cards(context) } }.orEmpty()
            val facts = WorkspaceContextProjection.shareableMemoryFacts(saved, latest)
            if (facts.isEmpty() || chain.call().isCanceled()) return@runCatching original
            val enriched = enrich(payload, facts).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            original.newBuilder().method("POST", enriched).build()
        }.getOrDefault(original) // Memory must never stop normal chat or reveal a raw DB error.
        if (chain.call().isCanceled()) return chain.proceed(original) // OkHttp cancellation applies.
        return chain.proceed(outgoing)
    }
}
