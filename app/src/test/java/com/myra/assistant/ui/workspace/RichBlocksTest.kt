package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RichBlocksTest {
    @Test fun parsesAllNineAllowedBlocksAndSpeaksOnlyOpenerCloser() {
        val reply = """{"blocks":[
          {"type":"text","style":"opener","text":"Hi bro"},
          {"type":"heading","emoji":"📱","text":"Plan"},
          {"type":"list","items":["One","Two"]},
          {"type":"table","columns":["A","B"],"rows":[["1","2"]]},
          {"type":"image_row","query":"grocery app UI","caption":"Inspiration only"},
          {"type":"app_cards","items":[{"name":"Chrome","note":"Test"}]},
          {"type":"callout","label":"Tip","text":"No payments yet"},
          {"type":"divider"},
          {"type":"options","question":"Choose","choices":["A","B"]},
          {"type":"text","style":"closer","text":"Start small"}
        ]}"""
        val parsed = RichBlockParser.parse(reply)
        assertEquals(10, parsed.size)
        assertTrue(parsed[1] is Block.Heading)
        assertTrue(parsed[2] is Block.Bullets)
        assertTrue(parsed[3] is Block.Table)
        assertTrue(parsed[4] is Block.ImageRow)
        assertTrue(parsed[5] is Block.AppCards)
        assertTrue(parsed[6] is Block.Callout)
        assertTrue(parsed[7] is Block.Divider)
        assertTrue(parsed[8] is Block.Options)
        assertEquals("Hi bro Start small", RichBlockParser.spokenText(parsed))
        assertFalse(RichBlockParser.spokenText(parsed).contains("Tip"))
    }

    @Test fun badBlocksAreSkippedAndPlainTextIsOneBlock() {
        assertEquals(listOf(Block.Text("opener", "hello")),
            RichBlockParser.parse("hello"))
        val bad = """{"blocks":[{"type":"unknown","text":"bad"},
          {"type":"table","columns":["A","B"],"rows":[["bad"]]},
          {"type":"heading","emoji":"✅","text":"Valid"},
          {"type":"options","question":"Choose","choices":["One"]}]}"""
        val parsed = RichBlockParser.parse(bad)
        assertEquals(1, parsed.size)
        assertEquals(Block.Heading("✅", "Valid"), parsed.single())
        assertTrue(RichBlockParser.parse("""{"blocks":[{"type":"heading" """).isEmpty())
    }

    @Test fun incrementalJsonRespectsEscapedStringsAndNestedObjects() {
        val reader = RichBlockIncrementalParser()
        val a = """{"blocks":[{"type":"text","style":"opener","text":"Quote: \"hi\""},"""
        assertEquals(1, reader.update(a).size)
        assertTrue(reader.update(a).isEmpty())
        val b = a + """{"type":"app_cards","items":[{"name":"Chrome","note":"Testing"}]},"""
        val second = reader.update(b)
        assertEquals(1, second.size)
        assertTrue(second.single() is Block.AppCards)
        assertTrue(reader.update(b + """{"type":"heading","text":"Unfinished" """).isEmpty())
    }

    @Test fun realSseContentEmitsCompletedBlocksOnlyAndChecksStop() {
        val json = """{"blocks":[{"type":"text","style":"opener","text":"Hey"}]}"""
        val chunk = org.json.JSONObject().put("choices", org.json.JSONArray().put(
            org.json.JSONObject().put("delta", org.json.JSONObject().put("content", json))
                .put("finish_reason", org.json.JSONObject.NULL)
        )).toString()
        val stop = org.json.JSONObject().put("choices", org.json.JSONArray().put(
            org.json.JSONObject().put("delta", org.json.JSONObject())
                .put("finish_reason", "stop")
        )).toString()
        val payload = "data: " + chunk + "\n\n" + "data: " + stop +
            "\n\n" + "data: [DONE]\n\n"
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/chat").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "text/event-stream")
            .body(payload.toResponseBody("text/event-stream".toMediaType()))
            .build()
        val emitted = mutableListOf<Block>()
        assertEquals(json, WorkspaceRichResponse.read(response, {
            error("SSE must not use JSON fallback")
        }) { emitted += it })
        assertEquals(listOf(Block.Text("opener", "Hey")), emitted)
    }

    @Test fun groceryAppRequestActuallyContainsRichSystemPromptAndStreaming() {
        val input = listOf(WorkspaceConversationStore.Message(
            id = "u1", role = "user", text = "grocery app", createdAtMs = 0L,
        ))
        val outgoing = WorkspaceChatGateway.request(
            WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            key = "unit-test-not-a-real-token",
            messages = input,
            extraSystemInstructions = WorkspaceRichBlocksContract.INSTRUCTIONS,
        )
        val trace = WorkspaceRichDiagnostics.inspect(outgoing, "OpenRouter Free")
        assertTrue(trace.richPromptInOutboundBody)
        assertTrue(trace.rulesPresent)
        assertTrue(trace.streamRequested)
        val body = okio.Buffer().also { outgoing.body?.writeTo(it) }.readUtf8()
        val json = org.json.JSONObject(body)
        val entries = json.getJSONArray("messages")
        assertEquals("grocery app", entries.getJSONObject(entries.length() - 1).getString("content"))
        assertTrue(entries.getJSONObject(0).getString("content").contains("Meri advice:"))

        // The same JSON contract must survive Free-route body generation too.
        val groq = org.json.JSONObject(WorkspaceGroqFree.body(
            input, extraSystemInstructions = WorkspaceRichBlocksContract.INSTRUCTIONS,
        ))
        assertTrue(groq.getBoolean("stream"))
        assertTrue(groq.getJSONArray("messages")
            .getJSONObject(0).getString("content").contains(WorkspaceRichBlocksContract.MARKER))
        val llm7 = org.json.JSONObject(WorkspaceLlm7Free.body(
            input, extraSystemInstructions = WorkspaceRichBlocksContract.INSTRUCTIONS,
        ))
        assertTrue(llm7.getBoolean("stream"))
    }

    @Test fun completeGroceryPlanExampleContainsSevenRequiredBlocks() {
        val raw = WorkspaceRichBlocksContract.INSTRUCTIONS
            .substringAfter("FULL SEVEN-BLOCK EXAMPLE (illustrative, adapt to user's actual context):")
            .substringBefore("A comparison task").trim()
        val parsed = RichBlockParser.parse(raw)
        assertEquals("Raw illustrative model format: " + raw, 7, parsed.size)
        assertTrue(parsed.first() is Block.Text)
        assertEquals("opener", (parsed.first() as Block.Text).style)
        assertTrue(parsed.any { it is Block.Heading })
        assertTrue(parsed.any { it is Block.AppCards || it is Block.Table })
        assertTrue(parsed.any { it is Block.Callout })
        assertTrue(parsed.any { it is Block.Divider })
        assertTrue(parsed.last() is Block.Text)
        assertTrue((parsed.last() as Block.Text).text.startsWith("Meri advice:"))
        val list = parsed.filterIsInstance<Block.Bullets>().single()
        assertEquals(3, list.items.size)
        assertTrue(list.items.none { it.trim().matches(Regex("""^(?:[-*•]|\d+[.)]).*""")) })
    }

    @Test fun localIconMapAndReadOnlyAppContextAreDeterministic() {
        assertEquals("🌐", WorkspaceLocalAppIcons.glyph("Google Chrome"))
        assertEquals("</>", WorkspaceLocalAppIcons.glyph("SPCK Editor"))
        assertEquals("U", WorkspaceLocalAppIcons.glyph("Unknown app"))
        val focused = WorkspaceRichUserContextInterceptor.queryFor("grocery app")
        assertTrue(focused.contains("android phone"))
        assertTrue(focused.contains("SPCK Editor Chrome"))
        assertEquals("hi bro", WorkspaceRichUserContextInterceptor.queryFor("hi bro"))
    }
}
