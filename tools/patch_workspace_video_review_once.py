#!/usr/bin/env python3
"""One-time exact-anchor Workspace-only repair, removed after verified CI."""
from pathlib import Path

ROOT = Path('app/src')
BASE = ROOT / 'main/java/com/myra/assistant/ui/workspace'
TEST = ROOT / 'test/java/com/myra/assistant/ui/workspace'


def replace(path, before, after):
    text = path.read_text(encoding='utf-8')
    occurrences = text.count(before)
    if occurrences != 1:
        raise RuntimeError(f'{path}: expected one exact anchor, found {occurrences}: {before[:85]!r}')
    path.write_text(text.replace(before, after, 1), encoding='utf-8')


def insert_test(path, text):
    current = path.read_text(encoding='utf-8')
    if not current.endswith('\n}\n'):
        raise RuntimeError(f'{path}: unexpected test class ending')
    path.write_text(current[:-2] + text + '}\n', encoding='utf-8')


prompt = BASE / 'WorkspacePromptWriting.kt'
replace(prompt, 'private val promptWord = Regex("(?iu)\\\\bprompts?\\\\b|प्रॉम्प्ट|پرومپٹ")',
        'private val promptWord = Regex("(?iu)\\\\b(?:prompts?|promts?)\\\\b|प्रॉम्प्ट|پرومپٹ")')
replace(prompt,
        '"ask the coding AI to confirm it before platform-specific implementation. If the user " +',
        '"ask the coding AI to confirm it before platform-specific implementation. If the user " +\n'
        '                "already specified Android, use Android and do NOT ask to confirm the platform " +\n'
        '                "again. Treat hands-free phone control as permission-scoped Android actions, " +\n'
        '                "not unrestricted control. Do not invent calls, SMS, contacts, location, " +\n'
        '                "wake words, always-listening or broad permissions when unrequested. " +\n'
        '                "Explain Android limits and request runtime consent only for actually needed " +\n'
        '                "capabilities. If the user " +')

follow = BASE / 'WorkspacePromptFollowUp.kt'
replace(follow,
        '"permissions or unrelated features just because hands-free interaction was requested. " +',
        '"permissions or unrelated features just because hands-free interaction was requested. " +\n'
        '            "If Android is already specified, use it directly; do not reconfirm the " +\n'
        '            "platform or introduce an alternative. " +')

groq = BASE / 'WorkspaceGroqFree.kt'
replace(groq,
        '    private const val MAX_RESPONSE_BYTES = 96_000L\n',
        '    private const val MAX_RESPONSE_BYTES = 96_000L\n\n'
        '    /** Includes generated system instructions, not just raw chat characters. */\n'
        '    internal fun withinBudget(messages: List<WorkspaceConversationStore.Message>): Boolean =\n'
        '        runCatching { withinBudget(JSONObject(WorkspaceChatGateway.openRouterBody(messages))) }\n'
        '            .getOrDefault(false)\n\n'
        '    private fun withinBudget(json: JSONObject): Boolean {\n'
        '        val entries = json.optJSONArray("messages") ?: return false\n'
        '        return (0 until entries.length()).sumOf { index ->\n'
        '            (entries.getJSONObject(index).opt("content") as? String)?.length\n'
        '                ?: (MAX_PROMPT_CHARS + 1)\n'
        '        } <= MAX_PROMPT_CHARS\n'
        '    }\n')
replace(groq,
        '        val recent = WorkspaceLongInputPolicy.outbound(messages)\n'
        '        require(recent.isNotEmpty() && recent.last().role == "user") { "Latest user message is required" }\n'
        '        require(recent.sumOf { it.text.length } <= MAX_PROMPT_CHARS) {\n'
        '            "Groq Free request exceeds LYRA\'s conservative free-quota budget; complete prompt saved locally, nothing sent. Use OpenRouter Free for a larger request."\n'
        '        }\n'
        '        val json = JSONObject(WorkspaceChatGateway.openRouterBody(messages))',
        '        val json = JSONObject(WorkspaceChatGateway.openRouterBody(messages))\n'
        '        require(withinBudget(json)) {\n'
        '            "Groq Free request exceeds LYRA\'s conservative free-quota budget; complete prompt saved locally, nothing sent. Use OpenRouter Free for a larger request."\n'
        '        }')

selector = BASE / 'WorkspaceFreeProviderSelection.kt'
if selector.exists():
    raise RuntimeError('Provider selection file already exists')
selector.write_text('''package com.myra.assistant.ui.workspace

/** Single deterministic, local selection policy. No automatic retry or paid route. */
internal object WorkspaceFreeProviderSelection {
    fun choose(openRouterAvailable: Boolean, groqAvailable: Boolean,
               groqFreeZdrApproved: Boolean, groqWithinBudget: Boolean,
               hasAttachments: Boolean): WorkspaceChatGateway.Provider? = when {
        // Groq is text-only; an explicitly chosen photo/document stays off Groq.
        hasAttachments -> if (openRouterAvailable) WorkspaceChatGateway.Provider.OPENROUTER_FREE else null
        groqFreeZdrApproved && groqAvailable && groqWithinBudget ->
            WorkspaceChatGateway.Provider.GROQ_FREE
        openRouterAvailable -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
        else -> null
    }
}
''', encoding='utf-8')

