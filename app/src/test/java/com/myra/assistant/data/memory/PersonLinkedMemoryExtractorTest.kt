package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonLinkedMemoryExtractorTest {
    @Test fun learnsRelationshipAndGamingChannelFromOneHinglishTurn() {
        val facts = PersonLinkedMemoryExtractor.extractAll(
            "meri ek best frend hai naufal iska gaming channel hai bina soye game khelta hai"
        )

        assertEquals(
            listOf("Zopy's best friend is Naufal", "Naufal has a gaming channel"),
            facts.map { it.fact }
        )
        assertEquals("person:naufal:gaming_channel", facts.last().stableKey)
        assertTrue(facts.none { it.fact.contains("sleep", true) || it.fact.contains("soye", true) })
    }

    @Test fun learnsSameFactsWhenNameComesBeforeHai() {
        val facts = PersonLinkedMemoryExtractor.extractAll(
            "meri best friend Nopal hai aur uska gaming channel hai"
        )

        assertEquals("Zopy's best friend is Nopal", facts.first().fact)
        assertEquals("Nopal has a gaming channel", facts.last().fact)
    }

    @Test fun ordinaryTemporaryGamingClaimIsNotSavedWithoutDurableChannelFact() {
        val facts = PersonLinkedMemoryExtractor.extractAll(
            "meri best friend Naufal hai aur woh bina soye game khelta hai"
        )

        assertEquals(listOf("Zopy's best friend is Naufal"), facts.map { it.fact })
    }

    @Test fun learnsFromClearMeraTranscriptObservedOnPhone() {
        val facts = PersonLinkedMemoryExtractor.extractAll(
            "Mera ek best friend hai Naufal, Uska gaming channel hai. Bina soye game khelta hai"
        )

        assertEquals(
            listOf("Zopy's best friend is Naufal", "Naufal has a gaming channel"),
            facts.map { it.fact }
        )
        assertEquals(MemorySaveDecision.AUTO_SAVE, MemorySafetyPolicy.decide(facts.last()))
    }

    @Test fun learnsFromGarbledChannelTranscriptObservedOnPhone() {
        val facts = PersonLinkedMemoryExtractor.extractAll(
            "Mera ek best friend nauphala hai, uska geminga cainala hai aura bina soe gema khelata hai"
        )

        assertEquals(
            listOf("Zopy's best friend is Naufal", "Naufal has a gaming channel"),
            facts.map { it.fact }
        )
    }
    @Test fun learnsDurableGamingCreatorFactButNotSleepExaggeration() {
        val facts = PersonLinkedMemoryExtractor.extractAll(
            "mera best friend Naufal hai, gaming videos banata hai aur bina soye khelta hai"
        )
        assertEquals(
            listOf("Zopy's best friend is Naufal", "Naufal creates gaming videos"),
            facts.map { it.fact }
        )
    }
}
