package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualRelationshipMemoryExtractorTest {
    @Test fun learnsFromOneNaturalHinglishThought() {
        val candidate = ContextualRelationshipMemoryExtractor.extract(
            listOf("Mera ek male best friend hai, uska naam Kareem hai, vo bohot accha dost hai")
        )
        assertEquals("Zopy's best friend is Kareem", candidate?.fact)
    }

    @Test fun combinesTwoCompletedRelatedTurns() {
        val candidate = ContextualRelationshipMemoryExtractor.extract(
            listOf("Mera ek best friend hai", "Uska naam Kareem hai")
        )
        assertEquals("person:best_friend", candidate?.stableKey)
        assertEquals("Zopy's best friend is Kareem", candidate?.fact)
    }

    @Test fun learnsEnglishWording() {
        val candidate = ContextualRelationshipMemoryExtractor.extract(
            listOf("I have a male best friend. His name is Kareem")
        )
        assertEquals("Zopy's best friend is Kareem", candidate?.fact)
    }

    @Test fun doesNotGuessFromVagueFriendMention() {
        assertNull(ContextualRelationshipMemoryExtractor.extract(listOf("Kareem bohot accha dost hai")))
        assertNull(ContextualRelationshipMemoryExtractor.extract(listOf("Kareem ke saath movie dekhi")))
    }

    @Test fun incompleteRelationshipWithoutNameDoesNotSave() {
        assertNull(ContextualRelationshipMemoryExtractor.extract(listOf("Mera ek male best friend hai")))
    }
}
