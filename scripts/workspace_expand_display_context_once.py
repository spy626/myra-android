"""One-time anchored source patch; runner deletes this helper after the branch update."""
from pathlib import Path

SRC = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace')

def replace(path, old, new):
    original = path.read_text(encoding='utf-8')
    count = original.count(old)
    if count != 1:
        raise SystemExit(f'Expected one exact anchor in {path}, found {count}: {old[:90]!r}')
    path.write_text(original.replace(old, new), encoding='utf-8')

activity = SRC / 'WorkspaceActivity.kt'
replace(activity,
    '    private val localDrafts = mutableMapOf<String, String>()\n',
    '    private val localDrafts = mutableMapOf<String, String>()\n'
    '    // Display-only state: never alters persisted messages, Copy or provider requests.\n'
    '    private val expandedMessageIds = mutableSetOf<String>()\n')
replace(activity,
    '                line.addView(bubble, LinearLayout.LayoutParams(-2, -2))\n'
    '                item.addView(line, LinearLayout.LayoutParams(-1, -2))\n'
    '            }\n',
    '                line.addView(bubble, LinearLayout.LayoutParams(-2, -2))\n'
    '                item.addView(line, LinearLayout.LayoutParams(-1, -2))\n'
    '                if (mine && WorkspaceMessageDisplayPolicy.shouldCollapse(message.text)) {\n'
    '                    val messageKey = "${current.projectId}:${message.id}"\n'
    '                    val toggle = label("Show more", 12f).apply {\n'
    '                        gravity = Gravity.END\n'
    '                        setTextColor(Color.rgb(168, 255, 178))\n'
    '                        setPadding(dp(8), dp(2), dp(12), dp(8))\n'
    '                        isClickable = true\n'
    '                        isFocusable = true\n'
    '                    }\n'
    '                    fun display(expanded: Boolean) {\n'
    '                        bubble.maxLines = if (expanded) Int.MAX_VALUE else\n'
    '                            WorkspaceMessageDisplayPolicy.COLLAPSED_LINES\n'
    '                        bubble.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END\n'
    '                        toggle.text = if (expanded) "Show less" else "Show more"\n'
    '                        toggle.contentDescription = if (expanded) "Show less of your message" else\n'
    '                            "Show full message"\n'
    '                    }\n'
    '                    display(messageKey in expandedMessageIds)\n'
    '                    toggle.setOnClickListener {\n'
    '                        if (!expandedMessageIds.add(messageKey)) expandedMessageIds.remove(messageKey)\n'
    '                        display(messageKey in expandedMessageIds)\n'
    '                    }\n'
    '                    item.addView(toggle, LinearLayout.LayoutParams(-1, -2))\n'
    '                }\n'
    '            }\n')

policy = SRC / 'WorkspaceLongInputPolicy.kt'
replace(policy,
    '    const val MAX_REQUEST_CHARS = 64_000\n    const val MAX_CODING_TASK_CHARS = 500\n',
    '    // Local history budget, not a promise that every free model supports this context.\n'
    '    const val MAX_REQUEST_CHARS = 96_000\n'
    '    const val MAX_RECENT_MESSAGES = 24\n'
    '    const val MAX_CODING_TASK_CHARS = 500\n')
replace(policy,
    '        messages.lastOrNull()?.let { it.text.length <= MAX_REQUEST_CHARS } == true\n',
    '        messages.lastOrNull()?.let { it.text.length <= MAX_MESSAGE_CHARS } == true\n')
replace(policy, 'messages.takeLast(8).asReversed()',
        'messages.takeLast(MAX_RECENT_MESSAGES).asReversed()')

projection = SRC / 'WorkspaceContextProjection.kt'
replace(projection, 'if (messages.size <= 8 || messages.lastOrNull()?.role != "user") return ""',
        'if (messages.size <= WorkspaceLongInputPolicy.MAX_RECENT_MESSAGES ||\n'
        '            messages.lastOrNull()?.role != "user") return ""')
replace(projection, 'messages.takeLast(8).filter',
        'messages.takeLast(WorkspaceLongInputPolicy.MAX_RECENT_MESSAGES).filter')
replace(projection, 'messages.dropLast(8).asReversed()',
        'messages.dropLast(WorkspaceLongInputPolicy.MAX_RECENT_MESSAGES).asReversed()')

gateway = SRC / 'WorkspaceChatGateway.kt'
replace(gateway,
        '"Full prompt exceeds this free route\'s 64000-character request cap; saved locally, nothing sent"',
        '"Full message exceeds LYRA\'s 64000-character local message cap; saved locally, nothing sent"')
