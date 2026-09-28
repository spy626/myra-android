package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProviderCiBenchmarkTest {
    @Test fun validExpressionBuildsBoundedSource() {
        val result = WorkspaceProviderCiBenchmark.prepare(
            """{"expression":"raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(\"|\")"}"""
        )
        assertTrue(result.source.contains("WorkspaceProviderCiTarget"))
        assertTrue(result.source.contains("joinToString"))
        assertTrue(result.source.length < 5_000)
    }

    @Test fun fencedOrBriefProseWrappedJsonIsSafelyExtracted() {
        val fenced = WorkspaceProviderCiBenchmark.prepare(
            """```json
{"expression":"raw.asSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(\"|\")"}
```"""
        )
        assertTrue(fenced.source.contains("joinToString"))

        val wrapped = WorkspaceProviderCiBenchmark.prepare(
            """Here is the bounded answer:
{"expression":"raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().sorted().joinToString(\"|\")"}
Done."""
        )
        assertTrue(wrapped.source.contains("toSet"))
    }

    @Test fun extraFieldsAndMultipleObjectsAreRejected() {
        val extra = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"expression":"raw.joinToString(\"|\")","note":"x"}"""
            )
        }
        assertTrue(extra.isFailure)

        val multiple = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"expression":"raw.joinToString(\"|\")"} {"expression":"raw.joinToString(\"|\")"}"""
            )
        }
        assertTrue(multiple.isFailure)
    }

    @Test fun dangerousOrNonWhitelistedCodeIsRejected() {
        val dangerous = listOf(
            """{"expression":"System.getenv(\"HOME\")"}""",
            """{"expression":"run { while (true) {} }"}""",
            """{"expression":"raw.joinToString(\"|\"); Runtime.getRuntime()"}""",
            """{"expression":"okhttp3.OkHttpClient.Builder().build().toString()"}""",
        )
        dangerous.forEach { raw ->
            assertTrue(runCatching { WorkspaceProviderCiBenchmark.prepare(raw) }.isFailure)
        }
    }
}
