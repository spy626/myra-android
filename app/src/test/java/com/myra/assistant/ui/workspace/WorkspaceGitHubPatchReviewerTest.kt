package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitHubPatchReviewerTest {
    private val plan = WorkspaceGitHubCodingPlan.create(
        goal = "GitHub checkout state fix karo",
        repository = "spy626/myra-android",
        branch = "agent/myra-phase-1",
        selectedPaths = listOf("src/A.kt", "src/B.kt"),
    )

    @Test fun reviewPromptIsReadOnlyAndBoundedToLockedScope() {
        val originals = linkedMapOf(
            "src/A.kt" to "fun a() = 1\n",
            "src/B.kt" to "fun b() = 2\n",
        )
        val prepared = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 10\n")
            ),
            rationale = "fix",
        )
        val prompt = WorkspaceGitHubPatchReviewer.prompt(plan.goal, plan, originals, prepared)
        assertTrue(prompt.contains("READ-ONLY QA reviewer"))
        assertTrue(prompt.contains("ACCEPT"))
        assertTrue(prompt.contains("src/A.kt"))
        assertTrue(prompt.contains("fun a() = 1"))
        assertTrue(prompt.contains("fun a() = 10"))
    }

    @Test fun strictReviewContractAcceptsOnlyKnownDecisionsAndKeys() {
        val accepted = WorkspaceGitHubPatchReviewer.read(
            """{"schemaVersion":1,"decision":"ACCEPT","summary":"Looks consistent.","risks":[]}"""
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.ACCEPT, accepted.decision)

        val revise = WorkspaceGitHubPatchReviewer.read(
            """{"schemaVersion":1,"decision":"REVISE","summary":"Missing guard.","risks":["negative input"]}"""
        )
        assertEquals(WorkspaceGitHubPatchReviewer.Decision.REVISE, revise.decision)

        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"MAYBE","summary":"x","risks":[]}"""
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"ACCEPT","summary":"x","risks":[],"patch":"no"}"""
            )
        }.isFailure)
    }

    @Test fun reviewerCannotInspectPathOutsideLockedPlan() {
        val originals = linkedMapOf("src/A.kt" to "fun a() = 1\n")
        val prepared = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/Outside.kt", "fun x() = 2\n")
            ),
            rationale = "scope drift",
        )
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.prompt(plan.goal, plan, originals, prepared)
        }.isFailure)
    }
    @Test fun reviewerNormalizesOneBoundedRiskStringButRejectsOtherTypes() {
        val single = WorkspaceGitHubPatchReviewer.read(
            """{"schemaVersion":1,"decision":"ACCEPT","summary":"Comment-only change.","risks":"No functional impact."}"""
        )
        assertEquals(listOf("No functional impact."), single.risks)

        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"ACCEPT","summary":"x","risks":42}"""
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"ACCEPT","summary":"x","risks":{"text":"no"}}"""
            )
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"ACCEPT","summary":"x","risks":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}"""
            )
        }.isFailure)
    }

    @Test fun reviewerRiskArrayStillRequiresOnlyBoundedStrings() {
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.read(
                """{"schemaVersion":1,"decision":"ACCEPT","summary":"x","risks":["ok",7]}"""
            )
        }.isFailure)
    }

    @Test fun mandatorySecondReviewReceivesPriorReviseLineage() {
        val originals = linkedMapOf(
            "src/A.kt" to "fun a() = 1\n",
            "src/B.kt" to "fun b() = 2\n",
        )
        val rejected = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 10\n")
            ),
            rationale = "first proposal",
        )
        val revised = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 20\n")
            ),
            rationale = "review correction",
        )
        val priorReview = WorkspaceGitHubPatchReviewer.Review(
            decision = WorkspaceGitHubPatchReviewer.Decision.REVISE,
            summary = "The first proposal violates the existing invariant.",
            risks = listOf("Keep the correction inside src/A.kt."),
        )

        val prompt = WorkspaceGitHubPatchReviewer.prompt(
            plan.goal,
            plan,
            originals,
            revised,
            WorkspaceGitHubPatchReviewer.RevisionLineage(rejected, priorReview),
        )

        assertTrue(prompt.contains("MANDATORY SECOND REVIEW"))
        assertTrue(prompt.contains("QA HANDOFF — EXPECTED"))
        assertTrue(prompt.contains("QA HANDOFF — ACTUAL"))
        assertTrue(prompt.contains("QA HANDOFF — EVIDENCE"))
        assertTrue(prompt.contains("QA HANDOFF — FIX INSTRUCTION"))
        assertTrue(prompt.contains("QA HANDOFF — AFFECTED FILES"))
        assertTrue(prompt.contains("PRIOR REVIEW DECISION: REVISE"))
        assertTrue(prompt.contains("violates the existing invariant"))
        assertTrue(prompt.contains("Keep the correction inside src/A.kt"))
        assertTrue(prompt.contains("REJECTED PROPOSAL FILE"))
        assertTrue(prompt.contains("fun a() = 10"))
        assertTrue(prompt.contains("fun a() = 20"))
        assertTrue(prompt.contains("verify that every actionable prior fix instruction/risk is actually resolved"))
        assertTrue(prompt.contains("Do not require the rejected intermediate proposal itself to be committed"))
    }

    @Test fun secondReviewLineageMustComeFromReviseAndStayInLockedScope() {
        val originals = linkedMapOf(
            "src/A.kt" to "fun a() = 1\n",
            "src/B.kt" to "fun b() = 2\n",
        )
        val revised = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 20\n")
            ),
            rationale = "review correction",
        )
        val acceptedReview = WorkspaceGitHubPatchReviewer.Review(
            WorkspaceGitHubPatchReviewer.Decision.ACCEPT,
            "accepted",
            emptyList(),
        )
        val rejected = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/A.kt", "fun a() = 10\n")
            ),
            rationale = "first",
        )
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.prompt(
                plan.goal,
                plan,
                originals,
                revised,
                WorkspaceGitHubPatchReviewer.RevisionLineage(rejected, acceptedReview),
            )
        }.isFailure)

        val outside = WorkspaceGitHubSelfEditBatch.Prepared(
            files = listOf(
                WorkspaceGitHubWritePolicy.FileChange("src/Outside.kt", "fun x() = 9\n")
            ),
            rationale = "outside",
        )
        val revise = WorkspaceGitHubPatchReviewer.Review(
            WorkspaceGitHubPatchReviewer.Decision.REVISE,
            "fix issue",
            emptyList(),
        )
        assertTrue(runCatching {
            WorkspaceGitHubPatchReviewer.prompt(
                plan.goal,
                plan,
                originals,
                revised,
                WorkspaceGitHubPatchReviewer.RevisionLineage(outside, revise),
            )
        }.isFailure)
    }

}
