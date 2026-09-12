package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAwareMemoryAdmissionTest {
    @Test fun directDurableRelationshipStillSavesImmediately() {
        val text = "Kareem mera best friend hai"
        val result = ContextAwareMemoryAdmission.admit(
            text,
            JarvisSimpleMemoryExtractor.extract(text),
            emptyList(),
            emptyList()
        )
        assertEquals("BEST_FRIEND|Kareem", result.candidates.single().value)
    }

    @Test fun hypotheticalRelationshipDoesNotBecomeDurableMemory() {
        val text = "Agar Kareem mera best friend hota to accha hota"
        val result = ContextAwareMemoryAdmission.admit(
            text,
            JarvisSimpleMemoryExtractor.extract(text),
            emptyList(),
            emptyList()
        )
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun reportedRelationshipDoesNotBecomeUsersRelationship() {
        val text = "Naufal ne bola Kareem uska best friend hai"
        val result = ContextAwareMemoryAdmission.admit(
            text,
            JarvisSimpleMemoryExtractor.extract(text),
            emptyList(),
            listOf("Kareem", "Naufal")
        )
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun meaninglessChitchatStaysConversationOnly() {
        val result = ContextAwareMemoryAdmission.admit(
            "haan",
            emptyList(),
            emptyList(),
            emptyList()
        )
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun shortAnswerInTravelContextBecomesOneMeaningfulEpisode() {
        val result = ContextAwareMemoryAdmission.admit(
            "Manali aur Kerala",
            emptyList(),
            listOf(MemoryContextTurn("assistant", "Kareem ke saath kahan gaye the?", 4L)),
            listOf("Kareem")
        )
        val episode = result.candidates.single { it.key.startsWith("episode:") }
        assertEquals("Kareem", episode.subject)
        assertTrue(episode.fact.contains("Manali aur Kerala"))
        assertTrue(episode.fact.contains("Kareem"))
    }

    @Test fun shortNameAnswerInBestFriendContextBecomesRelationship() {
        val result = ContextAwareMemoryAdmission.admit(
            "Kareem",
            emptyList(),
            listOf(MemoryContextTurn("assistant", "Tumhara best friend kaun hai?", 7L)),
            emptyList()
        )
        val relationship = result.candidates.single()
        assertEquals(JarvisMemoryType.RELATIONSHIP, relationship.type)
        assertEquals("BEST_FRIEND|Kareem", relationship.value)
    }

    @Test fun unresolvedPronounExperienceIsNotSaved() {
        val text = "Uske saath Manali gaya tha"
        val result = ContextAwareMemoryAdmission.admit(
            text,
            JarvisSimpleMemoryExtractor.extract(text),
            emptyList(),
            listOf("Kareem", "Naufal")
        )
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun pronounExperienceUsesRecentKnownPerson() {
        val text = "Uske saath Manali gaya tha"
        val result = ContextAwareMemoryAdmission.admit(
            text,
            JarvisSimpleMemoryExtractor.extract(text),
            listOf(MemoryContextTurn("assistant", "Kareem kal call kar raha tha", 8L)),
            listOf("Kareem", "Naufal")
        )
        val episode = result.candidates.single { it.key.startsWith("episode:") }
        assertEquals("Kareem", episode.subject)
        assertTrue(episode.fact.contains("Kareem ke saath"))
    }

    @Test fun pronounRecallCanReuseRecentPersonWithoutNetwork() {
        val resolved = ContextAwareMemoryAdmission.resolveRecallQuery(
            "Uske saath kaha gaya tha?",
            listOf(MemoryContextTurn("assistant", "Kareem tumhara best friend hai", 9L)),
            listOf("Kareem", "Naufal")
        )
        assertTrue(resolved.contains("Kareem ke saath"))
    }

    @Test fun staleAssistantQuestionDoesNotCaptureLaterUnrelatedTurn() {
        val result = ContextAwareMemoryAdmission.admit(
            "Kareem",
            emptyList(),
            listOf(
                MemoryContextTurn("assistant", "Tumhara best friend kaun hai?", 4L),
                MemoryContextTurn("user", "pata nahi", 5L)
            ),
            emptyList()
        )
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun negatedRelationshipCannotBeAddedAsPositiveRelationship() {
        val text = "Kareem mera best friend nahi hai"
        val result = ContextAwareMemoryAdmission.admit(
            text,
            JarvisSimpleMemoryExtractor.extract(text),
            emptyList(),
            listOf("Kareem")
        )
        assertTrue(result.candidates.none { it.type == JarvisMemoryType.RELATIONSHIP })
        assertTrue(ContextAwareMemoryAdmission.rejectsPositiveRelationship(text))
    }
}
