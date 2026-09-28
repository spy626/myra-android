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

    @Test fun proseAndExtraFieldsAreRejected() {
        val prose = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """Here: {"expression":"raw.joinToString(\"|\")"}"""
            )
        }
        assertTrue(prose.isFailure)

        val extra = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"expression":"raw.joinToString(\"|\")","note":"x"}"""
            )
        }
        assertTrue(extra.isFailure)
    }

    @Test fun dangerousOrDeclarationCodeIsRejected() {
        val dangerous = listOf(
            """{"expression":"System.getenv(\"HOME\")"}""",
            """{"expression":"run { while (true) {} }"}""",
            """{"expression":"raw.joinToString(\"|\"); Runtime.getRuntime()"}""",
        )
        dangerous.forEach { raw ->
            assertTrue(runCatching { WorkspaceProviderCiBenchmark.prepare(raw) }.isFailure)
        }
    }
}
