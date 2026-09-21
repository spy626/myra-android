package com.myra.assistant.ui.workspace

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Regression for the coffee phone's invalid_json_syntax: do not change HTML/JS by
 * guessing invalid JSON delimiters; request a format that does not JSON-escape code.
 * Both unrelated goals MUST use the same transport, parser and protection contract.
 */
class WorkspaceWebsiteGenericOutputContractTest {
    private val empty = mapOf("index.html" to null, "style.css" to null, "script.js" to null)
    private val cafe = WorkspaceWebsiteGeneration.Snapshot("cafe", "task", "approved",
        "Build a mobile cafe menu with a View Menu link and three food cards", empty)
    private val counter = WorkspaceWebsiteGeneration.Snapshot("counter", "task", "approved",
        "Build a counter with plus, minus and Reset buttons implemented in JavaScript", empty)

    private fun json(request: Request): JSONObject {
        val bytes = Buffer()
        requireNotNull(request.body).writeTo(bytes)
        return JSONObject(bytes.readUtf8())
    }

    @Test fun unrelatedFreshGoalsGetSameBoundedNonJsonOutputContractAndTheirOwnContext() {
        for (snapshot in listOf(cafe, counter)) {
            val body = json(WorkspaceWebsiteGroqFallback.request("gsk_test_key", snapshot))
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            val context = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(body.has("response_format"))
            assertTrue(system.contains("Return exactly THREE complete files as consecutive labelled code blocks"))
            assertFalse(system.contains("Return exactly ONE JSON object"))
            assertTrue(system.contains("script.js then a fenced javascript block"))
            assertEquals(snapshot.goal, context.getString("goal"))
            assertEquals(WorkspaceWebsiteGeneration.PATHS.toSet(),
                context.getJSONObject("existingFiles").keys().asSequence().toSet())
            assertTrue((0 until WorkspaceWebsiteGeneration.PATHS.size).all { index ->
                context.getJSONObject("existingFiles").isNull(WorkspaceWebsiteGeneration.PATHS[index])
            })
        }
    }

    @Test fun labelledCompleteHtmlCssAndJavaScriptAreStillStrictlyValidated() {
        val html = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
            "<body><button id=\"plus\">+</button><script src=\"script.js\"></script></body></html>"
        val css = "body { color: #fff; background: rgb(10, 20, 30); }"
        val js = "const button = document.getElementById(\"plus\");\n" +
            "button.addEventListener(\"click\", () => console.log('plus'));"
        val response = "index.html\n```html\n$html\n```\nstyle.css\n```css\n$css\n```" +
            "\nscript.js\n```javascript\n$js\n```"
        val parsed = WorkspaceWebsiteGeneration.parse(response)
        assertEquals(html, parsed.getValue("index.html"))
        assertEquals(css, parsed.getValue("style.css"))
        assertEquals(js, parsed.getValue("script.js"))
        assertEquals(WorkspaceWebsiteGeneration.PATHS.toSet(), parsed.keys)
        listOf(response.dropLast(3), response + "\nMore output", response + "\n" + response,
            response.replace("script.js\n```javascript", "api_key.txt\n```javascript"))
            .forEach { assertTrue(runCatching { WorkspaceWebsiteGeneration.parse(it) }.isFailure) }
    }

    @Test fun definiteHttp400CompatibilityRestoresJsonInstructionsWithoutMixedFormats() {
        for (snapshot in listOf(cafe, counter)) {
            val body = json(WorkspaceWebsiteGroqFallback.compatibilityRequest("gsk_test_key", snapshot))
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            assertTrue(system.contains("Return exactly ONE JSON object"))
            assertFalse(system.contains("Return exactly THREE complete files"))
            assertEquals(snapshot.goal,
                JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
                    .getString("goal"))
        }
    }

    @Test fun establishedProjectsKeepOriginalStructuredSchemaContract() {
        val existing = cafe.copy(original = mapOf("index.html" to "<html>existing</html>",
            "style.css" to "body{}", "script.js" to "console.log('keep')"))
        val body = json(WorkspaceWebsiteGroqFallback.request("gsk_test_key", existing))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertFalse(body.getJSONArray("messages").getJSONObject(0).getString("content")
            .contains("Return exactly THREE complete files"))
    }
}
