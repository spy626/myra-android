package com.myra.assistant.data.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BestFriendNameSimilarityTest {
    @Test fun mergesUnseenNaufalVariantPhonetically() {
        assertTrue(BestFriendNameSimilarity.likelySame("Nopal", "Naufal"))
    }

    @Test fun keepsDistinctNamesSeparate() {
        assertFalse(BestFriendNameSimilarity.likelySame("Karima", "Kareem"))
        assertFalse(BestFriendNameSimilarity.likelySame("Ayesha", "Naufal"))
    }
}
