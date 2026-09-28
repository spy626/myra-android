package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProviderCiBenchmarkTest {
    @Test fun finalistPromptCarriesTwoFilesRealCiAndGeneralContract() {
        val prompt = WorkspaceProviderCiBenchmark.initialPrompt(
            "GitHub Actions Build Android APK #42 failed; job=build; failed_steps=Unit tests and debug APK"
        )
        assertTrue(prompt.contains("SMALL TWO-FILE"))
        assertTrue(prompt.contains("REAL GitHub Actions failure"))
        assertTrue(prompt.contains("FAILED FILE A"))
        assertTrue(prompt.contains("FAILED FILE B"))
        assertTrue(prompt.contains("deduplicate AFTER normalization"))
        assertTrue(prompt.contains("Some edge-case acceptance tests are intentionally not enumerated"))
        assertTrue(prompt.contains("countExpression"))
    }

    @Test fun retryPromptKeepsSameTaskAndPreviousTwoFileImplementation() {
        val previous = WorkspaceProviderCiBenchmark.BASELINE_RULES_SOURCE + "\n" +
            WorkspaceProviderCiBenchmark.BASELINE_TARGET_SOURCE
        val prompt = WorkspaceProviderCiBenchmark.retryPrompt(
            "GitHub Actions #44 failed; failed_steps=Unit tests and debug APK",
            previous,
        )
        assertTrue(prompt.contains("SAME two-file task"))
        assertTrue(prompt.contains("YOUR PREVIOUS TWO-FILE IMPLEMENTATION"))
        assertTrue(prompt.contains("ALL THREE functions"))
    }

    @Test fun validThreeExpressionReplyBuildsTwoBoundedFiles() {
        val prepared = WorkspaceProviderCiBenchmark.prepare(
            """{"normalizeExpression":"raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()","previewExpression":"WorkspaceProviderCiRules.normalize(raw).take(limit.coerceAtLeast(0)).joinToString(\"|\")","countExpression":"WorkspaceProviderCiRules.normalize(raw).size"}"""
        )
        val files = WorkspaceProviderCiBenchmark.providerFiles(prepared)
        assertEquals(2, files.size)
        assertEquals(WorkspaceProviderCiBenchmark.TARGET_PATHS, files.map { it.path })
        assertTrue(prepared.rulesSource.contains("fun normalize"))
        assertTrue(prepared.targetSource.contains("fun preview"))
        assertTrue(prepared.targetSource.contains("fun count"))
    }

    @Test fun equivalentPureKotlinPreviewAndCountFormsAreAccepted() {
        val prepared = WorkspaceProviderCiBenchmark.prepare(
            """{"normalizeExpression":"raw.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().sorted()","previewExpression":"if (limit <= 0) \"\" else WorkspaceProviderCiRules.normalize(raw).take(limit).joinToString(\"|\")","countExpression":"WorkspaceProviderCiRules.normalize(raw).count()"}"""
        )
        assertTrue(prepared.targetSource.contains("if (limit <= 0)"))
        assertTrue(prepared.targetSource.contains(".count()"))
    }

    @Test fun rejectionReportsOnlyUnknownIdentifierNames() {
        val error = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"normalizeExpression":"raw.sorted()","previewExpression":"mystery(WorkspaceProviderCiRules.normalize(raw), limit)","countExpression":"WorkspaceProviderCiRules.normalize(raw).size"}"""
            )
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("mystery"))
    }

    @Test fun crossFileCoordinationIsMandatory() {
        val badPreview = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"normalizeExpression":"raw.sorted()","previewExpression":"raw.take(limit.coerceAtLeast(0)).joinToString(\"|\")","countExpression":"WorkspaceProviderCiRules.normalize(raw).size"}"""
            )
        }
        assertTrue(badPreview.isFailure)

        val badCount = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"normalizeExpression":"raw.sorted()","previewExpression":"WorkspaceProviderCiRules.normalize(raw).take(limit.coerceAtLeast(0)).joinToString(\"|\")","countExpression":"raw.size"}"""
            )
        }
        assertTrue(badCount.isFailure)
    }

    @Test fun dangerousCodeIsRejected() {
        val bad = runCatching {
            WorkspaceProviderCiBenchmark.prepare(
                """{"normalizeExpression":"System.getenv(\"HOME\")","previewExpression":"WorkspaceProviderCiRules.normalize(raw).take(limit).joinToString(\"|\")","countExpression":"WorkspaceProviderCiRules.normalize(raw).size"}"""
            )
        }
        assertTrue(bad.isFailure)
    }
}
