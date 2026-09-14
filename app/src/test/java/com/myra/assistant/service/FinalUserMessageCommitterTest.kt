package com.myra.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalUserMessageCommitterTest {
    private fun message(turn: Long, text: String) = FinalUserMessage(
        sessionId = "session-1",
        turnId = turn,
        utteranceId = "session-1:$turn",
        raw = text,
        normalized = text,
        display = text
    )

    @Test fun oneFinalCallbackProducesOneMessage() {
        val gate = FinalUserMessageCommitter()
        assertTrue(gate.commit(message(1, "hello")) is UserMessageCommitResult.Accepted)
    }

    @Test fun repeatedCallbacksForSameTurnAreIdempotent() {
        val gate = FinalUserMessageCommitter()
        gate.commit(message(2, "Mere bare mein kya jaante ho?"))
        assertTrue(
            gate.commit(message(2, "Mere bare mein kya jaante ho?")) is
                UserMessageCommitResult.AlreadyCommitted
        )
    }

    @Test fun normalAndControlledPathsCannotAppendSameTurnTwice() {
        val gate = FinalUserMessageCommitter()
        val accepted = listOf(
            gate.commit(message(3, "Mere bare mein kya jaante ho?")),
            gate.commit(message(3, "Mere bare mein kya jaante ho?"))
        ).count { it is UserMessageCommitResult.Accepted }
        assertEquals(1, accepted)
    }

    @Test fun replayOrResubscriptionCannotAppendSameIdentityAgain() {
        val gate = FinalUserMessageCommitter()
        val original = message(4, "Mera best friend kaun hai?")
        gate.commit(original)
        repeat(5) {
            assertTrue(gate.commit(original) is UserMessageCommitResult.AlreadyCommitted)
        }
    }

    @Test fun identicalTextInDifferentTurnsProducesTwoMessages() {
        val gate = FinalUserMessageCommitter()
        val results = listOf(gate.commit(message(5, "repeat")), gate.commit(message(6, "repeat")))
        assertEquals(2, results.count { it is UserMessageCommitResult.Accepted })
    }

    @Test fun twoSeparateQuestionsProduceExactlyTwoMessagesNotThree() {
        val gate = FinalUserMessageCommitter()
        val results = listOf(
            gate.commit(message(7, "Mera best friend kaun hai?")),
            gate.commit(message(8, "Mere bare mein kya jaante ho?")),
            gate.commit(message(8, "Mere bare mein kya jaante ho?"))
        )
        assertEquals(2, results.count { it is UserMessageCommitResult.Accepted })
        assertEquals(1, results.count { it is UserMessageCommitResult.AlreadyCommitted })
    }
}
