package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemorySafetyPolicyTest {
    @Test fun stableLowRiskPreferenceCanSaveAutomatically() {
        assertEquals(
            MemorySaveDecision.AUTO_SAVE,
            MemorySafetyPolicy.decide(candidate(MemorySensitivity.LOW, confidence = 0.92))
        )
    }

    @Test fun clearRelationshipCanSaveAutomatically() {
        assertEquals(
            MemorySaveDecision.AUTO_SAVE,
            MemorySafetyPolicy.decide(candidate(MemorySensitivity.PERSONAL, confidence = 0.95))
        )
    }

    @Test fun sensitiveInformationStillNeedsPermission() {
        assertEquals(
            MemorySaveDecision.ASK_PERMISSION,
            MemorySafetyPolicy.decide(candidate(MemorySensitivity.SENSITIVE, confidence = 0.95))
        )
    }

    @Test fun explicitRememberRequestCountsAsPermission() {
        assertEquals(
            MemorySaveDecision.AUTO_SAVE,
            MemorySafetyPolicy.decide(
                candidate(MemorySensitivity.SENSITIVE, confidence = 0.90, explicitlyRequested = true)
            )
        )
    }

    @Test fun credentialsAreAlwaysRejected() {
        assertEquals(
            MemorySaveDecision.REJECT,
            MemorySafetyPolicy.decide(
                candidate(MemorySensitivity.LOW, fact = "My OTP is 123456", confidence = 0.99, explicitlyRequested = true)
            )
        )
        assertEquals(
            MemorySaveDecision.REJECT,
            MemorySafetyPolicy.decide(
                candidate(MemorySensitivity.PERSONAL, fact = "My bank account number is 12345", confidence = 0.99, explicitlyRequested = true)
            )
        )
    }

    @Test fun uncertainInferenceIsRejected() {
        assertEquals(
            MemorySaveDecision.REJECT,
            MemorySafetyPolicy.decide(candidate(MemorySensitivity.LOW, confidence = 0.40))
        )
    }

    private fun candidate(
        sensitivity: MemorySensitivity,
        fact: String = "Zopy likes gaming videos",
        confidence: Double,
        explicitlyRequested: Boolean = false
    ) = MemoryCandidate(
        category = MemoryCategory.PREFERENCE,
        fact = fact,
        stableKey = "preference:gaming",
        sensitivity = sensitivity,
        confidence = confidence,
        explicitlyRequested = explicitlyRequested
    )
}
