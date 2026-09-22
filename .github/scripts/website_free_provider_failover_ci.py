from pathlib import Path

root = Path('app/src')
source = root / 'main/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGeneration.kt'
test = root / 'test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGenerationTest.kt'
s = source.read_text()
old = '.put("allow_fallbacks", false)'
assert s.count(old) == 1, 'Unexpected provider policy; abort'
assert s.count('.put("model", WorkspaceFreeAiSuggestion.MODEL)') == 1
assert s.count('.put("zdr", true).put("data_collection", "deny")') == 1
assert s.count('.put("max_price", JSONObject().put("prompt", 0)') == 1
s = s.replace(old, '// OpenRouter can switch only among zero-price, ZDR-compliant providers.\n                .put("allow_fallbacks", true)', 1)
source.write_text(s)
t = test.read_text()
old_assert = 'assertEquals(false, body.getJSONObject("provider").getBoolean("allow_fallbacks"))'
assert t.count(old_assert) == 1, 'Expected free-route contract test missing'
new_assert = '''// Provider failover is confined by the free router and every zero-price/privacy gate.
        assertTrue(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        val maximumPrice = body.getJSONObject("provider").getJSONObject("max_price")
        listOf("prompt", "completion", "request", "image").forEach {
            assertEquals("$it must remain free", 0, maximumPrice.getInt(it))
        }
        assertFalse(body.has("models"))'''
t = t.replace(old_assert, new_assert, 1)
test.write_text(t)
print('Only website free-provider failover and its safety assertions changed')
