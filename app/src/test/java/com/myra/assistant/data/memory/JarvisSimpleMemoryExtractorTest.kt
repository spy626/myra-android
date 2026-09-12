package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisSimpleMemoryExtractorTest {
    @Test fun extractsIdentityWithoutModelTool() {
        val rows = JarvisSimpleMemoryExtractor.extract("Mera naam Zopy hai")
        val row = rows.single { it.type == JarvisMemoryType.IDENTITY }
        assertEquals("identity:name", row.key)
        assertEquals("Zopy", row.value)
    }

    @Test fun extractsHinglishFriendStrengthsAsDistinctRelationships() {
        val friend = JarvisSimpleMemoryExtractor.extract("Mera dost Kareem hai").single()
        val good = JarvisSimpleMemoryExtractor.extract("Naufal mera bahut accha dost hai").single()
        val best = JarvisSimpleMemoryExtractor.extract("Mera best dost Samir hai").single()
        assertEquals("FRIEND|Kareem", friend.value)
        assertEquals("GOOD_FRIEND|Naufal", good.value)
        assertEquals("BEST_FRIEND|Samir", best.value)
    }

    @Test fun extractsPreferenceLocally() {
        val row = JarvisSimpleMemoryExtractor.extract("Mujhe short answers pasand hain").single()
        assertEquals(JarvisMemoryType.PREFERENCE, row.type)
        assertEquals("LIKE|short answers", row.value)
    }



    @Test fun normalizesCommonAsrNoiseInCommunicationPreference() {
        val row = JarvisSimpleMemoryExtractor.extract("Mujhe sorta ansara pasanda hai.").single()
        assertEquals(JarvisMemoryType.PREFERENCE, row.type)
        assertEquals("LIKE|short answers", row.value)
    }

    @Test fun questionsNeverBecomeNewMemory() {
        assertTrue(JarvisSimpleMemoryExtractor.extract("Mera naam kya hai?").isEmpty())
        assertTrue(JarvisSimpleMemoryExtractor.extract("Who is my best friend?").isEmpty())
    }

    @Test fun credentialsAndSensitiveIdentifiersNeverPersist() {
        assertTrue(JarvisSimpleMemoryExtractor.extract("Remember my OTP is 123456").isEmpty())
        assertTrue(JarvisSimpleMemoryExtractor.extract("My API key is secret-123").isEmpty())
        assertTrue(JarvisSimpleMemoryExtractor.extract("My Aadhaar number is 1234 5678 9012").isEmpty())
    }
}
