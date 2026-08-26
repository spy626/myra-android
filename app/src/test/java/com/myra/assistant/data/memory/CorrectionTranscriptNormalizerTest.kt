package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CorrectionTranscriptNormalizerTest {
    @Test fun rawHindiCorrectionPreservesDistinctNames() {
        val normalized = CorrectionTranscriptNormalizer.normalize("करीमा नहीं करीम", "Karima nahi karima")
        val correction = BestFriendNameCorrectionParser.parse(normalized, "Karima")!!
        assertEquals("Karima", correction.oldName)
        assertEquals("Kareem", correction.newName)
        assertNotEquals(correction.oldName, correction.newName)
    }
}
