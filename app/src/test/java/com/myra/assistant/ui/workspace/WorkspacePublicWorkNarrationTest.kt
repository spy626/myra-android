package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePublicWorkNarrationTest {
    private fun prepared(rationale: String) = WorkspaceGitHubSelfEditBatch.Prepared(
        files = listOf(
            WorkspaceGitHubWritePolicy.FileChange(
                "app/src/test/java/com/myra/assistant/screen/ReadingTrackerSafetyTest.kt",
                "class ReadingTrackerSafetyTest",
            )
        ),
        rationale = rationale,
    )

    @Test fun hinglishTaskGetsBrightSituationSpecificProgress() {
        val instruction =
            "Bro GitHub repo me ReadingTrackerSafetyTest.kt me safe comment-only change karo aur exact CI GREEN tak verify karo."
        val scope = WorkspacePublicWorkNarration.scope(
            instruction,
            listOf("app/src/test/java/com/myra/assistant/screen/ReadingTrackerSafetyTest.kt"),
        )
        val proposal = WorkspacePublicWorkNarration.proposal(
            instruction,
            prepared("Requested comment-only cleanup scoped to the selected test file."),
        )
        val commit = WorkspacePublicWorkNarration.committed(
            instruction,
            WorkspaceGitHubConnector.CommitReceipt(
                repository = "spy626/myra-android",
                branch = "agent/myra-phase-1",
                previousHead = "a".repeat(40),
                commitSha = "b".repeat(40),
                files = listOf("app/src/test/java/com/myra/assistant/screen/ReadingTrackerSafetyTest.kt"),
            ),
        )

        assertTrue(scope.text.contains("Scope bhi clear hai bro"))
        assertTrue(scope.text.contains("ReadingTrackerSafetyTest.kt"))
        assertTrue(proposal.text.contains("Proposal ready hai"))
        assertTrue(commit.text.contains("Push confirm ho gaya bro"))
        assertTrue(commit.text.contains("GREEN evidence se pehle task done nahi bolunga"))
    }

    @Test fun reviewDecisionChangesNarrationInsteadOfUsingOneFixedSentence() {
        val instruction = "Bro GitHub repo me safe fix karo."
        val accepted = WorkspacePublicWorkNarration.review(
            instruction,
            WorkspaceGitHubPatchReviewer.Review(
                WorkspaceGitHubPatchReviewer.Decision.ACCEPT,
                "Requested change is scoped correctly.",
                emptyList(),
            ),
            afterRevision = false,
        )
        val revise = WorkspacePublicWorkNarration.review(
            instruction,
            WorkspaceGitHubPatchReviewer.Review(
                WorkspaceGitHubPatchReviewer.Decision.REVISE,
                "Null guard is still missing.",
                listOf("Crash path remains."),
            ),
            afterRevision = false,
        )

        assertNotEquals(accepted.text, revise.text)
        assertTrue(accepted.text.contains("Reviewer ne change clear kiya"))
        assertTrue(revise.text.contains("fixable issue"))
        assertTrue(revise.text.contains("Null guard"))
    }

    @Test fun ciNarrationQualifiesEvidenceAndDoesNotClaimPhonePass() {
        val instruction = "Bro exact CI GREEN tak verify karo."
        val running = WorkspacePublicWorkNarration.ciRunning(instruction, 3314L, "in_progress")
        val passed = WorkspacePublicWorkNarration.ciPassed(instruction, 3314L)

        assertTrue(running.text.contains("isi commit"))
        assertTrue(passed.text.contains("configured build/tests"))
        assertFalse(passed.text.contains("phone-pass", ignoreCase = true))
        assertFalse(passed.text.contains("sab kuch verified", ignoreCase = true))
    }

    @Test fun providerNoiseInRationaleFallsBackToSafePublicText() {
        val instruction = "Update the GitHub repo safely."
        val update = WorkspacePublicWorkNarration.proposal(
            instruction,
            prepared("xKiro provider qwen produced this patch."),
        )

        assertFalse(update.text.contains("xKiro", ignoreCase = true))
        assertFalse(update.text.contains("qwen", ignoreCase = true))
        assertTrue(update.text.contains("ReadingTrackerSafetyTest.kt"))
    }
}
