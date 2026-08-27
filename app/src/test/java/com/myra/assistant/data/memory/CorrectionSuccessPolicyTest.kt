package com.myra.assistant.data.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionSuccessPolicyTest {
    @Test fun successAcknowledgementRequiresWriteAndVerification() {
        assertFalse(CorrectionSuccessPolicy.acknowledgementAllowed(false, false))
        assertFalse(CorrectionSuccessPolicy.acknowledgementAllowed(false, true))
        assertFalse(CorrectionSuccessPolicy.acknowledgementAllowed(true, false))
        assertTrue(CorrectionSuccessPolicy.acknowledgementAllowed(true, true))
    }

    @Test fun unresolvedClarificationHasNoSuccessWording() {
        val reply = CorrectionSuccessPolicy.UNRESOLVED_CLARIFICATION_REPLY
        assertFalse(reply.contains("save", ignoreCase = true))
        assertFalse(reply.contains("samajh", ignoreCase = true))
        assertFalse(reply.contains("thanks", ignoreCase = true))
    }
}
