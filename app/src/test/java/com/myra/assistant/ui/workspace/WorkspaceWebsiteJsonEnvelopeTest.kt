package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteJsonEnvelopeTest {
    private val html = "<!DOCTYPE html><html><head><link href=\"style.css\" rel=\"stylesheet\"></head>" +
        "<body><h1>Minicoy</h1><script src=\"script.js\"></script></body></html>"
    private fun envelope() = JSONObject().put("files", JSONObject()
        .put("index.html", html).put("style.css", "body { color: blue; }")
        .put("script.js", "console.log('hello');")).toString()

    @Test fun crlfJsonFenceAndShortLeadingTextCanRecoverWithoutAnotherRequest() {
        val valid = envelope()
        assertEquals(html, WorkspaceWebsiteGeneration.parse("```JSON\r\n$valid\r\n```").getValue("index.html"))
        assertEquals(html, WorkspaceWebsiteGeneration.parse("Here are the requested files:\n$valid")
            .getValue("index.html"))
        assertEquals(html, WorkspaceWebsiteGeneration.parse("\uFEFF$valid").getValue("index.html"))
    }

    @Test fun boundedMarkdownWrappersAroundCompleteOutputsRecoverWithoutGuessing() {
        val valid = envelope()
        val wrappedJson = "Here is the complete website:\n```json\n$valid\n```\nDone."
        assertEquals(html, WorkspaceWebsiteGeneration.parse(wrappedJson).getValue("index.html"))

        val labelled = "Here are the complete files:\n\n### **index.html**\n```html\n$html\n```" +
            "\n### **style.css**\n```css\nbody { color: blue; }\n```" +
            "\n### **script.js**\n```javascript\nconsole.log('hello');\n```\nAll three files are complete."
        val parsed = WorkspaceWebsiteGeneration.parse(labelled)
        assertEquals(html, parsed.getValue("index.html"))
        assertEquals("body { color: blue; }", parsed.getValue("style.css"))
        assertEquals("console.log('hello');", parsed.getValue("script.js"))

        listOf(
            labelled + "\n```txt\nextra\n```",
            labelled + "\n{\\\"extra\\\":true}",
            labelled + "\n" + labelled
        ).forEach { raw -> assertTrue("Ambiguous extra output must still be refused", runCatching {
            WorkspaceWebsiteGeneration.parse(raw)
        }.isFailure) }
    }
    @Test fun literalNewlinesInsideCompleteJsonStringsAreEscapedWithoutChangingFileText() {
        val multiline = html.replace("<body>", "<body>\n")
        val valid = JSONObject().put("files", JSONObject()
            .put("index.html", multiline).put("style.css", "body {}")
            .put("script.js", "")).toString()
        val malformedButComplete = valid.replace("<body>\\n", "<body>\n")
        assertEquals(multiline, WorkspaceWebsiteGeneration.parse(malformedButComplete).getValue("index.html"))
    }

    @Test fun incompleteAmbiguousAndWrongSchemaOutputNeverBecomesFiles() {
        val valid = envelope()
        val cases = listOf(
            valid.dropLast(1),
            "<html>Only HTML</html>",
            "First: $valid Second: $valid",
            valid + " trailing",
            JSONObject().put("files", JSONObject().put("index.html", html)).toString(),
            JSONObject().put("files", JSONObject().put("index.html", html)
                .put("style.css", "").put("script.js", "").put("secret.txt", "bad")).toString(),
            valid + " {\"extra\":true}"
        )
        cases.forEach { raw -> assertTrue("Malformed result should be refused", runCatching {
            WorkspaceWebsiteGeneration.parse(raw)
        }.isFailure) }
    }
}
