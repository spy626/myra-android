#!/usr/bin/env python3
"""One-time, exact-anchor patch. Do not reuse after first successful run."""
from pathlib import Path

root = Path("app/src/main/java/com/myra/assistant/ui/workspace")
activity = root / "WorkspaceActivity.kt"
tests = Path("app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceChatGatewayTest.kt")

def replace_once(path, old, new):
    content = path.read_text(encoding="utf-8")
    matches = content.count(old)
    if matches != 1:
        raise RuntimeError(f"{path}: expected one exact anchor, found {matches}: {old[:100]!r}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8")

replace_once(activity,
'''    /** Gemini remains voice-only; no silent paid or model fallback for Workspace. */
    private fun selectedProvider(): WorkspaceChatGateway.Provider? =
        if (keys.get(ApiKeyStore.OPENROUTER).isNotBlank())
            WorkspaceChatGateway.Provider.OPENROUTER_FREE else null

    private fun keyFor(provider: WorkspaceChatGateway.Provider): String = when (provider) {
        WorkspaceChatGateway.Provider.OPENROUTER_FREE -> keys.get(ApiKeyStore.OPENROUTER)
    }
''',
'''    /** Single Workspace selection point. Groq stays OFF unless explicitly opted in as Free+ZDR.
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

    private fun keyFor(provider: WorkspaceChatGateway.Provider): String = when (provider) {
        WorkspaceChatGateway.Provider.OPENROUTER_FREE -> keys.get(ApiKeyStore.OPENROUTER)
        WorkspaceChatGateway.Provider.GROQ_FREE -> keys.get(ApiKeyStore.GROQ)
    }
''')

replace_once(activity,
'''        val provider = runCatching { selectedProvider() }
            .getOrElse { statusMessage = "Secure provider key storage unavailable. Edit saved locally."; render(); return }
''',
'''        val provider = runCatching { selectedProvider(conversations.read(id).lastOrNull()?.text.orEmpty()) }
            .getOrElse { statusMessage = "Secure provider key storage unavailable. Edit saved locally."; render(); return }
''')

replace_once(activity,
'''        val provider = runCatching { selectedProvider() }
            .getOrElse { toast("Secure provider key storage unavailable"); return }
''',
'''        val provider = runCatching { selectedProvider(user.text) }
            .getOrElse { toast("Secure provider key storage unavailable"); return }
''')

replace_once(activity,
'''        val picked = attachments.toList()
        val stored = runCatching { conversations.append(id, "user", text) }
''',
'''        val picked = attachments.toList()
        // The Groq Free opt-in covers text chat only. Never silently send photos or files
        // to a different provider just because another API key exists.
        if (intent == null && current.type == WorkspaceProjectType.CHAT && picked.isNotEmpty() &&
            preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false) &&
            keys.get(ApiKeyStore.GROQ).isNotBlank()) {
            statusMessage = "Groq Free is text-only. Remove the attachment or turn Groq OFF in Settings before sending. Nothing was sent."
            render()
            return
        }
        val stored = runCatching { conversations.append(id, "user", text) }
''')

replace_once(activity,
'''        val provider = runCatching { selectedProvider() }
            .getOrElse { statusMessage = "Secure key storage unavailable. Message saved locally."; render(); return }
''',
'''        val provider = runCatching { selectedProvider(text) }
            .getOrElse { statusMessage = "Secure key storage unavailable. Message saved locally."; render(); return }
''')

replace_once(activity,
'''                .setMessage("Add an OpenRouter key in API & Cloud Settings. Gemini remains Voice-only. No paid fallback is available.")
''',
'''                .setMessage("Add an OpenRouter key, or add a Groq key and enable the Free + Inference ZDR switch in API & Cloud Settings. Gemini remains Voice-only. No paid fallback is available.")
''')

replace_once(tests,
'''    @Test fun onlyNonVoiceOpenRouterRouteIsExposed() {
        assertEquals(listOf(WorkspaceChatGateway.Provider.OPENROUTER_FREE),
''',
'''    @Test fun onlyExplicitWorkspaceFreeRoutesAreExposed() {
        assertEquals(listOf(WorkspaceChatGateway.Provider.OPENROUTER_FREE,
            WorkspaceChatGateway.Provider.GROQ_FREE),
''')
print("Exact Groq opt-in activation patch applied to WorkspaceActivity and gateway tests")
