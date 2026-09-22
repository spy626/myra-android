#!/usr/bin/env python3
"""One-shot, anchored fail-closed correction for Cloudflare website SSE framing."""
from pathlib import Path

source = Path('app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceCloudflareFree.kt')
tests = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceCloudflareModelsWebsiteTest.kt')
text = source.read_text(encoding='utf-8')
old = '''    private const val MAX_BYTES = 130_000L
    private val ACCOUNT = Regex("^[a-fA-F0-9]{32}$")'''
new = '''    private const val MAX_BYTES = 130_000L // Synchronous JSON envelope only.
    // Website SSE adds per-token JSON/framing overhead; these are transport limits,
    // separate from the unchanged 30,000-character complete website JSON limit.
    private const val MAX_WEBSITE_STREAM_BYTES = 2_000_000L
    private const val MAX_WEBSITE_EVENT_LINE_BYTES = 65_536L
    private val ACCOUNT = Regex("^[a-fA-F0-9]{32}$")'''
assert text.count(old) == 1, 'Synchronous/SSE limit anchor changed'
text = text.replace(old, new)
old = '''            var totalChars = 0
            var done = false
            var sawChoice = false
            var completed = false
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    totalChars += line.length
                    require(totalChars <= MAX_BYTES) { "Cloudflare website stream oversized; no files changed" }
                    if (!line.startsWith("data:")) continue'''
new = '''            var transportBytes = 0L
            var done = false
            var sawChoice = false
            var completed = false
            try {
                while (true) {
                    // readUtf8Line() can buffer an unbounded single line. Strict per-line
                    // reads bound memory; EOF without the required delimiter fails closed.
                    if (source.exhausted()) break
                    val line = try {
                        source.readUtf8LineStrict(MAX_WEBSITE_EVENT_LINE_BYTES)
                    } catch (e: java.io.EOFException) {
                        throw IllegalArgumentException(
                            "Cloudflare website stream event oversized or unterminated; no files changed")
                    }
                    // Account for UTF-8 bytes and CRLF (conservatively two terminator bytes).
                    // SSE protocol/usage overhead is not generated file content.
                    transportBytes += line.toByteArray(Charsets.UTF_8).size.toLong() + 2L
                    require(transportBytes <= MAX_WEBSITE_STREAM_BYTES) {
                        "Cloudflare website stream transport limit reached; no files changed"
                    }
                    if (!line.startsWith("data:")) continue'''
assert text.count(old) == 1, 'SSE reader anchor changed'
text = text.replace(old, new)
source.write_text(text, encoding='utf-8')

text = tests.read_text(encoding='utf-8')
anchor = '''    @Test fun synchronousWebsiteRepliesRemainSupportedWithoutStreamHeader() {'''
assert text.count(anchor) == 1, 'Test insertion anchor changed'
regressions = '''    @Test fun validWebsiteSurvivesLargeSseProtocolEnvelopeWithoutRelaxingOutputLimit() {
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        // Real streams repeat envelope metadata for many tokens. Over 130 KB of
        // protocol framing must not reject a small, complete three-file result.
        val metadata = buildString {
            repeat(200) { append(": ").append("m".repeat(1_000)).append('\\n') }
        }
        val stream = metadata + chunk(files()) + end
        assertTrue(stream.toByteArray(Charsets.UTF_8).size > 130_000)
        val generated = WorkspaceCloudflareFree.readWebsite(response(req, stream))
        assertEquals(WorkspaceWebsiteGeneration.PATHS.toSet(), generated.keys)
        assertTrue(generated.getValue("index.html").contains("style.css"))
    }

    @Test fun oversizedSingleEventAndTotalTransportStillFailClosed() {
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        val longLine = ":" + "x".repeat(70_000) + "\\n" + chunk(files()) + end
        val lineFailure = runCatching {
            WorkspaceCloudflareFree.readWebsite(response(req, longLine))
        }.exceptionOrNull()
        assertNotNull(lineFailure)
        assertTrue(lineFailure!!.message.orEmpty().contains("oversized or unterminated"))

        val excessTransport = buildString {
            repeat(2_100) { append(": ").append("t".repeat(1_000)).append('\\n') }
            append(chunk(files())).append(end)
        }
        val transportFailure = runCatching {
            WorkspaceCloudflareFree.readWebsite(response(req, excessTransport))
        }.exceptionOrNull()
        assertNotNull(transportFailure)
        assertTrue(transportFailure!!.message.orEmpty().contains("transport limit reached"))
    }

    @Test fun actualWebsiteTextRemainsLimitedToThirtyThousandCharacters() {
        val req = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        val oversizedText = chunk("x".repeat(30_001)) + end
        val failure = runCatching {
            WorkspaceCloudflareFree.readWebsite(response(req, oversizedText))
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("output oversized"))
    }

'''
text = text.replace(anchor, regressions + anchor)
tests.write_text(text, encoding='utf-8')
print('Patched bounded per-line and total-byte Cloudflare SSE transport; retained 30K output cap.')
print('Added >130KB valid-envelope, oversized-line, oversized-transport and visible-output regressions.')
