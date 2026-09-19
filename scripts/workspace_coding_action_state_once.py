#!/usr/bin/env python3
"""Apply a single, exact-source, branch-only Chat action visibility correction."""
from pathlib import Path

p = Path('app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt')
source = p.read_text(encoding='utf-8')
old = '''            if (websitePending != null) addControl("Review website · Undo / Keep") {
                coding.reviewPending(id)
            }
            else if (pending != null) addControl("Review edit · Undo / Keep") { coding.reviewPending(id) }
            else {
                val saved = runCatching { suggestions.recover(files, tasks, projects, id) }.getOrNull()
                if (saved is WorkspaceAiSuggestionDraftStore.Recovery.Ready)
                    addControl("Review saved code change") { coding.reviewSaved(id) }
                val lastInstruction = messages.lastOrNull { it.role == "user" }?.text.orEmpty()
                if (lastInstruction.isNotBlank()) addControl("Continue coding request") {
                    coding.continueRequest(id, lastInstruction)
                }
            }
'''
new = '''            val savedProposal = if (websitePending == null && pending == null)
                runCatching { suggestions.recover(files, tasks, projects, id) }.getOrNull() is
                    WorkspaceAiSuggestionDraftStore.Recovery.Ready
            else false
            // Never treat a saved instruction as a completed or resumable file edit.
            // A new instruction is sent through the chat composer; review requires a real backup.
            when (WorkspaceCodingActionPolicy.next(websitePending != null, pending != null, savedProposal)) {
                WorkspaceCodingActionPolicy.Action.REVIEW_WEBSITE ->
                    addControl("Review website · Undo / Keep") { coding.reviewPending(id) }
                WorkspaceCodingActionPolicy.Action.REVIEW_EDIT ->
                    addControl("Review edit · Undo / Keep") { coding.reviewPending(id) }
                WorkspaceCodingActionPolicy.Action.REVIEW_SAVED_PROPOSAL ->
                    addControl("Review saved code change") { coding.reviewSaved(id) }
                WorkspaceCodingActionPolicy.Action.NONE -> Unit
            }
'''
if source.count(old) != 1:
    raise SystemExit('Unexpected Chat action source; abort without modifying an unknown revision')
updated = source.replace(old, new)
assert 'addControl("Continue coding request")' not in updated
assert updated.count('WorkspaceCodingActionPolicy.next(') == 1
p.write_text(updated, encoding='utf-8')
print('Applied Chat action state fix: no stale Continue; review derives from pending records')
