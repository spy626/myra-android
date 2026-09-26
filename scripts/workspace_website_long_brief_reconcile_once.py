#!/usr/bin/env python3
"""One-time reconciliation of long-brief implementation with all existing safety tests."""
from pathlib import Path
import runpy

# Stage the original exact-anchor change in this isolated CI checkout, not locally.
runpy.run_path('scripts/workspace_website_long_brief_once.py', run_name='__main__')
BASE = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace')

def replace_once(path, old, new):
    content = path.read_text(encoding='utf-8')
    assert content.count(old) == 1, f'{path.name}: expected exactly one existing anchor; got {content.count(old)}'
    path.write_text(content.replace(old, new, 1), encoding='utf-8')

# Preserve the historical multiple-spaces normalization while retaining all
# words and requested paragraph breaks in a website specification.
replace_once(BASE / 'WorkspaceTaskContract.kt',
    r'''        val clean = raw.replace("\r\n", "\n").replace('\r', '\n').trim()''',
    r'''        val clean = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            .replace(Regex("[ \t]+"), " ")''')

# The legacy one-file handoff must recognize an initial, multiline WEBSITE
# goal as the SAME saved goal. Keep distinct 180-char follow-ups restricted.
replace_once(BASE / 'WorkspaceAiHandoff.kt',
    '''            WorkspaceTaskContract.normalizeGoal(rawFollowUp) == saved.goal''',
    '''            WorkspaceTaskContract.normalizeGoal(rawFollowUp,
                projects.getProject(projectId)?.type ?: WorkspaceProjectType.CHAT) == saved.goal''')

# The free provider now accepts larger approved contexts but must still reject
# an actual oversize request, rather than preserving an obsolete 12k threshold.
replace_once(TEST / 'WorkspaceWebsiteGroqFallbackTest.kt',
    '''        val huge = sample("a".repeat(12_000))''',
    '''        val huge = sample("a".repeat(WorkspaceLongInputPolicy.MAX_REQUEST_CHARS))''')

print('Reconciled normalized website paragraphs, legacy initial-goal handoff, and bounded provider overflow guard.')
