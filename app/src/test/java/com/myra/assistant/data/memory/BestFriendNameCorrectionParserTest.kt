package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestFriendNameCorrectionParserTest {
    @Test fun explicitCorrectionRenamesTheLastSavedPerson() {
        val correction = BestFriendNameCorrectionParser.parse("Nahi, Kareem", "Karima")
        assertEquals("Karima", correction?.oldName)
        assertEquals("Kareem", correction?.newName)
    }

    @Test fun explicitOldToNewCorrectionWorksAfterReconnectWithoutSessionState() {
        val correction = BestFriendNameCorrectionParser.parse("Karima nahi, Kareem", null)
        assertEquals("Karima", correction?.oldName)
        assertEquals("Kareem", correction?.newName)
    }

    @Test fun shortObservedNaufalCorrectionIsAcceptedButNewPersonIsNot() {
        // Now Pal canonicalizes to Naufal immediately, so repeating Nauphala is not a rename.
        assertNull(BestFriendNameCorrectionParser.parse("Nauphala", "Now Pal"))
        assertNull(BestFriendNameCorrectionParser.parse("Ayesha", "Karima"))
        assertNull(BestFriendNameCorrectionParser.parse("haan", "Karima"))
    }
}
