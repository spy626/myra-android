package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class BestFriendNameCanonicalizerTest {
    @Test fun canonicalizesObservedNaufalAndAyeshaAsrSpellings() {
        listOf("Now Pal", "Nau phala", "Nauphala", "Noval", "Naipal").forEach {
            assertEquals(it, "Naufal", BestFriendNameCanonicalizer.canonicalize(it))
        }
        listOf("Aisha", "Aysha", "Ayesha").forEach {
            assertEquals(it, "Ayesha", BestFriendNameCanonicalizer.canonicalize(it))
        }
    }

    @Test fun doesNotGuessThatLegitimateKarimaMeansKareem() {
        assertEquals("Karima", BestFriendNameCanonicalizer.canonicalize("Karima"))
    }
}
