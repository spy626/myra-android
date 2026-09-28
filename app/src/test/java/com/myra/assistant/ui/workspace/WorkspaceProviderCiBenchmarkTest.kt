package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProviderCiBenchmarkTest {
    @Test fun initialPromptCarriesRealCiEvidenceAndTwoStepContract() {
        val prompt = WorkspaceProviderCiBenchmark.initialPrompt(
            "GitHub Actions Build Android APK #42 failed; job=build; failed_steps=Unit tests and debug APK"
        )
        assertTrue(prompt.contains("TWO coordinated"))
        assertTrue(prompt.contains("REAL GitHub Actions failure"))
        assertTrue(prompt.contains("raw.distinct()"))
        assertTrue(prompt.contains("previewTags"))
        assertTrue(prompt.contains("-3"))
        assertTrue(prompt.contains("normalizeExpression"))
        assertTrue(prompt.contains("previewExpression"))
    }

    @Test fun retryPromptCarriesSameTaskPreviousSourceAndFailureEvidence() {
        val prompt = WorkspaceProviderCiBenchmark.retryPrompt(
            "GitHub Actions #44 failed; job=build; failed_steps=Unit tests and debug APK",
            WorkspaceProviderCiBenchmark.FAILURE_SOURCE,
        )
        assertTrue(prompt.contains("FIRST implementation"))
        assertTrue(prompt.contains("SAME task"))
        assertTrue(prompt.contains("YOUR PREVIOUS SYNTHETIC SOURCE"))
        assertTrue(prompt.contains("GitHub Actions #44"))
    }

    @Test fun validTwoExpressionReplyBuildsBoundedSource() {
        val result = WorkspaceProviderCiBenchmark.prepare(
            """{"normalizeExpression":"raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()","previewExpression":"normalizedTags(raw).take(limit.coerceAtLeast(0)).joinToString(\"|\")"}"""
        )
        assertTrue(result.source.contains("fun normalizedTags"))
        assertTrue(result.source.contains("fun previewTags"))
        assertTrue(result.source.contains("coerceAtLeast"))
        assertTrue(result.source.length < 6_000)
    }

    @Test fun fencedOrBriefProseWrappedJsonIsSafelyExtracted() {
        val fenced = WorkspaceProviderCiBenchmark.prepare(
            """```json
{"normalizeExpression":"raw.asSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted().toList()","previewExpression":"normalizedTags(raw).take(limit.coerceAtLeast(0)).joinToString(\"|\")"}
```"""
        )
        assertTrue(fenced.source.contains("toList"))

        val wrapped = WorkspaceProviderCiBenchmark.prepare(
            """Here is the bounded answer:
{"normalizeExpression":"raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().sorted()","previewExpression":"normalizedTags(raw).take(limit.coerceAtLeast(0)).joinToString(\"|\")"}
Done."""
        )
        assertTrue(wrapped.source.contains("toSet"))
    }

    @Test fun extraFieldsMissingCoordinationAndMultipleObjectsAreRejected() {
        val extra = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"normalizeExpression":"raw.sorted()","previewExpression":"normalizedTags(raw).joinToString(\"|\")","note":"x"}"""
            )
        }
        assertTrue(extra.isFailure)

        val uncoordinated = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"normalizeExpression":"raw.sorted()","previewExpression":"raw.take(limit.coerceAtLeast(0)).joinToString(\"|\")"}"""
            )
        }
        assertTrue(uncoordinated.isFailure)

        val multiple = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"normalizeExpression":"raw.sorted()","previewExpression":"normalizedTags(raw).take(limit).joinToString(\"|\")"} {"x":"y"}"""
            )
        }
        assertTrue(multiple.isFailure)
    }

    @Test fun dangerousOrNonWhitelistedCodeIsRejectedInEitherExpression() {
        val dangerous = listOf(
            """{"normalizeExpression":"System.getenv(\"HOME\")","previewExpression":"normalizedTags(raw).take(limit).joinToString(\"|\")"}""",
            """{"normalizeExpression":"raw.sorted()","previewExpression":"run { while (true) {} }"}""",
            """{"normalizeExpression":"raw.sorted()","previewExpression":"normalizedTags(raw).take(limit); Runtime.getRuntime()"}""",
        )
        dangerous.forEach { raw ->
            assertTrue(runCatching { WorkspaceProviderCiBenchmark.prepare(raw) }.isFailure)
        }
    }
}
