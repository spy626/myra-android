package com.myra.assistant.ui.workspace

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkflowImprovementActivationTest {
    private val caps = listOf(
        "CONNECTED_REPOSITORY_READ",
        "PROTECTED_FEATURE_BRANCH_WRITE",
        "GITHUB_ACTIONS_CI_VERIFY",
    )

    private fun experience(character: Char, run: Int, time: Long) =
        WorkspaceWorkflowExperience.validate(WorkspaceWorkflowExperience.Record(
            id = "github:" + character.toString().repeat(40),
            kind = WorkspaceWorkflowExperience.Kind.CONNECTED_GITHUB_SELF_EDIT,
            userTask = "Narrow safe workflow",
            repository = "spy626/myra-android",
            branch = "agent/myra-phase-1",
            commitSha = character.toString().repeat(40),
            changedFiles = listOf("app/src/main/java/com/myra/assistant/ui/workspace/Test.kt"),
            capabilities = caps,
            evidenceKind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
            evidenceSignal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
            verificationRef = "ci:" + run,
            verificationUrl = "https://github.com/spy626/myra-android/actions/runs/" + run,
            outcome = WorkspaceWorkflowExperience.Outcome.VERIFIED_SUCCESS,
            capturedAtMs = time,
        ))

    private fun feedback(
        experience: WorkspaceWorkflowExperience.Record,
        kind: WorkspaceWorkflowFeedbackIntent.Kind,
        time: Long,
    ) = WorkspaceWorkflowFeedback.fromUserTurn(
        targetExperienceId = experience.id,
        decision = WorkspaceWorkflowFeedbackIntent.Decision(kind, 0.99),
        sourceTurnId = "feedback-" + experience.id + "-" + kind.name + "-" + time,
        userText = when (kind) {
            WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM -> "haan sahi tha"
            WorkspaceWorkflowFeedbackIntent.Kind.CORRECT -> "ye galat tha"
            WorkspaceWorkflowFeedbackIntent.Kind.UNDO -> "undo this result"
        },
        capturedAtMs = time,
    )

    private val two = listOf(experience('a', 3360, 1), experience('b', 3362, 2))
    private val positive = two.mapIndexed { i, e ->
        feedback(e, WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM, 3L + i)
    }
    private fun proposal(
        experiences: List<WorkspaceWorkflowExperience.Record> = two,
        feedback: List<WorkspaceWorkflowFeedback.Record> = positive,
    ) = WorkspaceWorkflowImprovementActivation.currentProposals(experiences, feedback).single()

    @Test fun explicitSeparateActivationCommandRequired() {
        val gate = WorkspaceWorkflowImprovementActivation
        assertTrue(gate.isExplicitRequest("activate this proposal"))
        assertTrue(gate.isExplicitRequest("activate proposal " + proposal().id))
        assertFalse(gate.isExplicitRequest("approve this proposal"))
        assertFalse(gate.isExplicitRequest("can you activate this proposal?"))
        assertFalse(gate.isExplicitRequest("do not activate this proposal"))
        assertFalse(gate.isExplicitRequest("activate GitHub writes now"))
    }

    @Test fun noApprovalCannotActivateButExactApprovalCanPersist() {
        val current = proposal()
        assertNull(WorkspaceWorkflowImprovementActivation.issue(
            current.id, "activation-turn", 20L, two, positive, emptyList()
        ))
        val approval = WorkspaceWorkflowImprovementApproval.fromUserTurn(
            current, "separate-approval-turn", 10L
        )
        val active = requireNotNull(WorkspaceWorkflowImprovementActivation.issue(
            current.id, "activation-turn", 20L, two, positive, listOf(approval)
        ))
        val root = Files.createTempDirectory("workflow-activation-test").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            // A matching persisted approval is mandatory even when a valid-looking record is passed.
            val failure = runCatching { store.recordActivation(active) }
            assertTrue(failure.isFailure)
            store.recordApproval(approval)
            assertEquals(active, store.recordActivation(active))
            assertEquals(active, store.recordActivation(active))
            assertEquals(listOf(active), store.listActivations())
            assertTrue(WorkspaceWorkflowImprovementActivation.effective(
                store.listActivations().single(), two, positive, store.listApprovals()
            ))
            assertEquals(active, WorkspaceWorkflowImprovementActivation.fromJson(
                WorkspaceWorkflowImprovementActivation.toJson(active)
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun changedProposalCannotUseStaleApprovalOrActivation() {
        val current = proposal()
        val approval = WorkspaceWorkflowImprovementApproval.fromUserTurn(current, "approve", 10L)
        val active = requireNotNull(WorkspaceWorkflowImprovementActivation.issue(
            current.id, "activate", 20L, two, positive, listOf(approval)
        ))
        val third = experience('c', 3364, 5L)
        val grownExperiences = two + third
        val grownFeedback = positive + feedback(third, WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM, 6L)
        assertNotEquals(current.id, proposal(grownExperiences, grownFeedback).id)
        assertNull(WorkspaceWorkflowImprovementActivation.issue(
            current.id, "again", 30L, grownExperiences, grownFeedback, listOf(approval)
        ))
        assertFalse(WorkspaceWorkflowImprovementActivation.effective(
            active, grownExperiences, grownFeedback, listOf(approval)
        ))
        assertNull(WorkspaceWorkflowImprovementActivation.issue(
            proposal(grownExperiences, grownFeedback).id, "new", 31L,
            grownExperiences, grownFeedback, listOf(approval)
        ))
    }

    @Test fun correctionOrUndoAfterActivationSuppressesGuidance() {
        val current = proposal()
        val approval = WorkspaceWorkflowImprovementApproval.fromUserTurn(current, "approve", 10L)
        val active = requireNotNull(WorkspaceWorkflowImprovementActivation.issue(
            current.id, "activate", 20L, two, positive, listOf(approval)
        ))
        val valid = WorkspaceWorkflowImprovementActivation.instructions(
            listOf(active), two, positive, listOf(approval)
        )
        assertTrue(valid.contains("ACTIVE_PLANNING_ONLY"))
        assertTrue(valid.contains("NOT a tool decision"))
        assertTrue(valid.contains("WorkspaceExecutionAuthority remain mandatory"))
        for (kind in listOf(
            WorkspaceWorkflowFeedbackIntent.Kind.CORRECT,
            WorkspaceWorkflowFeedbackIntent.Kind.UNDO,
        )) {
            val negative = positive + feedback(two[0], kind, 30L)
            assertFalse(WorkspaceWorkflowImprovementActivation.effective(
                active, two, negative, listOf(approval)
            ))
            assertEquals("", WorkspaceWorkflowImprovementActivation.instructions(
                listOf(active), two, negative, listOf(approval)
            ))
            assertNull(WorkspaceWorkflowImprovementActivation.issue(
                current.id, "try-again", 40L, two, negative, listOf(approval)
            ))
        }
    }

    @Test fun olderCorrectionStillBlocksEvenAfterLaterConfirmation() {
        val negative = positive +
            feedback(two[0], WorkspaceWorkflowFeedbackIntent.Kind.CORRECT, 30L) +
            feedback(two[0], WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM, 31L)
        // Current reflection may see the latest confirmation. Full retained counter-evidence wins.
        val current = proposal(two, negative)
        val approval = WorkspaceWorkflowImprovementApproval.fromUserTurn(current, "approve", 32L)
        assertNull(WorkspaceWorkflowImprovementActivation.issue(
            current.id, "activate", 33L, two, negative, listOf(approval)
        ))
    }

    @Test fun combinedConsentRequiresExplicitCurrentActionAndNoQuestions() {
        val intent = WorkspaceWorkflowImprovementApprovalIntent
        assertTrue(intent.requestsPlanningActivation("approve and activate this proposal"))
        assertTrue(intent.requestsPlanningActivation("haan approve aur activate karo"))
        assertTrue(intent.requestsPlanningActivation("approve & activate planning"))
        assertTrue(intent.requestsPlanningActivation(
            "approve and activate proposal " + proposal().id
        ))
        assertTrue(intent.requestsPlanningActivation("approve this proposal and activate"))
        assertFalse(intent.requestsPlanningActivation("approve this proposal"))
        assertFalse(intent.requestsPlanningActivation("haan sahi hai"))
        assertFalse(intent.requestsPlanningActivation("should I approve and activate this proposal?"))
        assertFalse(intent.requestsPlanningActivation("I approve and activate this proposal?"))
        assertFalse(intent.requestsPlanningActivation("do not approve and activate this proposal"))
        assertFalse(intent.requestsPlanningActivation("approve and activate GitHub writes"))
    }

    @Test fun explicitStaleProposalIdCannotFallbackToAnotherVisibleProposal() {
        val live = proposal()
        val stale = "workflow-proposal:" + "f".repeat(64)
        val grounding = WorkspaceWorkflowImprovementApprovalGrounding
        assertNull(grounding.resolve(
            "approve and activate proposal " + stale,
            listOf(live.summary),
            listOf(live),
        ))
        assertNull(grounding.resolve(
            "approve and activate workflow-proposal:old",
            listOf(live.summary),
            listOf(live),
        ))
        assertEquals(live, grounding.resolve(
            "approve and activate proposal " + live.id,
            emptyList(),
            listOf(live),
        ))
    }

    @Test fun oneFreshConsentTurnApprovesAndActivatesPlanningWithoutOldApprovalAutoplay() {
        val root = Files.createTempDirectory("workflow-auto-approval").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            two.forEach { store.record(it) }
            positive.forEach { store.recordFeedback(it) }
            val current = proposal()
            assertEquals(emptyList<WorkspaceWorkflowImprovementApproval.Record>(), store.listApprovals())
            assertEquals(emptyList<WorkspaceWorkflowImprovementActivation.Record>(), store.listActivations())
            val historical = WorkspaceWorkflowImprovementApproval.fromUserTurn(
                current, "old-approval-only-turn", 10L
            )
            store.recordApproval(historical)
            // Historic approval never becomes an active overlay just by being loaded.
            assertTrue(store.listActivations().isEmpty())
            val combined = requireNotNull(store.approveAndActivatePlanning(
                proposal = current, sourceTurnId = "new-combined-user-turn", atMs = 20L
            ))
            val active = requireNotNull(combined.activation)
            assertNotEquals(historical.id, combined.approval.id)
            assertEquals(combined.approval.id, active.approvalId)
            assertTrue(WorkspaceWorkflowImprovementActivation.effective(
                active, store.list(), store.listFeedback(), store.listApprovals()
            ))
            val reopened = WorkspaceWorkflowExperienceStore(root)
            assertTrue(reopened.listActivations().contains(active))
            assertTrue(WorkspaceWorkflowImprovementActivation.instructions(
                reopened.listActivations(),
                reopened.list(),
                reopened.listFeedback(),
                reopened.listApprovals(),
            ).contains("ACTIVE_PLANNING_ONLY"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun staleCandidateBeforeCombinedConsentWritesNeitherApprovalNorActivation() {
        val root = Files.createTempDirectory("workflow-auto-stale").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            two.forEach { store.record(it) }
            positive.forEach { store.recordFeedback(it) }
            val old = proposal()
            store.record(experience('c', 3364, 9L))
            assertNull(store.approveAndActivatePlanning(old, "stale-turn", 20L))
            assertTrue(store.listApprovals().isEmpty())
            assertTrue(store.listActivations().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun correctionAutoRevokesPermanentlyEvenIfLaterConfirmationArrives() {
        val root = Files.createTempDirectory("workflow-auto-revoke").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            two.forEach { store.record(it) }
            positive.forEach { store.recordFeedback(it) }
            val combined = requireNotNull(store.approveAndActivatePlanning(
                proposal(), "combined-turn", 20L
            ))
            assertTrue(combined.activation != null)
            val negative = feedback(two[0], WorkspaceWorkflowFeedbackIntent.Kind.CORRECT, 30L)
            store.recordFeedback(negative)
            assertTrue(store.listActivations().isEmpty())
            store.recordFeedback(feedback(
                two[0], WorkspaceWorkflowFeedbackIntent.Kind.CONFIRM, 31L
            ))
            assertTrue(WorkspaceWorkflowExperienceStore(root).listActivations().isEmpty())
            assertEquals("", WorkspaceWorkflowImprovementActivation.instructions(
                store.listActivations(), store.list(), store.listFeedback(), store.listApprovals()
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun newExecutionAutomaticallyInvalidatesPriorProposalActivation() {
        val root = Files.createTempDirectory("workflow-auto-experience").toFile()
        try {
            val store = WorkspaceWorkflowExperienceStore(root)
            two.forEach { store.record(it) }
            positive.forEach { store.recordFeedback(it) }
            val combined = requireNotNull(store.approveAndActivatePlanning(
                proposal(), "combined-turn", 20L
            ))
            assertTrue(combined.activation != null)
            store.record(experience('c', 3364, 30L))
            assertTrue(WorkspaceWorkflowExperienceStore(root).listActivations().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
