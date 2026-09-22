package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCloudflareModelsWebsiteTest {
    private val account = "0123456789abcdef0123456789abcdef"
    private val token = "fixture_not_a_live_token"
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec",
        "Create a mobile-friendly counter", WorkspaceWebsiteGeneration.PATHS.associateWith { null })

    private fun response(request: Request, data: String, type: String = "text/event-stream") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
            .message("OK").header("Content-Type", type).body(data.toResponseBody()).build()

    private fun files(): String = JSONObject().put("files", JSONObject()
        .put("index.html", "<!doctype html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><button id=\"plus\">+</button><script src=\"script.js\"></script></body></html>")
        .put("style.css", "body{margin:0}")
        .put("script.js", "document.getElementById('plus').onclick=()=>{};")).toString()

    private fun chunk(value: String) = "data: " + JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("content", value))
            .put("finish_reason", JSONObject.NULL))).toString() + "\n\n"

    private val end = "data: " + JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject()).put("finish_reason", "stop"))).toString() +
        "\n\ndata: [DONE]\n\n"

    @Test fun allFourModelRoutesAreAllowlistedAndQwenUsesItsOwnParameters() {
        assertEquals(4, WorkspaceCloudflareFree.MODELS.distinct().size)
        WorkspaceCloudflareFree.MODELS.forEach { model ->
            val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot, model)
            assertEquals("api.cloudflare.com", req.url.host)
            assertTrue(req.url.toString().endsWith("/ai/run/$model"))
            val out = Buffer()
            requireNotNull(req.body).writeTo(out)
            val payload = JSONObject(out.readUtf8())
            assertTrue(payload.getBoolean("stream"))
            assertFalse(payload.has("model"))
            assertFalse(payload.has("provider"))
            assertFalse(payload.has("gateway"))
            assertEquals(2, payload.getJSONArray("messages").length())
            assertTrue(payload.getJSONArray("messages").getJSONObject(1)
                .getString("content").contains("existingFiles"))
            if (model == WorkspaceCloudflareFree.MODELS[1]) {
                assertEquals(7000, payload.getInt("max_tokens"))
                assertFalse(payload.has("max_completion_tokens"))
                assertFalse(payload.has("chat_template_kwargs"))
                assertFalse(payload.has("store"))
            } else {
                assertEquals(7000, payload.getInt("max_completion_tokens"))
                assertFalse(payload.has("max_tokens"))
            }
        }
        assertEquals(WorkspaceCloudflareFree.MODEL, WorkspaceCloudflareFree.chosenModel("@cf/paid/not-approved"))
        assertTrue(runCatching { WorkspaceCloudflareFree.endpoint(account, "@cf/paid/not-approved") }.isFailure)
    }

    @Test fun streamingWhitespaceAndSplitJsonMakeOneCompleteThreeFileResult() {
        val model = WorkspaceCloudflareFree.MODELS[1]
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot, model)
        val raw = files()
        val middle = raw.length / 2
        val streamed = chunk(" ") + chunk(raw.substring(0, middle)) + chunk(raw.substring(middle)) + end
        // Separate whitespace outside JSON is legal; never erase whitespace inside chunks.
        val actual = WorkspaceCloudflareFree.readWebsite(response(req, streamed))
        assertEquals(WorkspaceWebsiteGeneration.PATHS.toSet(), actual.keys)
        assertTrue(actual.getValue("index.html").contains("script.js"))
    }

    @Test fun missingDoneAndOutputLimitNeverAcceptPartialWebsite() {
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        val raw = files()
        val early = runCatching { WorkspaceCloudflareFree.readWebsite(response(req, chunk(raw) +
            "data: " + JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("delta", JSONObject()).put("finish_reason", "stop"))).toString() + "\n\n")) }
            .exceptionOrNull()
        assertNotNull(early)
        assertTrue(early!!.message.orEmpty().contains("before a complete reply"))
        val limited = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject()).put("finish_reason", "length"))).toString() + "\n\n"
        val failure = runCatching { WorkspaceCloudflareFree.readWebsite(response(req, chunk(raw) + limited +
            "data: [DONE]\n\n")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("incomplete"))
    }

    @Test fun synchronousWebsiteRepliesRemainSupportedWithoutStreamHeader() {
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        val payload = JSONObject().put("success", true).put("result", JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", files()))))).toString()
        assertEquals(3, WorkspaceCloudflareFree.readWebsite(response(req, payload, "application/json")).size)
    }
}
