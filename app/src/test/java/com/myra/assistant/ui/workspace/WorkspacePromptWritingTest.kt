package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspacePromptWritingTest {
    private fun message(role: String, text: String) =
        WorkspaceConversationStore.Message("test", role, text, 1L)

    private fun sent(prompt: String): org.json.JSONArray = JSONObject(
        WorkspaceChatGateway.openRouterBody(listOf(message("user", prompt))))
        .getJSONArray("messages")

    @Test fun buildingACompanionProducesDevelopmentBriefInsteadOfCharacterSystemPrompt() {
        val prompt = "Mujhe ek ai companion banane hai mujhe prompt do"
        assertEquals(WorkspacePromptWriting.Kind.BUILD, WorkspacePromptWriting.kind(prompt))
        val entries = sent(prompt)
        assertEquals("system", entries.getJSONObject(0).getString("role"))
        val instructions = entries.getJSONObject(0).getString("content")
        assertTrue(instructions.contains("CODING/DEVELOPMENT AI"))
        assertTrue(instructions.contains("BUILD the described"))
        assertTrue(instructions.contains("not a role-play or personality-only system prompt"))
        assertTrue(instructions.contains("acceptance criteria"))
        assertTrue(instructions.contains("NEXT STEP:"))
        assertEquals(prompt, entries.getJSONObject(1).getString("content"))
        assertEquals(WorkspacePromptWriting.Kind.BUILD,
            WorkspacePromptWriting.kind("Write me a developer prompt to build an AI companion app"))
    }

    @Test fun explicitPersonalityPromptIsNotTransformedIntoSoftwareBuildBrief() {
        val prompt = "Mujhe AI companion ka personality system prompt do"
        assertEquals(WorkspacePromptWriting.Kind.PERSONA, WorkspacePromptWriting.kind(prompt))
        val instructions = sent(prompt).getJSONObject(0).getString("content")
        assertTrue(instructions.contains("personality/system prompt"))
        assertFalse(instructions.contains("CODING/DEVELOPMENT AI"))
        assertNull(WorkspacePromptWriting.kind("What is prompt engineering?"))
        assertNull(WorkspacePromptWriting.kind("Hi bro, kese ho"))
        assertNull(WorkspacePromptWriting.kind("Mujhe horror story do"))
        assertEquals(1, sent("Hi bro, kese ho").length())
        assertEquals(WorkspaceStoryScript.Kind.STORY,
            WorkspaceStoryScript.kind("Mujhe video ke liye horror story do"))
    }

    @Test fun copyContainsOnlyReusablePromptAndTitleStaysInsideCard() {
        val prompt = "Mujhe ek AI companion banane hai mujhe prompt do"
        val reply = "INTRO: Isse coding AI ko do.\n" +
            "TITLE: Build an Intelligent AI Companion\n" +
            "PROMPT:\nAct as an Android engineer.\n\n1. Build chat first.\n" +
            "2. Test memory before claiming success.\n" +
            "NEXT STEP: Apna platform specify karke coding AI ko bhejo."
        val card = requireNotNull(WorkspaceStoryScript.card(prompt, reply))
        assertTrue(card.promptCard)
        assertEquals("Build an Intelligent AI Companion", card.title)
        assertEquals("Isse coding AI ko do.", card.intro)
        assertEquals("Act as an Android engineer.\n\n1. Build chat first.\n2. Test memory before claiming success.", card.copyText)
        assertEquals(card.body, card.copyText)
        assertFalse(card.copyText.contains("NEXT STEP"))
        assertFalse(card.copyText.contains("Isse coding AI"))
        assertEquals("Apna platform specify karke coding AI ko bhejo.", card.tip)
    }

    @Test fun unstructuredProviderOutputIsPreservedInsteadOfInventingFeatures() {
        val prompt = "AI companion banane ke liye prompt do"
        val oldResponse = "You are CareCompanion, always remember everything."
        val card = requireNotNull(WorkspaceStoryScript.card(prompt, oldResponse))
        assertTrue(card.promptCard)
        assertEquals(oldResponse, card.copyText)
        assertEquals(oldResponse, card.body)
        assertNull(card.tip)
        assertEquals("Development prompt", card.title)
    }

    @Test fun misspelledPromptStillProducesAndroidDevelopmentBrief() {
        val original = "Mujhe ek ai companion bana hai hand free mujhe ek promt do"
        assertEquals(WorkspacePromptWriting.Kind.BUILD, WorkspacePromptWriting.kind(original))
        val instructions = sent(original).getJSONObject(0).getString("content")
        assertTrue(instructions.contains("CODING/DEVELOPMENT AI"))
        assertTrue(instructions.contains("do NOT ask to confirm the platform"))
        assertTrue(instructions.contains("Do not invent calls, SMS"))
        assertEquals(WorkspacePromptWriting.Kind.BUILD,
            WorkspacePromptWriting.kind("make an android app, promt do"))
        assertNull(WorkspacePromptWriting.kind("hi bro, promt kya hai"))
    }
}
