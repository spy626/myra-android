package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRichBudgetTest {
    @Test fun normalFiveRowTableAndNaturalBulletsRemainUntouched() {
        val raw = """{"blocks":[
            {"type":"heading","text":"1. Features"},
            {"type":"list","items":["Home categories","Search","Product details","Cart and checkout"]},
            {"type":"heading","text":"2. Sample products"},
            {"type":"table","columns":["Product","Sample price"],"rows":[
                ["Rice","₹65"],["Milk","₹30"],["Eggs","₹42"],["Sugar","₹50"],["Oil","₹140"]]},
            {"type":"heading","text":"3. Tools"},
            {"type":"app_cards","items":[{"name":"Google Keep","note":"Plan now"},{"name":"SPCK Editor","note":"Code later"}]}
        ]}"""
        val result = WorkspaceRichOutputBudget.compact(raw, true)
        assertEquals(raw, result.raw)
        assertTrue(result.changes.isEmpty())
        val blocks = RichBlockParser.parse(result.raw)
        assertEquals(4, (blocks[1] as Block.Bullets).items.size)
        assertEquals(5, (blocks[3] as Block.Table).rows.size)
        assertEquals(2, (blocks[5] as Block.AppCards).items.size)
        assertEquals(2, WorkspaceRichOutputBudget.visualCount(result.raw))
    }

    @Test fun proseAndMarkdownPlansDoNotBecomeFakeTables() {
        val model = """{"blocks":[{"type":"heading","text":"Plan"},
            {"type":"list","items":["Choose features","Sketch on paper","Note catalog"]}]}"""
        val result = WorkspaceRichOutputBudget.compact(model, true)
        assertEquals(model, result.raw)
        assertEquals(0, WorkspaceRichOutputBudget.visualCount(result.raw))
        assertTrue(RichBlockParser.parse(result.raw)[1] is Block.Bullets)
        val markdown = "## Plan\\n• Choose features\\n• Sketch on paper"
        val plain = WorkspaceRichOutputBudget.compact(markdown, true)
        assertEquals(markdown, plain.raw)
        assertEquals(0, WorkspaceRichOutputBudget.visualCount(plain.raw))
    }

    @Test fun exceptionalOversizedProseCanBeShortenedWithoutNewVisuals() {
        val model = JSONObject().put("blocks", org.json.JSONArray()
            .put(JSONObject().put("type", "text").put("style", "opener")
                .put("text", "Long explanation ".repeat(180)))
            .put(JSONObject().put("type", "text").put("style", "body")
                .put("text", "Another paragraph ".repeat(180)))
            .put(JSONObject().put("type", "list")
                .put("items", org.json.JSONArray().put("One useful action")))).toString()
        val result = WorkspaceRichOutputBudget.compact(model, true)
        assertTrue(result.raw.length < model.length)
        assertTrue(result.changes.isNotEmpty())
        assertEquals(0, WorkspaceRichOutputBudget.visualCount(result.raw))
    }

    @Test fun exactSseBudgetExceptionReportsPartialTransportNotInventedFullSize() {
        val request = Request.Builder().url("https://example.com/chat").build()
        val data = "data: " + "x".repeat(192_005) + "\n\n"
        val response = Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "text/event-stream")
            .body(data.toResponseBody("text/event-stream".toMediaType())).build()
        val error = runCatching {
            WorkspaceRichResponse.read(response, { error("unexpected fallback") },
                onBlocks = {})
        }.exceptionOrNull()
        assertTrue(error is WorkspaceRichResponse.BudgetExceeded)
        val metrics = (error as WorkspaceRichResponse.BudgetExceeded).metrics
        assertTrue(metrics.wireChars > 192_000)
        assertEquals(0, metrics.contentChars)
    }

    @Test fun shortRetryUsesIdenticalModelEndpointLatestUserAndFreeSafetyFields() {
        val user = "grocery app 3 steps no coding"
        val input = listOf(WorkspaceConversationStore.Message("u1", "user", user, 0L))
        val extra = "RUNTIME-TRUTH: no paid route, never write main.\n\n" +
            WorkspaceRichBlocksContract.COMPACT_GROQ_INSTRUCTIONS
        val original = WorkspaceGroqFree.request("test-groq-key", input,
            extraSystemInstructions = extra)
        val retry = requireNotNull(WorkspaceRichRetry.request(original))
        val body = okio.Buffer().also { retry.body?.writeTo(it) }.readUtf8()
        val json = JSONObject(body)
        assertEquals(WorkspaceGroqFree.MODEL, json.getString("model"))
        assertEquals("api.groq.com", retry.url.host)
        assertTrue(json.getBoolean("stream"))
        assertEquals(1050, json.getInt("max_completion_tokens"))
        assertEquals(user, json.getJSONArray("messages").getJSONObject(1)
            .getString("content"))
        val system = json.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("RUNTIME-TRUTH: no paid route"))
        assertTrue(system.contains("A visual is OPTIONAL"))
        assertEquals("Bearer test-groq-key", retry.header("Authorization"))
        assertFalse(body.contains("test-groq-key"))
        assertNull(WorkspaceRichRetry.request(retry))
        val trace = WorkspaceRichDiagnostics.inspect(retry, "GROQ_FREE")
        assertEquals(WorkspaceGroqFree.MODEL, trace.model)
        assertTrue(trace.richPromptInOutboundBody)
    }
}
