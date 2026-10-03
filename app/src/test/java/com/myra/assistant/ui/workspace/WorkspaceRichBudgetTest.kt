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
    @Test fun visualShapesAreBoundedBulletsTrimFirstAndAtLeastOneVisualSurvives() {
        val raw = """{"blocks":[
            {"type":"text","style":"opener","text":"Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi "},
            {"type":"heading","text":"Step 1 — plan"},
            {"type":"list","items":["one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen","another lengthy description of products that nobody needs inside the list item today"]},
            {"type":"mockup_card","title":"Home screen","layout":"grid","items":["Home","Product","Cart","Checkout","Account","Help"]},
            {"type":"app_cards","items":[{"name":"Google Keep","note":"one two three four five six seven eight nine"},{"name":"SPCK Editor","note":"Code later"},{"name":"Chrome","note":"Preview later"},{"name":"Another","note":"Not required"}]},
            {"type":"table","columns":["Item","Sample price","Unit"],"rows":[["Rice","65","kg"],["Milk","30","ml"],["Egg","40","six"],["Sugar","55","kg"]]},
            {"type":"callout","label":"Tip","text":"Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data "},
            {"type":"text","style":"closer","text":"Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. "}
        ]}""".replace("Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi Hi ", "Hi ".repeat(60))
            .replace("Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data Always use illustrative sample data ",
                "Always use illustrative sample data ".repeat(15))
            .replace("Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. Meri advice: keep it short. ",
                "Meri advice: keep it short. ".repeat(15))
        val result = WorkspaceRichOutputBudget.compact(raw, true)
        val blocks = RichBlockParser.parse(result.raw)
        assertTrue(result.changes.first().contains("list"))
        val items = blocks.filterIsInstance<Block.Bullets>().single().items
        assertTrue(items.all { it.split(" ").size <= 12 })
        assertEquals(4, blocks.filterIsInstance<Block.MockupCard>().single().items.size)
        assertEquals(3, blocks.filterIsInstance<Block.AppCards>().single().items.size)
        assertTrue(blocks.filterIsInstance<Block.AppCards>().single().items.all {
            it.second.split(" ").size <= 5
        })
        assertEquals(2, blocks.filterIsInstance<Block.Table>().single().columns.size)
        assertEquals(3, blocks.filterIsInstance<Block.Table>().single().rows.size)
        assertEquals(3, WorkspaceRichOutputBudget.visualCount(result.raw))
        assertEquals("text", result.before.first())
        assertTrue(result.after.containsAll(listOf("mockup_card", "app_cards", "table")))
        assertFalse(result.raw.contains("Account"))
    }

    @Test fun missingVisualFromModelBulletsCanBePresentedWithoutInventingFacts() {
        val model = """{"blocks":[
            {"type":"heading","text":"Plan"},
            {"type":"list","items":["Products ka scope decide karo","Paper par screens sketch karo","Sample catalog note karo"]}
        ]}"""
        val result = WorkspaceRichOutputBudget.compact(model, true)
        assertEquals(1, WorkspaceRichOutputBudget.visualCount(result.raw))
        assertTrue(result.changes.any { it.contains("existing list") })
        assertTrue(result.raw.contains("Paper par screens sketch karo"))
        assertTrue(RichBlockParser.parse(result.raw).any { it is Block.Table })
        val markdown = "## Plan\n• Products ka scope decide karo\n• Cart sketch karo"
        val converted = WorkspaceRichOutputBudget.compact(markdown, true)
        assertEquals(1, WorkspaceRichOutputBudget.visualCount(converted.raw))
        assertTrue(converted.raw.contains("Cart sketch karo"))
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
        val retry = WorkspaceRichRetry.request(original)
        assertNotNull(retry)
        val body = okio.Buffer().also { retry!!.body?.writeTo(it) }.readUtf8()
        val json = JSONObject(body)
        assertEquals(WorkspaceGroqFree.MODEL, json.getString("model"))
        assertEquals("api.groq.com", retry.url.host)
        assertTrue(json.getBoolean("stream"))
        assertEquals(1050, json.getInt("max_completion_tokens"))
        assertEquals(user, json.getJSONArray("messages").getJSONObject(1)
            .getString("content"))
        val system = json.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("RUNTIME-TRUTH: no paid route"))
        assertTrue(system.contains("at least one mockup_card"))
        assertEquals("Bearer test-groq-key", retry.header("Authorization"))
        assertFalse(body.contains("test-groq-key"))
        assertNull(WorkspaceRichRetry.request(retry))
        val trace = WorkspaceRichDiagnostics.inspect(retry, "GROQ_FREE")
        assertEquals(WorkspaceGroqFree.MODEL, trace.model)
        assertTrue(trace.richPromptInOutboundBody)
    }
}
