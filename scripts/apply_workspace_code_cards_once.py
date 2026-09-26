"""Exact-anchor, single-use patch on agent/myra-phase-1; fail if source changed."""
from pathlib import Path
SRC = Path('app/src/main/java/com/myra/assistant/ui/workspace')

def replace(path, old, new):
    content = path.read_text(encoding='utf-8')
    if content.count(old) != 1:
        raise SystemExit(f'Unsafe patch: expected one exact anchor in {path}; found {content.count(old)}')
    path.write_text(content.replace(old, new), encoding='utf-8')

activity = SRC / 'WorkspaceActivity.kt'
replace(activity,
'''            val story = if (!mine && current.type == WorkspaceProjectType.CHAT)
                WorkspaceStoryScript.card(latestUserPrompt, message.text) else null
            if (story != null) {
''',
'''            val story = if (!mine && current.type == WorkspaceProjectType.CHAT)
                WorkspaceStoryScript.card(latestUserPrompt, message.text) else null
            val codeParts = if (!mine && story == null) WorkspaceCodeBlocks.parse(message.text)
                else emptyList()
            if (story != null) {
''')
replace(activity,
'''            if (story != null) {
                item.addView(WorkspaceStoryCardView.create(this, story) {
                    copyMessage(story.copyText)
                }, LinearLayout.LayoutParams(-1, -2))
            } else {
                val line = LinearLayout(this).apply {
''',
'''            if (story != null) {
                item.addView(WorkspaceStoryCardView.create(this, story) {
                    copyMessage(story.copyText)
                }, LinearLayout.LayoutParams(-1, -2))
            } else if (codeParts.any { it is WorkspaceCodeBlocks.Part.Code }) {
                codeParts.forEach { part ->
                    when (part) {
                        is WorkspaceCodeBlocks.Part.Prose -> item.addView(label("", 15f).apply {
                            text = WorkspaceMarkdownText.render(part.text)
                            setTextIsSelectable(true)
                            setPadding(dp(14), dp(9), dp(14), dp(9))
                        }, LinearLayout.LayoutParams(-1, -2))
                        is WorkspaceCodeBlocks.Part.Code -> item.addView(
                            WorkspaceCodeCardView.create(this, part) { copyMessage(part.source) },
                            LinearLayout.LayoutParams(-1, -2).apply {
                                topMargin = dp(7)
                                bottomMargin = dp(9)
                            })
                    }
                }
            } else {
                val line = LinearLayout(this).apply {
''')

gateway = SRC / 'WorkspaceChatGateway.kt'
replace(gateway,
'''        val instructions = listOf(writingInstructions, earlier).filter(String::isNotBlank)
            .joinToString("\\n\\n")
''',
'''        val codeInstructions = latest?.let(WorkspaceCodePrompt::instructions).orEmpty()
        val instructions = listOf(writingInstructions, earlier, codeInstructions)
            .filter(String::isNotBlank).joinToString("\\n\\n")
''')
print('Applied scoped code-card UI and code-formatting request changes.')
