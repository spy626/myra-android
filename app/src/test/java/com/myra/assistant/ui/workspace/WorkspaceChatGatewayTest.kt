package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatGatewayTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("id", role, text, 1L)

    private fun assertZeroPrice(body: JSONObject) {
        val ceiling = body.getJSONObject("provider").getJSONObject("max_price")
        listOf("prompt", "completion", "request", "image").forEach { category ->
            assertEquals("Nonzero provider price for $category", 0, ceiling.getInt(category))
        }
    }

    @Test fun onlyExplicitWorkspaceFreeRoutesAreExposed() {
        assertEquals(listOf(
            WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceChatGateway.Provider.LLM7_FREE),
            WorkspaceChatGateway.Provider.values().toList())
        val request = WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            "session-secret", listOf(message("user", "this project only")))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message("user", "this project only"))))
        assertEquals("openrouter/free", body.getString("model"))
        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", body.getJSONObject("provider").getString("data_collection"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertZeroPrice(body)
        assertFalse(body.getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"))
        val initial = body.getJSONArray("messages")
        assertEquals("system", initial.getJSONObject(0).getString("role"))
        assertEquals("this project only", initial.getJSONObject(initial.length() - 1).getString("content"))
        assertFalse(request.url.toString().contains("session-secret"))
        assertFalse(body.toString().contains("session-secret"))
        assertEquals("Bearer session-secret", request.header("Authorization"))
        assertEquals(null, request.header("x-goog-api-key"))
        assertEquals("openrouter.ai", request.url.host)
    }

    @Test fun oneTurnSystemInstructionsAreOptionalBoundedAndDoNotRewriteUserTurn() {
        val messages = listOf(message("user", "Review this response"))
        val baseline = WorkspaceChatGateway.openRouterBody(messages)
        assertEquals(baseline, WorkspaceChatGateway.openRouterBody(
            messages, extraSystemInstructions = null))

        val extra = "LYRA ENABLED SKILL — TEST ONLY"
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(
            messages, extraSystemInstructions = extra))
        val projected = body.getJSONArray("messages")
        val system = projected.getJSONObject(0).getString("content")
        assertTrue(system.contains(extra))
        assertEquals(1, Regex(Regex.escape(extra)).findAll(system).count())
        assertEquals("Review this response",
            projected.getJSONObject(projected.length() - 1).getString("content"))

        assertTrue(runCatching {
            WorkspaceChatGateway.openRouterBody(
                messages, extraSystemInstructions = "x".repeat(24_001))
        }.isFailure)
    }

    @Test fun longPastedMessageIsSentInFullWithoutSlicing() {
        val original = "START\n" + "हॉरर कहानी और AI companion\n".repeat(850) + "\nEND"
        val olderThatFits = message("assistant", "old".repeat(15000))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(olderThatFits, message("user", original))))
        assertZeroPrice(body)
        val payload = body.getJSONArray("messages")
        assertEquals(3, payload.length())
        assertEquals(original, payload.getJSONObject(payload.length() - 1).getString("content"))

        val olderTooLarge = message("assistant", "x".repeat(80_000))
        val bounded = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(olderTooLarge, message("user", original))))
            .getJSONArray("messages")
        assertEquals(2, bounded.length())
        assertEquals(original, bounded.getJSONObject(bounded.length() - 1).getString("content"))
    }

    @Test fun photoSentOnlyInCurrentTurnAndNotRetainedInPreviousMessages() {
        val messages = listOf(message("user", "Earlier"), message("assistant", "Okay"), message("user", "Describe photo"))
        val image = WorkspaceChatGateway.Image("image/png", "cG5n")
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(messages, image))
        assertZeroPrice(body)
        val openRouter = body.getJSONArray("messages")
        assertEquals("Earlier", openRouter.getJSONObject(1).getString("content"))
        assertTrue(openRouter.getJSONObject(3).getJSONArray("content").getJSONObject(1)
            .getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
    }

    @Test fun threePhotosAreSentInSelectionOrderOnlyOnLatestTurnWithZeroPrice() {
        val media = listOf("YWJj", "ZGVm", "Z2hp").map {
            WorkspaceChatGateway.Image("image/jpeg", it)
        }
        val conversation = listOf(message("user", "Earlier"), message("assistant", "Okay"),
            message("user", "Compare these three screenshots"))
        val request = WorkspaceChatGateway.request(
            WorkspaceChatGateway.Provider.OPENROUTER_FREE, "test-key", conversation,
            images = media)
        val buffer = okio.Buffer().also { request.body?.writeTo(it) }
        val body = JSONObject(buffer.readUtf8())
        assertZeroPrice(body)
        assertEquals("openrouter/free", body.getString("model"))
        val transcript = body.getJSONArray("messages")
        assertEquals("Earlier", transcript.getJSONObject(1).getString("content"))
        val last = transcript.getJSONObject(transcript.length() - 1).getJSONArray("content")
        assertEquals(4, last.length())
        assertEquals("Compare these three screenshots", last.getJSONObject(0).getString("text"))
        media.forEachIndexed { index, image ->
            val content = last.getJSONObject(index + 1).getJSONObject("image_url")
                .getString("url")
            assertEquals("data:image/jpeg;base64," + image.base64, content)
        }
        assertFalse(body.toString().contains("test-key"))
    }

    @Test fun mediaIsBoundedAndTextOnlyRoutesRejectImagePartsBeforeNetwork() {
        val conversation = listOf(message("user", "Check my photos"))
        val image = WorkspaceChatGateway.Image("image/png", "cG5n")
        assertTrue(runCatching {
            WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
                "test-key", conversation, images = List(11) { image })
        }.isFailure)
        listOf(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceChatGateway.Provider.LLM7_FREE).forEach { provider ->
            assertTrue(runCatching {
                WorkspaceChatGateway.request(provider, "test-key", conversation,
                    images = listOf(image))
            }.isFailure)
        }
    }

    @Test fun upToTenImagesPreserveOrderAndGuardAggregateBudget() {
        val images = (1..WorkspaceMediaLimits.MAX_PHOTOS).map { i ->
            WorkspaceChatGateway.Image("image/jpeg", "YQ==".repeat(i))
        }
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(
            listOf(message("user", "Compare all ten screenshots")), images = images))
        assertZeroPrice(body)
        val messages = body.getJSONArray("messages")
        val parts = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
        assertEquals(11, parts.length())
        images.forEachIndexed { i, image ->
            assertEquals("data:image/jpeg;base64," + image.base64,
                parts.getJSONObject(i + 1).getJSONObject("image_url").getString("url"))
        }
        assertTrue(WorkspaceMediaLimits.imageEnvelopeSizes(images.map { it.base64.length }))
        assertFalse(WorkspaceMediaLimits.imageEnvelopeSizes(List(11) { 4 }))
        assertFalse(WorkspaceMediaLimits.imageEnvelopeSizes(List(10) { 1_270_001 }))
    }

    @Test fun originalVideoAndStandaloneAudioUseNativePartsAndZeroPrice() {
        val user = listOf(message("user", "Analyze this file"))
        val video = WorkspaceChatGateway.NativeVideo("video/mp4", "YWJj")
        val rawVideo = JSONObject(WorkspaceChatGateway.openRouterBody(user, video = video))
        assertZeroPrice(rawVideo)
        val vmsg = rawVideo.getJSONArray("messages")
        val vparts = vmsg.getJSONObject(vmsg.length() - 1).getJSONArray("content")
        assertEquals(2, vparts.length())
        assertEquals("video_url", vparts.getJSONObject(1).getString("type"))
        assertEquals("data:video/mp4;base64,YWJj",
            vparts.getJSONObject(1).getJSONObject("video_url").getString("url"))
        val audio = WorkspaceChatGateway.Audio("audio/mpeg", "YWJj")
        val rawAudio = JSONObject(WorkspaceChatGateway.openRouterBody(user, audio = audio))
        assertZeroPrice(rawAudio)
        val amsg = rawAudio.getJSONArray("messages")
        val aparts = amsg.getJSONObject(amsg.length() - 1).getJSONArray("content")
        assertEquals("input_audio", aparts.getJSONObject(1).getString("type"))
        assertEquals("mp3", aparts.getJSONObject(1).getJSONObject("input_audio").getString("format"))
        assertEquals("YWJj", aparts.getJSONObject(1).getJSONObject("input_audio").getString("data"))
        assertFalse(rawVideo.toString().contains("input_audio"))
    }

    @Test fun textOnlyProvidersRejectNativeMediaBeforeNetworkAndMixedMediaFailsClosed() {
        val user = listOf(message("user", "Check media"))
        val video = WorkspaceChatGateway.NativeVideo("video/mp4", "YWJj")
        val audio = WorkspaceChatGateway.Audio("audio/mpeg", "YWJj")
        for (provider in listOf(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceChatGateway.Provider.LLM7_FREE)) {
            assertTrue(runCatching {
                WorkspaceChatGateway.request(provider, "test-key", user, video = video)
            }.isFailure)
            assertTrue(runCatching {
                WorkspaceChatGateway.request(provider, "test-key", user, audio = audio)
            }.isFailure)
        }
        assertTrue(runCatching {
            WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
                "test-key", user, images = listOf(WorkspaceChatGateway.Image("image/png", "YQ==")),
                video = video)
        }.isFailure)
    }

    @Test fun everyFreeTextRouteGetsLatinOnlyHinglishPolicyWithoutEditingLatestUserTurn() {
        val original = "bro mere paas sirf Android phone hai. 3 steps batao, coding mat karna"
        val messages = listOf(message("user", original))
        val standard = WorkspaceChatGateway.openAiMessages(messages)
        assertTrue(standard.getJSONObject(0).getString("content").contains(
            WorkspaceHinglishReply.PROMPT_RULE))
        assertEquals(original, standard.getJSONObject(standard.length() - 1).getString("content"))

        val groq = JSONObject(WorkspaceGroqFree.body(messages)).getJSONArray("messages")
        assertTrue(groq.getJSONObject(0).getString("content").contains(
            WorkspaceHinglishReply.PROMPT_RULE))
        assertEquals(original, groq.getJSONObject(groq.length() - 1).getString("content"))

        val llm7 = JSONObject(WorkspaceLlm7Free.body(messages)).getJSONArray("messages")
        assertTrue(llm7.getJSONObject(0).getString("content").contains(
            WorkspaceHinglishReply.PROMPT_RULE))
        assertEquals(original, llm7.getJSONObject(llm7.length() - 1).getString("content"))
    }

    @Test fun practicalPlanningInstructionsReachAllFreeProvidersWithoutChangingLatestTurn() {
        val original = "bro mere paas sirf Android phone hai aur mujhe free mein ek simple " +
            "grocery app banana hai. Sabse pehle kya karna chahiye? 3 practical steps " +
            "batao, abhi coding start mat karna 😂"
        val messages = listOf(message("user", original))
        val common = WorkspaceChatGateway.openAiMessages(messages)
        val system = common.getJSONObject(0).getString("content")
        assertTrue(system.contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
        assertTrue(system.contains("ONE coherent feasible starting route"))
        assertTrue(system.contains("device the user HAS"))
        assertTrue(system.contains("minimum viable FIRST version"))
        assertTrue(system.contains("Work stages:"))
        assertTrue(system.contains("visual no-code implementation"))
        assertTrue(system.contains("PRESENTATION CONTRACT"))
        assertTrue(system.contains("native-friendly Markdown"))
        assertTrue(system.contains("CURRENT USER PLANNING BRIEF"))
        assertTrue(system.contains("Requested MAIN step count: 3"))
        assertTrue(system.contains("Phone-only resource explicitly stated: true"))
        assertTrue(system.contains("Free/zero-budget requirement explicitly stated: true"))
        assertTrue(system.contains("Explicit target delivery platform: UNSPECIFIED"))
        assertTrue(system.contains("EXACT MAIN STEP COUNT: give exactly 3"))
        assertTrue(system.contains("PLANNING-ONLY HARD STOP"))
        assertTrue(system.contains("SETUP and IMPLEMENTATION are NOT planning"))
        assertTrue(system.contains("NO coding, signup, builder launch"))
        assertFalse(system.contains("Code-answer formatting when supplying code"))
        assertFalse(system.contains("CURRENT-TURN ANSWER ACCEPTANCE"))
        assertEquals(1, Regex("PLANNING-ONLY HARD STOP").findAll(system).count())
        assertEquals(original, common.getJSONObject(common.length() - 1).getString("content"))

        val groq = JSONObject(WorkspaceGroqFree.body(messages)).getJSONArray("messages")
        val groqSystem = groq.getJSONObject(0).getString("content")
        assertTrue(groqSystem.contains("PRACTICAL PLANNING"))
        assertTrue(groqSystem.contains("EXACT MAIN STEP COUNT: give exactly 3"))
        assertEquals(1, Regex("PLANNING-ONLY HARD STOP").findAll(groqSystem).count())
        assertEquals(original, groq.getJSONObject(groq.length() - 1).getString("content"))

        val llm7 = JSONObject(WorkspaceLlm7Free.body(messages)).getJSONArray("messages")
        assertTrue(llm7.getJSONObject(0).getString("content")
            .contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
        assertEquals(1, Regex("PLANNING-ONLY HARD STOP").findAll(
            llm7.getJSONObject(0).getString("content")
        ).count())
        assertEquals(original,
            llm7.getJSONObject(llm7.length() - 1).getString("content"))
        assertFalse(WorkspaceChatGateway.openAiMessages(listOf(
            message("user", "hi bro, how are you today?")
        )).getJSONObject(0).getString("content")
            .contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
    }

    @Test fun quotaFailureDoesNotExposeProviderBodyOrRetry() {
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429)
            .message("quota").body("secret echoed in response".toResponseBody()).build()
        val failure = runCatching {
            WorkspaceChatGateway.read(WorkspaceChatGateway.Provider.OPENROUTER_FREE, response)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("limit"))
        assertFalse(failure.message.orEmpty().contains("secret echoed"))
    }
    @Test fun normalChatGetsSemanticContinuityWithoutRewritingLatestUserTurn() {
        val messages = listOf(
            message("user", "Earlier we compared repository handoff architecture."),
            message("assistant", "Okay."),
            message("user", "bro wahi concept se continue karo"),
        )
        val payload = WorkspaceChatGateway.openAiMessages(messages)
        val system = payload.getJSONObject(0).getString("content")

        assertTrue(system.contains("SEMANTIC TASK CONTINUITY"))
        assertTrue(system.contains("repository handoff architecture"))
        assertTrue(system.contains("current-turn execution gates remain separate"))
        assertEquals(
            "bro wahi concept se continue karo",
            payload.getJSONObject(payload.length() - 1).getString("content"),
        )
    }

}
