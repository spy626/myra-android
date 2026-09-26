#!/usr/bin/env python3
"""One-time exact-anchor provider-invariant website contract; no provider or memory change."""
from pathlib import Path
root = Path('app/src/main/java/com/myra/assistant/ui/workspace')
def swap(path, old, new):
    text = path.read_text(encoding='utf-8')
    assert text.count(old) == 1, f'expected one anchor {path}: {text.count(old)}'
    path.write_text(text.replace(old, new, 1), encoding='utf-8')
swap(root / 'WorkspaceWebsiteVisualQuality.kt',
    '        val files = action.files\n',
    '        // All providers must pass the same user-brief contract before any project write.\n'
    '        val files = WorkspaceWebsiteConsistency.verify(snapshot, action.files)\n')
swap(root / 'WorkspaceWebsiteVisualQuality.kt',
    'else -> "Preview loaded · automated layout checks clear; inspect appearance on phone."',
    'else -> "Preview loaded · basic layout checks clear; inspect appearance on phone. Button actions need a real tap test."')
swap(root / 'WorkspaceWebsiteGeneration.kt',
    '            "Plan an intentional mobile-first visual hierarchy: legible contrasting text, " +',
    '            "Treat each explicitly requested heading, named card and button behavior as acceptance criteria. " +\n'
    '            "On a new Minicoy tourism page without a specified theme, follow a coherent coastal " +\n'
    '            "palette (teal #087e93, sand #fff5e6, coral #fb923c), consistent typography and spacing. " +\n'
    '            "On existing projects preserve the current palette, typography, sections and working UI " +\n'
    '            "unless the user explicitly asks to change them; avoid unrelated full-page redesigns. " +\n'
    '            "Plan an intentional mobile-first visual hierarchy: legible contrasting text, " +')
print('Integrated provider-neutral acceptance, stable existing-project design prompt, honest Preview status')
