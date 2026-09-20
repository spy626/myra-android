#!/usr/bin/env python3
"""Apply after workspace_website_400_click_feedback_once.py; preserves prior safety test."""
from pathlib import Path
p = Path('app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGeneration.kt')
s = p.read_text()
old = '''                400 -> "Groq Free HTTP 400 (" +
                    WorkspaceWebsiteProviderError.category(result.peekBody(8_193L).string()) +
                    "): request rejected. No further retry or paid fallback; project files unchanged."
'''
new = '''                400 -> "Groq Free HTTP 400: request or output format rejected (" +
                    WorkspaceWebsiteProviderError.category(result.body?.let {
                        result.peekBody(8_193L).string()
                    }.orEmpty()) +
                    "); not a quota or billing signal. No further retry or paid fallback; project files unchanged."
'''
assert s.count(old) == 1, 'Unexpected Groq HTTP 400 source revision'
p.write_text(s.replace(old, new, 1))
print('Reconciled legacy HTTP400 regression: never read absent body, keep no-quota explanation')
