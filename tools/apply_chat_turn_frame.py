from pathlib import Path

ROOT = Path('app/src')

def patch(path, before, after):
    file = ROOT / path
    source = file.read_text()
    count = source.count(before)
    if count != 1:
        raise RuntimeError(f'{file}: expected one patch anchor, got {count}')
    file.write_text(source.replace(before, after, 1))

patch('main/java/com/myra/assistant/ui/workspace/WorkspaceChatGateway.kt',
'''        val codeInstructions = latest?.let(WorkspaceCodePrompt::instructions).orEmpty()
        val instructions = listOf(CHAT_REPLY_DISCIPLINE, writingInstructions, earlier, codeInstructions)
''',
'''        val codeInstructions = latest?.let(WorkspaceCodePrompt::instructions).orEmpty()
        // AIRI-style turn state is a bounded, read-only projection of this same Chat.
        // Dedicated writing, follow-up, coding and task prompts are never replaced.
        val turnFrame = if (revisionKind == null && contextDecision == null &&
            writingInstructions.isBlank() && codeInstructions.isBlank())
            WorkspaceChatTurnFrame.instructions(recent) else ""
        val instructions = listOf(CHAT_REPLY_DISCIPLINE, turnFrame, writingInstructions, earlier, codeInstructions)
''')

patch('main/java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt',
'''            activeRequest = null
            val failure = result.exceptionOrNull()
            result.onSuccess { reply ->
''',
'''            activeRequest = null
            // Check the completed visible draft against the actual USER topic before
            // saving it. This is deliberately conservative and makes NO new AI call.
            val checked = result.mapCatching { reply ->
                if (projects.getProject(id)?.type == WorkspaceProjectType.CHAT && picked.isEmpty()) {
                    val saved = conversations.read(id)
                    val actual = if (replacingAssistantId != null &&
                        saved.lastOrNull()?.id == replacingAssistantId) saved.dropLast(1) else saved
                    WorkspaceChatTurnFrame.verify(actual, reply)
                } else reply
            }
            val failure = checked.exceptionOrNull()
            checked.onSuccess { reply ->
''')

patch('test/java/com/myra/assistant/ui/workspace/WorkspaceChatNaturalConversationTest.kt',
'''        assertFalse(guidance.contains("chess club"))
        assertFalse(guidance.contains("hiking"))
''',
'''        // A read-only current-turn frame now quotes the actual USER's subject,
        // but it must never promote the assistant's invented activity to evidence.
        assertTrue(guidance.contains("chess club"))
        assertFalse(guidance.contains("hiking"))
''')
print('Applied scoped turn-context, completed-reply, and legacy test updates')
