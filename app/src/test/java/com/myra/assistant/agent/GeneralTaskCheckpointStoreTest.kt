package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneralTaskCheckpointStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun receipt(status: AgentRuntimeStatus = AgentRuntimeStatus.WAITING_FOR_RESULT) =
        UnifiedTaskReceipt("task-1", 44L, 2, status, "step-1",
            listOf("verified-step"), false, true,
            status == AgentRuntimeStatus.COMPLETED, null)

    @Test fun persistedReceiptNeverGrantsExecutionOrUserApproval() {
        val root = temporary.newFolder()
        val one = GeneralTaskCheckpointStore(root, now = { 10_000L })
        one.save(receipt())
        val restored = assertNotNull(one.read()).let { one.read()!! }
        assertEquals(44L, restored.sourceTurnId)
        assertTrue(restored.requiresReview)
        assertFalse(restored.mayAutoResume)
        assertFalse(restored.verifiedComplete)
        val raw = root.resolve("general-task-checkpoint.json").readText()
        assertFalse(raw.contains("password"))
        assertFalse(raw.contains("query"))
    }

    @Test fun staleAndMalformedCheckpointsDoNotGetRestored() {
        val root = temporary.newFolder()
        GeneralTaskCheckpointStore(root, now = { 1_000L }).save(receipt())
        assertNull(GeneralTaskCheckpointStore(root, now = {
            1_000L + 8L * 24 * 60 * 60 * 1000
        }).read())
        root.resolve("general-task-checkpoint.json").writeText("{bad")
        assertNull(GeneralTaskCheckpointStore(root).read())
    }

    @Test fun explicitCancelClearsPendingRecoveryOffer() {
        val root = temporary.newFolder()
        val store = GeneralTaskCheckpointStore(root, now = { 10_000L })
        val runtime = GeneralAgentRuntime(now = { 10_000L })
        runtime.setCheckpointSink(store::save)
        val intent = StructuredAgentIntent(TurnIntent.ACTION_REQUEST,
            "search public docs", "search", true, confidence = .99)
        runtime.start(44, intent, "task-1")
        assertTrue(store.read()?.requiresReview == true)
        runtime.cancel()
        val terminal = store.read()!!
        assertEquals(AgentRuntimeStatus.CANCELLED, terminal.status)
        assertFalse(terminal.requiresReview)
    }

    @Test fun reviewedCheckpointClosesOnlyExpectedIdentityWithoutReplayingTask() {
        val root = temporary.newFolder()
        val store = GeneralTaskCheckpointStore(root, now = { 10_000L })
        store.save(receipt())
        val expected = store.read()!!
        assertFalse(store.closeForFreshRequest(expected.copy(taskId = "wrong")))
        assertTrue(store.read()!!.requiresReview)
        assertTrue(store.closeForFreshRequest(expected))
        assertFalse(store.read()!!.requiresReview)
        assertEquals(AgentRuntimeStatus.CANCELLED, store.read()!!.status)
        assertFalse(store.closeForFreshRequest(expected))
    }

    @Test fun autonomousPublicSearchIsBoundedAndConsumedOnce() {
        val root = temporary.newFolder()
        val store = GeneralTaskCheckpointStore(root, now = { 10_000L })
        store.saveWithSafeSearch(receipt(), "Kotlin coroutines")
        val pending = store.read()!!
        assertEquals("Kotlin coroutines", pending.safeSearchQuery)
        assertTrue(pending.requiresReview)
        assertTrue(store.closeForFreshRequest(pending))
        assertNull(store.read()!!.safeSearchQuery)
        assertFalse(store.closeForFreshRequest(pending))
    }

    @Test fun unsafeOrCredentialQueryNeverGetsSaved() {
        val root = temporary.newFolder()
        val store = GeneralTaskCheckpointStore(root, now = { 10_000L })
        store.saveWithSafeSearch(receipt(), "my secret account")
        assertNull(store.read()!!.safeSearchQuery)
    }

    @Test fun completionFlagRequiresBothTerminalStatusAndVerifiedStep() {
        val root = temporary.newFolder()
        val store = GeneralTaskCheckpointStore(root, now = { 10_000L })
        store.save(receipt(AgentRuntimeStatus.COMPLETED))
        assertTrue(store.read()?.verifiedComplete == true)
        store.save(receipt(AgentRuntimeStatus.COMPLETED).copy(lastVerifiedStepIds = emptyList()))
        assertFalse(store.read()?.verifiedComplete == true)
    }
}
