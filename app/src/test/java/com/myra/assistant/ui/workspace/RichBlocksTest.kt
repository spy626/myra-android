package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RichBlocksTest {
    @Test fun bodyParagraphParsesButDoesNotSpeakWhileRemainingVisible() {
        val raw = """{"blocks":[
            {"type":"heading","text":"Planning"},
            {"type":"text","style":"body","text":"Pehle features note karo."},
            {"type":"text","style":"closer","text":"Meri advice: plan first."}
        ]}"""
        val parsed = RichBlockParser.parse(raw)
        assertEquals(3, parsed.size)
        assertEquals(Block.Text("body", "Pehle features note karo."), parsed[1])
        assertFalse(RichBlockParser.spokenText(parsed).contains("Pehle features"))
        assertTrue(RichBlockParser.visibleText(parsed).contains("Pehle features"))
    }

    @Test fun parsesAllTenAllowedBlocksAndSpeaksOnlyOpenerCloser() {
        val reply = """{"blocks":[
          {"type":"text","style":"opener","text":"Hi bro"},
          {"type":"heading","emoji":"📱","text":"Plan"},
          {"type":"list","items":["One","Two"]},
          {"type":"table","columns":["A","B"],"rows":[["1","2"]]},
          {"type":"image_row","query":"grocery app UI","caption":"Inspiration only"},
          {"type":"app_cards","items":[{"name":"Chrome","note":"Test"}]},
          {"type":"callout","label":"Tip","text":"No payments yet"},
          {"type":"mockup_card","title":"4 screens","layout":"grid","items":["Home","Product","Cart","Checkout"]},
          {"type":"divider"},
          {"type":"options","question":"Choose","choices":["A","B"]},
          {"type":"text","style":"closer","text":"Start small"}
        ]}"""
        val parsed = RichBlockParser.parse(reply)
        assertEquals(11, parsed.size)
        assertTrue(parsed[1] is Block.Heading)
        assertTrue(parsed[2] is Block.Bullets)
        assertTrue(parsed[3] is Block.Table)
        assertTrue(parsed[4] is Block.ImageRow)
        assertTrue(parsed[5] is Block.AppCards)
        assertTrue(parsed[6] is Block.Callout)
        assertTrue(parsed[7] is Block.MockupCard)
        assertTrue(parsed[8] is Block.Divider)
        assertTrue(parsed[9] is Block.Options)
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
        assertTrue(entries.getJSONObject(0).getString("content")
            .contains("previously worked on a web app using SPCK Editor"))
        assertTrue(entries.getJSONObject(0).getString("content")
            .contains("A table is useful for actual 2-column product/price"))
        assertTrue(entries.getJSONObject(0).getString("content")
            .contains("For N requested steps, give EXACTLY N"))

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

    @Test fun fullExampleIsNaturalBulletsTableAppRowsNotCompulsoryMockup() {
        val raw = WorkspaceRichBlocksContract.INSTRUCTIONS.substringAfter(
            "COMPLETE THREE-STEP FEW-SHOT (illustrative plan; adapt, never copy as live data):"
        ).substringBefore("END THREE-STEP FEW-SHOT.").trim()
        val blocks = RichBlockParser.parse(raw)
        assertEquals(8, blocks.size)
        assertEquals(listOf(1, 3, 5), blocks.indices.filter { blocks[it] is Block.Heading })
        assertTrue(blocks[2] is Block.Bullets)
        assertTrue(blocks[4] is Block.Table)
        assertEquals(5, (blocks[4] as Block.Table).rows.size)
        assertTrue(blocks[6] is Block.AppCards)
        assertTrue((blocks.last() as Block.Text).text.startsWith("Meri advice:"))
        assertFalse(blocks.any { it is Block.MockupCard || it is Block.Callout })
        assertFalse(RichBlockParser.spokenText(blocks).contains("₹"))
    }

    @Test fun freeSampleUsesSevenNaturalBlocksWithNoForcedVisuals() {
        val compact = WorkspaceRichBlocksContract.COMPACT_GROQ_INSTRUCTIONS
        val raw = compact.substringAfter(
            "THREE-STEP FEW-SHOT JSON (illustrative, not live data):").trim()
        val blocks = RichBlockParser.parse(raw)
        assertEquals(7, blocks.size)
        assertEquals(listOf(0, 2, 4), blocks.indices.filter { blocks[it] is Block.Heading })
        assertTrue(blocks[1] is Block.Bullets)
        assertTrue(blocks[3] is Block.Table)
        assertTrue(blocks[5] is Block.AppCards)
        assertTrue(blocks.last() is Block.Text)
        listOf(WorkspaceRichBlocksContract.INSTRUCTIONS, compact).forEach { prompt ->
            assertTrue(prompt.contains("LYRA_RICH_BLOCKS_V1"))
            assertTrue(prompt.contains("Google Keep"))
            assertTrue(prompt.contains("SPCK Editor"))
            assertTrue(prompt.contains("Chrome"))
            assertTrue(prompt.contains("mockup_card"))
            assertTrue(prompt.contains("app_cards"))
            assertTrue(prompt.contains("table"))
            assertTrue(prompt.contains("Meri advice:"))
        }
    }

    @Test fun proseOnlyPlanIsAllowedByBothContracts() {
        val full = WorkspaceRichBlocksContract.INSTRUCTIONS
        val compact = WorkspaceRichBlocksContract.COMPACT_GROQ_INSTRUCTIONS
        assertTrue(full.contains("No compulsory visual block"))
        assertTrue(full.contains("NEVER transform planning bullets into a comparison table"))
        assertTrue(full.contains("mockup_card is optional ONLY"))
        assertTrue(compact.contains("A visual is OPTIONAL"))
        assertTrue(compact.contains("ordinary list for steps/features"))
        val plain = """{"blocks":[{"type":"heading","text":"Features"},
            {"type":"list","items":["Home","Cart"]},
            {"type":"text","style":"closer","text":"Meri advice: start with notes."}]}"""
        val blocks = RichBlockParser.parse(plain)
        assertEquals(3, blocks.size)
        assertFalse(blocks.any { WorkspaceRichOutputBudget.isVisual(it) })
    }

    @Test fun localIconMapAndReadOnlyAppContextAreDeterministic() {
        assertEquals("🌐", WorkspaceLocalAppIcons.glyph("Google Chrome"))
        assertEquals("</>", WorkspaceLocalAppIcons.glyph("SPCK Editor"))
        assertEquals(WorkspaceLocalAppIcons.Kind.CHROME,
            WorkspaceLocalAppIcons.kind("Google Chrome"))
        assertEquals(WorkspaceLocalAppIcons.Kind.GOOGLE_KEEP,
            WorkspaceLocalAppIcons.kind("Keep Notes"))
        assertEquals(WorkspaceLocalAppIcons.Kind.SPCK,
            WorkspaceLocalAppIcons.kind("SPCK Editor"))
        assertEquals(WorkspaceLocalAppIcons.Kind.UNKNOWN,
            WorkspaceLocalAppIcons.kind("Unknown app"))
        assertEquals("U", WorkspaceLocalAppIcons.glyph("Unknown app"))
        val focused = WorkspaceRichUserContextInterceptor.queryFor("grocery app")
        assertTrue(focused.contains("android phone"))
        assertTrue(focused.contains("SPCK Editor Chrome"))
        assertEquals("hi bro", WorkspaceRichUserContextInterceptor.queryFor("hi bro"))
    }

    @Test fun mockupAcceptsBothLayoutsAndRejectsUnsafeShapes() {
        val grid = """{"blocks":[{"type":"mockup_card","title":"4 screens",
            "items":["Home","Product","Cart","Checkout"],"layout":"grid"}]}"""
        assertEquals(Block.MockupCard("4 screens",
            listOf("Home","Product","Cart","Checkout"), "grid"),
            RichBlockParser.parse(grid).single())
        val list = """{"blocks":[{"type":"mockup_card","title":"Home preview",
            "items":["Location","Search","Products"],"layout":"list"}]}"""
        assertEquals("list", (RichBlockParser.parse(list).single() as Block.MockupCard).layout)
        assertEquals("Home preview\nLocation\nSearch\nProducts",
            RichBlockParser.visibleText(RichBlockParser.parse(list)))
        assertTrue(RichBlockParser.spokenText(RichBlockParser.parse(list)).isBlank())
        listOf(
            """{"blocks":[{"type":"mockup_card","title":"No items","items":[] }]}""",
            """{"blocks":[{"type":"mockup_card","title":"Bad","items":["A","B"],"layout":"remote"}]}""",
            """{"blocks":[{"type":"mockup_card","title":"Only one","items":["Home"]}]}"""
        ).forEach { assertTrue(RichBlockParser.parse(it).isEmpty()) }
    }

    @Test fun incrementalStreamingEmitsWholeMockupOnly() {
        val prefix = """{"blocks":[{"type":"mockup_card","title":"Screens","items":["Home","""
        val full = prefix + """"Cart"],"layout":"grid"}]}"""
        val reader = RichBlockIncrementalParser()
        assertTrue(reader.update(prefix).isEmpty())
        assertEquals(Block.MockupCard("Screens", listOf("Home","Cart"), "grid"),
            reader.update(full).single())
        assertTrue(reader.update(full).isEmpty())
    }

    @Test fun shortAppContextIsScopedToRelatedPromptNotEveryConversation() {
        assertTrue(WorkspaceRichBlocksContract.shortProjectContext("grocery app")
            .contains("Android phone"))
        assertTrue(WorkspaceRichBlocksContract.shortProjectContext("grocery app")
            .contains("completely free tools"))
        assertEquals("", WorkspaceRichBlocksContract.shortProjectContext("hi bro"))
        val hi = WorkspaceChatGateway.openAiMessages(
            listOf(WorkspaceConversationStore.Message("h1", "user", "hi bro", 0L)),
            extraSystemInstructions = WorkspaceRichBlocksContract.INSTRUCTIONS,
        )
        assertFalse(hi.getJSONObject(0).getString("content")
            .contains("previously worked on a web app using SPCK Editor"))
    }
}
