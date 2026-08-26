package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingDeleteClarificationTest {
    @Test fun bareNameBecomesVerifiedForgetCommandOnlyInsidePendingFlow() {
        assertEquals(
            MemoryCommand.Forget("Kareem"),
            PendingDeleteClarification.resolve("Karim")
        )
    }

    @Test fun sentenceOrConfirmationCannotBeGuessedAsPersonName() {
        assertNull(PendingDeleteClarification.resolve("delete the app"))
        assertNull(PendingDeleteClarification.resolve("yes"))
    }
}
