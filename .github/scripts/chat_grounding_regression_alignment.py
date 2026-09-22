#!/usr/bin/env python3
"""Align only exact old message-index assertions; retain independent route/privacy checks."""
from pathlib import Path
root = Path('app/src/test/java/com/myra/assistant/ui/workspace')
def change(name, old, new):
    file = root / name
    text = file.read_text(encoding='utf-8')
    assert text.count(old) == 1, f'Expected one exact match in {name}: {old!r}'
    file.write_text(text.replace(old, new, 1), encoding='utf-8')
change('WorkspaceCloudflareFreeTest.kt',
       'assertEquals("Say hello", json.getJSONArray("messages").getJSONObject(0).getString("content"))',
       'assertEquals("Say hello", json.getJSONArray("messages").getJSONObject(json.getJSONArray("messages").length() - 1).getString("content"))')
change('WorkspaceContextProjectionTest.kt',
       'assertEquals(1, JSONObject(WorkspaceChatGateway.openRouterBody(listOf(user("Hi"))))\n            .getJSONArray("messages").length())',
       'assertEquals(2, JSONObject(WorkspaceChatGateway.openRouterBody(listOf(user("Hi"))))\n            .getJSONArray("messages").length())')
change('WorkspaceGroqFreeTest.kt',
       'assertEquals(original, payload.getJSONArray("messages").getJSONObject(0).getString("content"))',
       'assertEquals(original, payload.getJSONArray("messages").getJSONObject(payload.getJSONArray("messages").length() - 1).getString("content"))')
change('WorkspacePromptContextTest.kt',
       'assertEquals("user", entries(listOf(user("Hi bro"))).getJSONObject(0).getString("role"))',
       'assertEquals("user", entries(listOf(user("Hi bro"))).getJSONObject(1).getString("role"))')
change('WorkspacePromptFollowUpTest.kt',
       'assertEquals("user", sent.getJSONObject(0).getString("role"))',
       'assertEquals("system", sent.getJSONObject(0).getString("role"))\n        assertEquals("user", sent.getJSONObject(1).getString("role"))')
change('WorkspacePromptWritingTest.kt',
       'assertEquals(1, sent("Hi bro, kese ho").length())',
       'assertEquals(2, sent("Hi bro, kese ho").length())')
change('WorkspaceStoryScriptTest.kt',
       'assertEquals(1, plain.length())\n        assertEquals("user", plain.getJSONObject(0).getString("role"))',
       'assertEquals(2, plain.length())\n        assertEquals("system", plain.getJSONObject(0).getString("role"))\n        assertEquals("user", plain.getJSONObject(1).getString("role"))')
print('ALIGNED: seven legacy tests retain provider, privacy, and original-user assertions')
