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
}