replace(gateway,
        '                .put("allow_fallbacks", false))\n            .put("messages", entries).toString()',
        '                .put("allow_fallbacks", false))\n'
        '            // OpenRouter may otherwise compress/truncate the middle on small endpoints.\n'
        '            // Never permit silent truncation of the user\'s full pasted prompt.\n'
        '            .put("plugins", JSONArray().put(JSONObject().put("id", "context-compression")\n'
        '                .put("enabled", false)))\n'
        '            .put("messages", entries).toString()')

helper = SRC / 'WorkspaceMessageDisplayPolicy.kt'
if helper.exists():
    raise SystemExit('Display policy already exists; inspect it instead of overwriting')
helper.write_text('''package com.myra.assistant.ui.workspace

/** UI-only collapsing. Full message stays in the TextView, store, Copy and request. */
internal object WorkspaceMessageDisplayPolicy {
    const val COLLAPSED_LINES = 5
    fun shouldCollapse(text: String): Boolean =
        text.length > 240 || text.count { it == '\\n' } >= COLLAPSED_LINES
}
''', encoding='utf-8')

display_test = TEST / 'WorkspaceMessageDisplayPolicyTest.kt'
if display_test.exists():
    raise SystemExit('Display test already exists; inspect instead of overwriting')
display_test.write_text('''package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMessageDisplayPolicyTest {
    @Test fun shortMessagesStayExpanded() {
        assertFalse(WorkspaceMessageDisplayPolicy.shouldCollapse("Hi bro"))
        assertFalse(WorkspaceMessageDisplayPolicy.shouldCollapse("Line one\\nLine two"))
    }

    @Test fun longOrMultilinePromptsGetFiveLineToggleWithoutChangingText() {
        val pasted = "A".repeat(WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS)
        assertTrue(WorkspaceMessageDisplayPolicy.shouldCollapse(pasted))
        assertEquals(WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS, pasted.length)
        assertTrue(WorkspaceMessageDisplayPolicy.shouldCollapse("a\\nb\\nc\\nd\\ne\\nf"))
        assertEquals(5, WorkspaceMessageDisplayPolicy.COLLAPSED_LINES)
    }
}
''', encoding='utf-8')

long_test = TEST / 'WorkspaceLongInputPolicyTest.kt'
replace(long_test,
        '        assertTrue(outgoing.size == 1)\n        assertTrue(outgoing.single().text == latest.text)\n',
        '        assertTrue(outgoing.size == 2)\n'
        '        assertTrue(outgoing.last().text == latest.text)\n'
        '        val overBudget = WorkspaceLongInputPolicy.outbound(listOf(\n'
        '            message("b".repeat(40_000)), message("c".repeat(64_000))))\n'
        '        assertTrue(overBudget.size == 1)\n'
        '        assertTrue(overBudget.single().text == "c".repeat(64_000))\n'
        '        val history = (1..30).map { message("turn-$it " + "x".repeat(1_000)) }\n'
        '        val recent = WorkspaceLongInputPolicy.outbound(history)\n'
        '        assertTrue(recent.size == WorkspaceLongInputPolicy.MAX_RECENT_MESSAGES)\n'
        '        assertTrue(recent.first().text == history[6].text)\n')

context_test = TEST / 'WorkspaceContextProjectionTest.kt'
replace(context_test,
        'val filler = (1..5).flatMap { listOf(user("Unrelated food topic $it"), assistant("Guess: buy a phone $it")) }',
        'val filler = (1..13).flatMap { listOf(user("Unrelated food topic $it"), assistant("Guess: buy a phone $it")) }')
replace(context_test,
        'assertEquals(9, sent.length()) // one bounded system note, last eight raw messages',
        'assertEquals(25, sent.length()) // one bounded system note, last 24 raw messages')
replace(context_test, 'sent.getJSONObject(8).getString("content")',
        'sent.getJSONObject(24).getString("content")')
replace(context_test,
        'val filler = (1..5).flatMap { listOf(user("Gardening flowers number $it"), assistant("Okay $it")) }',
        'val filler = (1..13).flatMap { listOf(user("Gardening flowers number $it"), assistant("Okay $it")) }')

chat_test = TEST / 'WorkspaceChatGatewayTest.kt'
replace(chat_test,
        '        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))\n',
        '        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))\n'
        '        assertFalse(body.getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"))\n')

print('Applied bounded expandable-message display and 24-turn, 96k-char local context patch.')
