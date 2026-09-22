from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')

def patch(file, before, after):
    path = ROOT / file
    text = path.read_text()
    matches = text.count(before)
    if matches != 1:
        raise RuntimeError(f'{path}: expected exactly one patch anchor, got {matches}')
    path.write_text(text.replace(before, after, 1))

patch('WorkspaceChatVisibleReply.kt',
      '(think|reasoning|analysis)', '(think|thinking|reasoning|analysis)')
patch('WorkspaceChatVisibleReply.kt',
      '(?:think|reasoning|analysis)', '(?:think|thinking|reasoning|analysis)')
# Replace a removed reasoning block with one normal word separator, not three.
patch('WorkspaceChatVisibleReply.kt',
      '        return speech\n',
      '        return speech.replace(Regex("""[ \\t]{2,}"""), " ")\n')
# Match the first negative action; a second positive action could be a replacement plan.
patch('WorkspaceChatPlanStatus.kt',
      r'\b.{0,45}\b', r'\b.{0,45}?\b')

patch('WorkspaceChatTurnFrame.kt',
'''    fun verify(messages: List<WorkspaceConversationStore.Message>, reply: String): String {
        if (!isCasual(messages)) return reply
        val recentUsers = messages.takeLast(8).filter { it.role == "user" }
        if (recentUsers.none { shortPreference.containsMatchIn(it.text) }) return reply
        val anchor = topic(messages)?.let(::tokens).orEmpty()
        if (anchor.isEmpty() || reply.length < 100) return reply
        val responseWords = tokens(reply)
        if (anchor.intersect(responseWords).isNotEmpty()) return reply
        throw IllegalArgumentException(
            "LYRA's long reply may be off-topic, so it was not saved. Tap Retry or choose another approved Free model; no automatic resend."
        )
    }
''',
'''    fun verify(messages: List<WorkspaceConversationStore.Message>, reply: String): String {
        if (!isCasual(messages)) return reply
        // All three Free routes arrive here before any assistant text is persisted.
        val visible = WorkspaceChatVisibleReply.sanitize(reply)
        val recentUsers = messages.takeLast(8).filter { it.role == "user" }
        if (recentUsers.none { shortPreference.containsMatchIn(it.text) }) return visible
        val anchor = topic(messages)?.let(::tokens).orEmpty()
        if (anchor.isEmpty() || visible.length < 100) return visible
        val responseWords = tokens(visible)
        if (anchor.intersect(responseWords).isNotEmpty()) return visible
        throw IllegalArgumentException(
            "LYRA's long reply may be off-topic, so it was not saved. Tap Retry or choose another approved Free model; no automatic resend."
        )
    }
''')

patch('WorkspaceActivity.kt',
'''                WorkspaceChatRecallGrounding.answer(conversations.read(id))
''',
'''                val selectedChat = conversations.read(id)
                WorkspaceChatRecallGrounding.answer(selectedChat)
                    ?: WorkspaceChatPlanStatus.answer(selectedChat)
''')

patch('WorkspaceChatGateway.kt',
'''            "when the user asks for a short reply. For a task, question, story, code, or " +
''',
'''            "when the user asks for a short reply. Use complete everyday words, not clipped " +
            "fragments. If a plan is cancelled without a replacement being shared, say only " +
            "that no replacement was shared here; do not claim the user decided on nothing. " +
            "For a task, question, story, code, or " +
''')
print('Applied completed-chat visible-boundary and grounded status integration')
