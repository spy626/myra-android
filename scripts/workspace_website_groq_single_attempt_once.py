#!/usr/bin/env python3
from pathlib import Path
p = Path('app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceChatCodingFlow.kt')
s = p.read_text()
a = 'val second = WorkspaceWebsiteGeneration.client.newCall(secondRequest)'
b = 'val second = WorkspaceWebsiteGroqFallback.client.newCall(secondRequest)'
assert s.count(a) == 1, 'Expected one website Groq fallback call site'
p.write_text(s.replace(a, b, 1))
p = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGroqFallbackTest.kt')
s = p.read_text()
a = '    @Test fun invalidKeyNeverBuildsGroqRequest() {'
b = '''    @Test fun GroqFallbackClientCannotRetryOrResendAfter429() {
        assertTrue(WorkspaceWebsiteGroqFallback.client.interceptors.none { it is WorkspaceFreeRouteRetry })
        assertFalse(WorkspaceWebsiteGroqFallback.client.retryOnConnectionFailure)
    }

    @Test fun invalidKeyNeverBuildsGroqRequest() {'''
assert s.count(a) == 1, 'Expected one Groq fallback regression test anchor'
p.write_text(s.replace(a, b, 1))
print('Groq website HTTP attempt is now exactly once (no retry interceptor).')
