package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonLinkedMemoryIdentityTest {
    @Test fun correctionRenamesLinkedFactAndStableKey() {
        val row = MemoryEntity("1", "person:nauphara:gaming_channel", "PERSON",
            "Nauphara has a gaming channel", "", "PERSONAL", .94, "test", 1, 1, 1)
        val renamed = PersonLinkedMemoryIdentity.rename(row, listOf("Nauphara"), "Naufal")!!
        assertEquals("person:naufal:gaming_channel", renamed.stableKey)
        assertEquals("Naufal has a gaming channel", renamed.fact)
    }

    @Test fun unrelatedPersonIsNeverRenamed() {
        val row = MemoryEntity("1", "person:ayesha:gaming_channel", "PERSON",
            "Ayesha has a gaming channel", "", "PERSONAL", .94, "test", 1, 1, 1)
        assertTrue(PersonLinkedMemoryIdentity.rename(row, listOf("Nauphara"), "Naufal") == null)
    }
}
