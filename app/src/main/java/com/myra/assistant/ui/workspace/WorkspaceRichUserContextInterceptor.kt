package com.myra.assistant.ui.workspace

import android.content.Context
import com.myra.assistant.MyApplication
import com.myra.assistant.data.memory.MemoryBrainCoordinator
import com.myra.assistant.data.memory.MemoryEntity
import com.myra.assistant.data.memory.SavedMemoryContextFormatter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject

/**
 * Extra narrow READ-ONLY context for rich app-planning answers, subject to the exact
 * existing Workspace saved-memory sharing opt-in. Does not save, update, learn from,
 * create, or authorize actions using any memory. Provider transport is unchanged.
 */
internal class WorkspaceRichUserContextInterceptor(
    private val contextProvider: () -> Context? = { MyApplication.contextOrNull() },
    private val cards: suspend (Context) -> List<MemoryEntity> = {
        MemoryBrainCoordinator.get(it).activeCards(80)
    },
) : Interceptor {
    companion object {
        private const val PREFS = "workspace_ui"
        private val appIntent = Regex(
            "(?iu)\\b(?:app|application|grocery|market|website|project|build|banao|banana|develop)\\b"
        )
        /** Only a bounded topic-specific query extension; never invent missing memory facts. */
        internal fun queryFor(latest: String): String =
            if (appIntent.containsMatchIn(latest))
                latest + " app project grocery market android phone mobile SPCK Editor Chrome tools"
            else latest
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val context = contextProvider()
        if (context == null || original.method != "POST" || chain.call().isCanceled() ||
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(WorkspaceMemoryInterceptor.PREFERENCE_KEY, false)) {
            return chain.proceed(original)
        }
        val enriched = runCatching {
            val buffer = Buffer()
            original.body?.writeTo(buffer) ?: return@runCatching original
            val json = JSONObject(buffer.readUtf8())
            val messages = json.optJSONArray("messages") ?: return@runCatching original
            val system = messages.optJSONObject(0)?.takeIf {
                it.optString("role") == "system"
            } ?: return@runCatching original
            if (!WorkspaceRichBlocksContract.enabled(system.optString("content")))
                return@runCatching original
            val last = messages.optJSONObject(messages.length() - 1) ?: return@runCatching original
            val latest = last.opt("content") as? String ?: return@runCatching original
            if (latest.contains("\n\nDocument ")) return@runCatching original
            val active = runBlocking { withTimeoutOrNull(1_200L) { cards(context) } }.orEmpty()
            val relevant = WorkspaceContextProjection.shareableMemoryFacts(
                active, queryFor(latest),
            ).filterNot { system.optString("content").contains(it) }.take(4)
            val note = SavedMemoryContextFormatter.format(relevant, 4)
            if (note.isBlank() || chain.call().isCanceled()) return@runCatching original
            val previousSystem = system.optString("content")
            val updatedSystem = previousSystem + "\n" + note
            // An optional memory projection must never defeat Free route prompt budgets.
            val maxPrompt = when (original.url.toString()) {
                WorkspaceGroqFree.ENDPOINT -> WorkspaceGroqFree.MAX_PROMPT_CHARS
                WorkspaceLlm7Free.ENDPOINT -> WorkspaceLlm7Free.MAX_PROMPT_CHARS
                else -> Int.MAX_VALUE
            }
            val projectedChars = (0 until messages.length()).sumOf { index ->
                val value = messages.optJSONObject(index)?.opt("content") as? String
                if (index == 0) updatedSystem.length
                else value?.length ?: maxPrompt
            }
            if (projectedChars > maxPrompt) return@runCatching original
            system.put("content", updatedSystem)
            original.newBuilder().method("POST", json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }.getOrDefault(original)
        return chain.proceed(if (chain.call().isCanceled()) original else enriched)
    }
}
