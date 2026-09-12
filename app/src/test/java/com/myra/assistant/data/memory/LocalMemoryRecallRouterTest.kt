package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMemoryRecallRouterTest {
    private fun evidence(text: String) = AuthoritativeMemoryTurnEvidence(
        turnId = 1L,
        canonicalText = text,
        displayText = text,
        sessionId = "test",
        utteranceId = "test:1"
    )

    @Test fun naturalExperienceQuestionUsesLocalEpisodeRecall() {
        assertEquals(
            MemoryRecallType.EPISODES,
            LocalMemoryRecallRouter.classify(evidence("Kareem ke saath kaha gaya tha?"))?.type
        )
    }

    @Test fun communicationPreferenceQuestionUsesLocalRecall() {
        assertEquals(
            MemoryRecallType.PREFERENCES,
            LocalMemoryRecallRouter.classify(evidence("Mujhe kaisa answer pasand hai?"))?.type
        )
    }

    @Test fun broadWhatDoYouKnowQuestionUsesLocalGeneralRecall() {
        assertEquals(
            MemoryRecallType.GENERAL,
            LocalMemoryRecallRouter.classify(evidence("Tum mere bare me kya jaante ho?"))?.type
        )
    }

    @Test fun unrelatedQuestionDoesNotHijackMemoryLane() {
        assertNull(LocalMemoryRecallRouter.classify(evidence("Weather kaisa hai?")))
    }
}
