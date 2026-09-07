package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticMemoryContextResolutionTest {
    @Test fun contextualSelfReferenceCanUseRecentGroundedAntecedent() {
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes coding",
            categoryName = "PREFERENCE",
            memoryKey = "coding_interest",
            evidence = "I love it",
            confidence = 0.93,
            conversationContext = "We were talking about coding. I love it"
        )
        assertEquals("Zopy likes coding", candidate?.fact)
        assertEquals("preference:likes:coding", candidate?.stableKey)
    }

    @Test fun ambiguousSelfReferenceWithoutAntecedentIsRejected() {
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Zopy likes coding",
            categoryName = "PREFERENCE",
            memoryKey = "coding_interest",
            evidence = "I love it",
            confidence = 0.96,
            conversationContext = "I love it"
        ))
    }

    @Test fun contextualPersonPronounRequiresSameVerifiedRecentPerson() {
        MemoryWorkingContext.clear()
        MemoryWorkingContext.person("Kareem")
        val candidate = SemanticMemoryProposalValidator.validate(
            fact = "Kareem likes coding",
            categoryName = "PERSON",
            memoryKey = "kareem_coding",
            evidence = "Usko coding pasand hai",
            confidence = 0.93,
            conversationContext = "Mera friend Kareem hai. Usko coding pasand hai"
        )
        assertEquals("Kareem likes coding", candidate?.fact)
        assertEquals(NaturalMemoryExtractor.stablePersonId("Kareem"), candidate?.entityId)
        MemoryWorkingContext.clear()
    }

    @Test fun contextualPersonPronounCannotJumpToDifferentPerson() {
        MemoryWorkingContext.clear()
        MemoryWorkingContext.person("Karim")
        assertNull(SemanticMemoryProposalValidator.validate(
            fact = "Kareem likes coding",
            categoryName = "PERSON",
            memoryKey = "kareem_coding",
            evidence = "Usko coding pasand hai",
            confidence = 0.97,
            conversationContext = "Mera friend Kareem hai. Usko coding pasand hai"
        ))
        MemoryWorkingContext.clear()
    }
}
