package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClarifiedPersonNameResolverTest {
    @Test fun bareClarificationResolves() {
        assertEquals("Kareem", ClarifiedPersonNameResolver.resolve("Kareem."))
    }

    @Test fun letterByLetterClarificationResolves() {
        assertEquals("Kareem", ClarifiedPersonNameResolver.resolve("K-A-R-E-E-M"))
        assertEquals("Kareem", ClarifiedPersonNameResolver.resolve("K A R E E M"))
    }

    @Test fun conservativePhoneticResolutionHandlesRecordedSpellingTranscript() {
        assertEquals("Kareem", ClarifiedPersonNameResolver.resolve("Queeriem"))
        assertNull(ClarifiedPersonNameResolver.resolve("haan"))
    }
}
