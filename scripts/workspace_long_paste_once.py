"""One-time anchored source edit. The invoking workflow and this script are removed after success."""
from pathlib import Path
BASE = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace')
changes = {}

def replace(path, before, after, count=1):
    content = changes.get(path, path.read_text(encoding='utf-8'))
    found = content.count(before)
    if found != count:
        raise AssertionError(f'{path}: expected {count} occurrences, found {found}: {before[:95]!r}')
    changes[path] = content.replace(before, after, count)

activity = BASE / 'WorkspaceActivity.kt'
replace(activity, '            maxLines = 5\n            inputType =',
        '            maxLines = 8\n            isVerticalScrollBarEnabled = true\n            inputType =')
replace(activity, '            filters = arrayOf(InputFilter.LengthFilter(4_000))',
        '            // Never silently truncate a pasted prompt. Check the full text on Send.\n            filters = emptyArray<InputFilter>()', count=2)
replace(activity, '                val revisedText = input.text.toString().trim()',
        '                val revisedText = input.text.toString()')
replace(activity, 'if (revisedText.isBlank() || revisedText.length > 4_000) {',
        'if (!WorkspaceLongInputPolicy.sendable(revisedText)) {')
replace(activity, 'toast("Message must contain 1–4000 characters")',
        'toast("Message must contain 1–${WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS} characters; edit remains open if too long")')
replace(activity, '        val text = composer.text.toString().trim()\n        if (text.isEmpty()) { toast("Write a message first"); return }',
        '        val text = composer.text.toString()\n        if (text.isBlank()) { toast("Write a message first"); return }\n'
        '        if (!WorkspaceLongInputPolicy.sendable(text)) {\n'
        '            statusMessage = "The complete pasted draft is still in the chat box. " +\n'
        '                "This app supports up to ${WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS} characters per message; nothing was sent."\n'
        '            render()\n'
        '            return\n'
        '        }')
replace(activity, '            require(expanded.length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {',
        '            require(expanded.length <= WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS) {')

store = BASE / 'WorkspaceConversationStore.kt'
replace(store, 'const val MAX_MESSAGE_LENGTH = 6_000',
        'const val MAX_MESSAGE_LENGTH = WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS')
replace(store, 'private const val MAX_FILE_BYTES = 1_200_000L',
        'private const val MAX_FILE_BYTES = 16_000_000L')
replace(store, 'private fun checkedText(text: String): String = text.trim().also {',
        'private fun checkedText(text: String): String = text.also {')
replace(store, '"Message must contain 1–6000 characters"',
        '"Message must contain 1–${MAX_MESSAGE_LENGTH} characters"')

policy = BASE / 'WorkspaceLongInputPolicy.kt'
replace(policy, '    fun sendable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_MESSAGE_CHARS\n'
        '    fun requestFits(messages: List<WorkspaceConversationStore.Message>): Boolean =\n'
        '        messages.takeLast(8).sumOf { it.text.length.toLong() } <= MAX_REQUEST_CHARS',
        '    fun sendable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_MESSAGE_CHARS\n'
        '    fun requestFits(messages: List<WorkspaceConversationStore.Message>): Boolean =\n'
        '        messages.lastOrNull()?.let { it.text.length <= MAX_REQUEST_CHARS } == true\n\n'
        '    /** Select only complete preceding messages, with the latest user turn always intact. */\n'
        '    fun outbound(messages: List<WorkspaceConversationStore.Message>): List<WorkspaceConversationStore.Message> {\n'
        '        require(messages.lastOrNull()?.role == "user") { "A user message is required" }\n'
        '        require(requestFits(messages)) { "Prompt exceeds the free-route request limit; full message remains saved locally" }\n'
        '        val selected = mutableListOf<WorkspaceConversationStore.Message>()\n'
        '        var remaining = MAX_REQUEST_CHARS\n'
        '        for (message in messages.takeLast(8).asReversed()) {\n'
        '            if (message.text.length > remaining) break\n'
        '            selected.add(message)\n'
        '            remaining -= message.text.length\n'
        '        }\n'
        '        return selected.asReversed()\n'
        '    }')

gateway = BASE / 'WorkspaceChatGateway.kt'
replace(gateway, '        val recent = messages.takeLast(8)\n'
        '        require(recent.sumOf { it.text.length } <= 12_000) { "Conversation is too long for one private request" }',
        '        // Previous turns are dropped whole when needed; the latest pasted prompt is never sliced.\n'
        '        require(WorkspaceLongInputPolicy.requestFits(messages)) {\n'
        '            "Full prompt exceeds this free route\'s 64000-character request cap; saved locally, nothing sent"\n'
        '        }')
replace(gateway, '        val recent = messages.takeLast(8)\n        val latest = recent.lastOrNull()',
        '        val recent = WorkspaceLongInputPolicy.outbound(messages)\n        val latest = recent.lastOrNull()')

test = TEST / 'WorkspaceLongInputPolicyTest.kt'
replace(test, '        assertTrue(WorkspaceLongInputPolicy.requestFits(listOf(message("a".repeat(32_000)))))\n'
        '        assertFalse(WorkspaceLongInputPolicy.requestFits(listOf(message("b".repeat(40_000)), message("c".repeat(30_000)))))',
        '        assertTrue(WorkspaceLongInputPolicy.requestFits(listOf(message("a".repeat(32_000)))))\n'
        '        assertTrue(WorkspaceLongInputPolicy.requestFits(listOf(message("b".repeat(40_000)), message("c".repeat(30_000)))))\n'
        '        val latest = message("c".repeat(30_000))\n'
        '        val outgoing = WorkspaceLongInputPolicy.outbound(listOf(message("b".repeat(40_000)), latest))\n'
        '        assertTrue(outgoing.size == 1)\n'
        '        assertTrue(outgoing.single().text == latest.text)\n'
        '        assertFalse(WorkspaceLongInputPolicy.requestFits(listOf(message("z".repeat(WorkspaceLongInputPolicy.MAX_REQUEST_CHARS + 1)))))')

gateway_test = TEST / 'WorkspaceChatGatewayTest.kt'
needle = '    @Test fun photoSentOnlyInCurrentTurnAndNotRetainedInPreviousMessages() {'
insert = '''    @Test fun longPastedMessageIsSentInFullWithoutSlicing() {
        val original = "START\\n" + "हॉरर कहानी और AI companion\\n".repeat(850) + "\\nEND"
        val older = message("assistant", "old".repeat(15000))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(listOf(older, message("user", original))))
        val payload = body.getJSONArray("messages")
        assertEquals(original, payload.getJSONObject(payload.length() - 1).getString("content"))
        assertEquals(1, payload.length())
    }

'''
replace(gateway_test, needle, insert + needle)

for path, content in changes.items():
    path.write_text(content, encoding='utf-8')
    print(f'Updated {path} ({len(content)} chars)')