activity = BASE / 'WorkspaceActivity.kt'
replace(activity,
        '        val picked = attachments.toList()\n        val stored = runCatching { conversations.append(id, "user", text) }',
        '        val picked = attachments.toList()\n'
        '        // Keep the unsent draft and chosen files if only text-only Groq is available.\n'
        '        if (picked.isNotEmpty() && keys.get(ApiKeyStore.OPENROUTER).isBlank() &&\n'
        '            intent == null && (current.type == WorkspaceProjectType.CHAT ||\n'
        '                !WorkspaceChatIntent.isCodingFollowUp(text))) {\n'
        '            statusMessage = "Groq Free is text-only. Add an OpenRouter Free key for this " +\n'
        '                "selected file/photo, or remove it. Draft and attachments stay on this phone."\n'
        '            render()\n'
        '            return\n'
        '        }\n'
        '        val stored = runCatching { conversations.append(id, "user", text) }')
replace(activity,
        'val provider = runCatching { selectedProvider(text) }',
        'val provider = runCatching { selectedProvider(picked.isNotEmpty()) }')
replace(activity,
        '        val enriched = runCatching {\n            val addition = picked.filterNot { it.mime.startsWith("image/") }',
        '        if (provider == WorkspaceChatGateway.Provider.GROQ_FREE && picked.isNotEmpty()) {\n'
        '            statusMessage = "Groq Free is text-only; no selected photo/file was sent."\n'
        '            render()\n'
        '            return\n'
        '        }\n'
        '        val enriched = runCatching {\n            val addition = picked.filterNot { it.mime.startsWith("image/") }')
old_selection = '''    /** Single Workspace selection point. Groq stays OFF unless explicitly opted in as Free+ZDR.
     * Large prompts use the already configured $0 OpenRouter route, never a paid route.
     * Groq has no price ceiling: turn the opt-in OFF before changing its account tier.
     */
    private fun selectedProvider(latestText: String = ""): WorkspaceChatGateway.Provider? {
        val openRouterKey = keys.get(ApiKeyStore.OPENROUTER)
        val groqKey = keys.get(ApiKeyStore.GROQ)
        val groqEnabled = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        return when {
            groqEnabled && groqKey.isNotBlank() &&
                (latestText.length <= WorkspaceGroqFree.MAX_PROMPT_CHARS || openRouterKey.isBlank()) ->
                WorkspaceChatGateway.Provider.GROQ_FREE
            openRouterKey.isNotBlank() -> WorkspaceChatGateway.Provider.OPENROUTER_FREE
            else -> null
        }
    }
'''
new_selection = '''    /** Single Workspace selection point; full same-chat request budget, never only latest text.
     * Attachments use the existing OpenRouter route or stay local; no auto retry or paid route.
     * Groq Free/ZDR opt-in does not certify an account that is later upgraded to paid.
     */
    private fun selectedProvider(hasAttachments: Boolean = false): WorkspaceChatGateway.Provider? {
        val openRouterAvailable = keys.get(ApiKeyStore.OPENROUTER).isNotBlank()
        val groqAvailable = keys.get(ApiKeyStore.GROQ).isNotBlank()
        val groqApproved = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        val history = selectedId?.let { runCatching { conversations.read(it) }.getOrNull() }
        // Retry of an existing assistant reply uses its preceding user turn.
        val candidate = if (history?.lastOrNull()?.role == "assistant") history.dropLast(1) else history
        val groqFits = candidate?.takeIf { it.lastOrNull()?.role == "user" }
            ?.let { WorkspaceGroqFree.withinBudget(it) } ?: false
        return WorkspaceFreeProviderSelection.choose(openRouterAvailable, groqAvailable,
            groqApproved, groqFits, hasAttachments)
    }
'''
replace(activity, old_selection, new_selection)
replace(activity,
        'AlertDialog.Builder(this).setTitle("No Workspace provider key")',
        'AlertDialog.Builder(this).setTitle("No eligible Workspace free route")')
replace(activity,
        '"Add an OpenRouter key, or add a Groq key and enable the Free + Inference ZDR switch in API & Cloud Settings. Gemini remains Voice-only. No paid fallback is available."',
        '"Add an OpenRouter Free key for long requests or attachments, or enable Groq Free + Inference ZDR for bounded text chat in API & Cloud Settings. Full message stays local if no route fits. Gemini is Voice-only; no paid fallback."')

writing_test = TEST / 'WorkspacePromptWritingTest.kt'
insert_test(writing_test, '''
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
''')

groq_test = TEST / 'WorkspaceGroqFreeTest.kt'
insert_test(groq_test, '''
    @Test fun entireProviderPromptBudgetIncludesSystemAndHistory() {
        val near = message("user", "AI companion banane ke liye promt do " + "x".repeat(11_950))
        assertFalse(WorkspaceGroqFree.withinBudget(listOf(near)))
        assertTrue(runCatching { WorkspaceGroqFree.body(listOf(near)) }.isFailure)
        val history = listOf(message("user", "first"),
            message("assistant", "a".repeat(11_990)), message("user", "follow up"))
        assertFalse(WorkspaceGroqFree.withinBudget(history))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true,
                WorkspaceGroqFree.withinBudget(history), false))
    }

    @Test fun attachmentsNeverChooseGroqAndDoNotAutoEnableWithoutConsent() {
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, true))
        assertEquals(null, WorkspaceFreeProviderSelection.choose(false, true, true, true, true))
        assertEquals(null, WorkspaceFreeProviderSelection.choose(false, true, false, true, false))
        assertEquals(WorkspaceChatGateway.Provider.GROQ_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, true, true, false))
        assertEquals(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceFreeProviderSelection.choose(true, true, false, true, false))
    }
''')

print('Video regression patch applied to six Workspace-only source/test files.')
