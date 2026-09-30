package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAdaptiveFinalAnswerTest {
    private fun success(adaptive: String? = null) = WorkspaceGitHubSelfEditFlow.Completion(
        commit = WorkspaceGitHubConnector.CommitReceipt(
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            previousHead = "a".repeat(40),
            commitSha = "b".repeat(40),
            files = listOf("app/src/main/java/com/myra/assistant/ui/workspace/A.kt"),
        ),
        workflow = WorkspaceGitHubConnector.WorkflowRun(
            id = 99L,
            runNumber = 3280L,
            name = "Build Android APK",
            headSha = "b".repeat(40),
            status = "completed",
            conclusion = "success",
            url = "https://example.invalid/run/99",
        ),
        pullRequest = WorkspaceGitHubConnector.PullRequestReceipt(
            action = "updated",
            number = 7,
            url = "https://example.invalid/pr/7",
            draft = true,
            head = "agent/myra-phase-1",
            base = "main",
        ),
        adaptiveAnswer = adaptive,
    )

    @Test fun committedDiffEvidenceUsesActualBeforeAndAfterWindows() {
        val prepared = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange(
                    "app/src/main/java/com/myra/assistant/ui/workspace/A.kt",
                    "class A {\n    fun value() = 2\n}\n",
                )
            ),
            rationale = "Changed the returned value requested by the user.",
        )
        val evidence = WorkspaceAdaptiveFinalAnswer.changeEvidence(
            mapOf(
                "app/src/main/java/com/myra/assistant/ui/workspace/A.kt" to
                    "class A {\n    fun value() = 1\n}\n"
            ),
            prepared,
        )

        assertTrue(evidence.contains("fun value() = 1"))
        assertTrue(evidence.contains("fun value() = 2"))
        assertTrue(evidence.contains("CONTEXT ONLY, NOT INDEPENDENT PROOF"))
    }

    @Test fun promptRequestsSituationSpecificAnswerInsteadOfFixedReceipt() {
        val result = success()
        val prompt = WorkspaceAdaptiveFinalAnswer.prompt(
            userTask = "Bro is bug ko fix karo aur exact CI GREEN tak verify karo.",
            changeEvidence = "FILE: \"A.kt\"\nBEFORE: old\nAFTER: new",
            result = result,
        )

        assertTrue(prompt.contains("Do NOT use a fixed receipt/template"))
        assertTrue(prompt.contains("Lead with what the user cares about"))
        assertTrue(prompt.contains("CI GREEN proves only"))
        assertTrue(prompt.contains("Bro is bug ko fix karo"))
        assertTrue(prompt.contains("Exact CI: #3280"))
        assertTrue(prompt.contains("prefer 2–4 short natural sentences"))
        assertTrue(prompt.contains("answer in natural Hinglish"))
        assertTrue(prompt.contains("instead of pasting English coder/reviewer wording verbatim"))
        assertTrue(prompt.contains("**Done bro ✅**"))
        assertTrue(prompt.contains("avoid headings and receipt-like labels"))
        assertTrue(prompt.contains("protected feature-branch write"))
        assertTrue(prompt.contains("Normally omit commit SHA, draft PR number and raw branch identifier"))
        assertTrue(prompt.contains("Never say everything or sab kuch is verified"))
        assertFalse(prompt.contains("agent/myra-phase-1"))
        assertFalse(prompt.contains("b".repeat(40)))
        assertFalse(prompt.contains("What changed:"))
    }

    @Test fun adaptiveAnswerAcceptsNaturalGroundedResult() {
        val result = success()
        val raw = "Bro ✅ requested fix complete ho gaya. Exact CI #3280 GREEN hai. " +
            "Ab next useful step changed behavior ko phone par test karna hai."

        assertEquals(raw, WorkspaceAdaptiveFinalAnswer.accept(raw, result))
        assertEquals(raw, WorkspaceFinalAnswer.githubSuccess(result.copy(adaptiveAnswer = raw)))
    }

    @Test fun adaptiveAnswerCollapsesImmediateRepeatedOpening() {
        val result = success()
        val raw = "Bro, kaam hoBro, kaam ho gaya! Exact CI #3280 GREEN hai."

        val accepted = WorkspaceAdaptiveFinalAnswer.accept(raw, result)

        assertEquals("Bro, kaam ho gaya! Exact CI #3280 GREEN hai.", accepted)
    }

    @Test fun adaptiveAnswerPreservesParagraphBreaks() {
        val result = success()
        val raw = "Bro ✅ requested fix complete ho gaya.\n\nExact CI #3280 GREEN hai."

        val accepted = WorkspaceAdaptiveFinalAnswer.accept(raw, result)

        assertEquals(raw, accepted)
    }

    @Test fun adaptiveAnswerRejectsInventedVerificationFacts() {
        val result = success()

        assertTrue(runCatching {
            WorkspaceAdaptiveFinalAnswer.accept(
                "Bro phone-passed ✅ aur CI #3280 GREEN.", result)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAdaptiveFinalAnswer.accept(
                "Bro task complete, CI #9999 GREEN.", result)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAdaptiveFinalAnswer.accept(
                "Commit ccccccc complete hai.", result)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAdaptiveFinalAnswer.accept(
                "Main branch updated and task complete.", result)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAdaptiveFinalAnswer.accept(
                "Bro agent/myra-phase-1 par change complete hai. CI #3280 GREEN.", result)
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceAdaptiveFinalAnswer.accept(
                "Bro, sab kuch verified hai. CI #3280 GREEN.", result)
        }.isFailure)

        val qualified = "Bro requested change complete hai. CI #3280 ke configured build/tests passed."
        assertEquals(qualified, WorkspaceAdaptiveFinalAnswer.accept(qualified, result))
    }
}
