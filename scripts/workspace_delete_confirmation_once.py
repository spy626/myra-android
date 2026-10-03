#!/usr/bin/env python3
"""Scoped, exact-source phase-branch UX correction. Abort on unknown revisions."""
from pathlib import Path

root = Path('app/src/main/java/com/myra/assistant/ui/workspace')
drawer = root / 'WorkspaceNavigationDrawer.kt'
activity = root / 'WorkspaceActivity.kt'
route = root / 'WorkspaceFreeAiSuggestion.kt'
result = root / 'WorkspaceCodingResult.kt'


def replace_exact(source: str, old: str, new: str, description: str) -> str:
    if source.count(old) != 1:
        raise SystemExit(f'{description}: expected one exact source occurrence, got {source.count(old)}; abort')
    return source.replace(old, new, 1)

source = drawer.read_text(encoding='utf-8')
source = replace_exact(source,
    '        onDeleteChat: (String) -> Unit,\n    ) {',
    '        onDeleteChat: (String) -> Unit,\n        onBeforeDeleteProject: (String) -> Unit,\n    ) {',
    'declare pre-delete cancellation')
start_marker = '        /** This is deliberately NOT called by Delete Chat. Requires exact-name confirmation. */'
end_marker = '        fun row(project: WorkspaceProject, chat: Boolean) {'
if source.count(start_marker) != 1 or source.count(end_marker) != 1:
    raise SystemExit('Delete dialog changed; abort')
start = source.index(start_marker)
end = source.index(end_marker, start)
block = source[start:end]
block = replace_exact(block, start_marker,
    '        /** Delete Project is separate from Delete Chat, with one explicit destructive confirmation. */',
    'delete comment')
input_block = '''                    val input = EditText(activity).apply {
                        hint = "Type project name to confirm"
                        setSingleLine(true)
                        setPadding(dp(18), dp(14), dp(18), dp(14))
                    }
'''
block = replace_exact(block, input_block, '', 'remove name typing control')
block = replace_exact(block,
    '''                        .setMessage("This deletes '${project.name}', all its source files, task briefs, saved drafts, rollback data and chat history. This CANNOT be undone. Type the exact project name to continue. Delete Chat alone never deletes these files.")
                        .setView(input)
''',
    '''                        .setMessage("Delete '${project.name}' and ALL its source files, task briefs, saved drafts, rollback data and chat history? This cannot be undone. Delete Chat alone does not delete project files.")
''',
    'replace warning with single clear confirmation')
block = replace_exact(block,
    '''                        if (input.text.toString() != project.name) {
                            input.error = "Type the exact project name"
                            return@setOnClickListener
                        }
''', '', 'remove typed name comparison')
block = replace_exact(block,
    '''                            require(current.name == project.name && current.type != WorkspaceProjectType.CHAT) {
                                "Project changed; reopen the drawer"
                            }
''',
    '''                            require(current.projectId == id && current.name == project.name &&
                                current.type == project.type && current.type != WorkspaceProjectType.CHAT) {
                                "Project changed; reopen the drawer"
                            }
''',
    'retain identity and original project type')
block = replace_exact(block,
    '''                            check(store.deleteProject(id)) { "Project could not be deleted" }
''',
    '''                            // Stop any selected in-flight project work before removing its source.
                            onBeforeDeleteProject(id)
                            check(store.deleteProject(id)) { "Project could not be deleted" }
''',
    'cancel live coding before destructive delete')
if 'Type project name' in block or 'input.text' in block or '.setView(input)' in block:
    raise SystemExit('Delete Project still requires typing; abort')
source = source[:start] + block + source[end:]
drawer.write_text(source, encoding='utf-8')

source = activity.read_text(encoding='utf-8')
source = replace_exact(source,
    '''            onDeleteChat = { id -> confirmDeleteChat(id) }
''',
    '''            onDeleteChat = { id -> confirmDeleteChat(id) },
            onBeforeDeleteProject = { id ->
                if (selectedId == id) {
                    requestGeneration++
                    activeRequest?.cancel()
                    activeRequest = null
                    coding.cancel()
                    codingRetryTarget = null
                }
            }
''',
    'wire deletion to cancel selected project coding')
activity.write_text(source, encoding='utf-8')

source = route.read_text(encoding='utf-8')
source = replace_exact(source,
    '429 -> "OpenRouter returned HTTP 429: free route rate-limited. This does not prove your daily quota is exhausted.${retryAfterHint(retryAfter)} No paid fallback."',
    '429 -> "OpenRouter returned HTTP 429: free route rate-limited. This does not prove your daily quota is exhausted.${retryAfterHint(retryAfter)} Wait as suggested, or try later instead of repeatedly tapping Retry. No paid fallback."',
    'clarify real provider 429 versus daily quota')
route.write_text(source, encoding='utf-8')

source = result.read_text(encoding='utf-8')
source = replace_exact(source,
    '''    fun failure(reason: String): String = "LYRA coding request complete nahi kar paayi: $reason " +
        "Work → Files mein current project check karo. Agar Review edit/website dikhe " +
        "toh Undo ya Keep ke baad same instruction dobara bhej sakte ho. " +
        "No paid fallback."
''',
    '''    fun failure(reason: String): String = "LYRA coding request complete nahi kar paayi: $reason " +
        "Work → Files mein current project check karo. Agar Review edit/website dikhe " +
        "toh Undo ya Keep ke baad same instruction dobara bhej sakte ho." +
        if (reason.contains("No paid fallback", ignoreCase = true)) "" else " No paid fallback."
''',
    'remove duplicate no-paid-fallback warning')
result.write_text(source, encoding='utf-8')

# Regression guard: code outside Delete Project (e.g. Rename Chat) may still use EditText.
updated = drawer.read_text(encoding='utf-8')
part = updated[updated.index('fun showProjectActions('):updated.index('fun row(project:')]
assert 'Type project name' not in part and '.setView(input)' not in part
assert part.count('onBeforeDeleteProject(id)') == 1
assert 'current.projectId == id' in part and 'current.type == project.type' in part
assert 'setNegativeButton("Cancel", null)' in part and 'setPositiveButton("Delete Project", null)' in part
assert 'WorkspaceProjectStore(File(activity.filesDir, "workspace/projects"))' in part
assert 'transcript.isFile && transcript.delete()' in part
print('Verified: one-tap explicit Delete Project confirmation, scoped identity, project+chat cleanup and in-flight cancellation; 429 has no paid fallback.')
