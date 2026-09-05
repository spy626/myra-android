package com.myra.assistant.service

import com.myra.assistant.agent.GroundedActionResultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseDeliveryOwnershipTest {
    @Test fun controlledSuppressionCannotLeakIntoNewConversationTurn() {
        val arbiter = TurnResponseArbiter()
        arbiter.claimControlled(10L)
        assertFalse(arbiter.acceptsOrdinaryModel())
        val oldGeneration = arbiter.generationId

        arbiter.supersedeForNewUserTurn(11L)

        assertTrue(arbiter.acceptsOrdinaryModel())
        assertEquals(11L, arbiter.turnId)
        assertFalse(arbiter.isCurrent(10L, oldGeneration))
    }

    @Test fun beginningDifferentTurnSupersedesIncompleteControlledOwner() {
        val arbiter = TurnResponseArbiter()
        arbiter.claimControlled(20L)
        arbiter.begin(21L)
        assertTrue(arbiter.acceptsOrdinaryModel())
        assertEquals(21L, arbiter.turnId)
    }

    @Test fun userAndAssistantChatMessagesStoreExactlyOnce() {
        val store = ChatMessageDeliveryStore()
        val user = StoredChatMessage("user:s:1", 1L, ChatRole.USER, "Hello")
        val assistant = StoredChatMessage("assistant:s:1:model", 1L, ChatRole.ASSISTANT, "Hi")
        assertTrue(store.commit(user) is ChatMessageStoreResult.Accepted)
        assertTrue(store.commit(user) is ChatMessageStoreResult.AlreadyStored)
        assertTrue(store.commit(assistant) is ChatMessageStoreResult.Accepted)
        assertTrue(store.commit(assistant) is ChatMessageStoreResult.AlreadyStored)
        assertEquals(listOf(user, assistant), store.snapshot())
    }

    @Test fun controlledAndModelClaimsBothRequireSameTurnVerifiedRuntime() {
        val text = "Privacy open kar diya tumhare liye"
        val noAction = PhysicalActionClaimGate.evaluate(text, 6L, null)
        assertEquals(PhysicalActionClaimDecision.BLOCK, noAction.decision)

        val acceptedOnly = GroundedPhysicalAction(
            6L, "task-6", "ACCESSIBILITY_CLICK", GroundedActionResultState.DISPATCH_ACCEPTED
        )
        assertEquals(
            PhysicalActionClaimDecision.BLOCK,
            PhysicalActionClaimGate.evaluate(text, 6L, acceptedOnly).decision
        )

        val verified = acceptedOnly.copy(state = GroundedActionResultState.VERIFIED_SUCCESS)
        assertEquals(
            PhysicalActionClaimDecision.ALLOW,
            PhysicalActionClaimGate.evaluate(text, 6L, verified).decision
        )
    }

    @Test fun verifiedActionFromDifferentTurnOrCapabilityCannotAuthorizeClaim() {
        val verifiedSearch = GroundedPhysicalAction(
            5L, "task-5", "BROWSER_SEARCH", GroundedActionResultState.VERIFIED_SUCCESS
        )
        assertEquals(
            PhysicalActionClaimDecision.BLOCK,
            PhysicalActionClaimGate.evaluate("Privacy open kar diya", 6L, verifiedSearch).decision
        )
        assertEquals(
            PhysicalActionClaimDecision.BLOCK,
            PhysicalActionClaimGate.evaluate("Privacy open kar diya", 5L, verifiedSearch).decision
        )
    }

    @Test fun fabricatedSettingsPrivacyPackageCannotCreateVerifiedSuccess() {
        assertFalse(PreFinalPhoneToolOwnershipPolicy.mayExecute("OPEN_APP", false))
        val fabricatedAccepted = GroundedPhysicalAction(
            6L,
            "legacy:6:open_app:com.android.settings.privacy",
            "OPEN_APP",
            GroundedActionResultState.DISPATCH_ACCEPTED
        )
        assertEquals(
            PhysicalActionClaimDecision.BLOCK,
            PhysicalActionClaimGate.evaluate(
                "com.android.settings.privacy open kar diya tumhare liye", 6L, fabricatedAccepted
            ).decision
        )
    }

    @Test fun nonActionConversationIsNeverBlockedByClaimGate() {
        val verdict = PhysicalActionClaimGate.evaluate(
            "Settings button ka system aur smart bana sakte hain.", 7L, null
        )
        assertEquals(PhysicalActionClaimDecision.ALLOW, verdict.decision)
    }
}
