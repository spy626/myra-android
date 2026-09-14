package com.myra.assistant.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGenerationOwnerTest {
    @Test fun stale_generation_is_dropped() {
        val owner = AudioGenerationOwner()
        owner.authorize(11, "MODEL")
        owner.authorize(12, "CONTROLLED_LOCAL")
        assertFalse(owner.accepts(11, "MODEL"))
        assertTrue(owner.accepts(12, "CONTROLLED_LOCAL"))
    }

    @Test fun one_turn_has_one_authorized_audible_generation() {
        val owner = AudioGenerationOwner()
        assertFalse(owner.authorize(21, "MODEL").concurrent)
        assertTrue(owner.authorize(22, "CONTROLLED_LOCAL").concurrent)
        assertFalse(owner.accepts(21, "MODEL"))
        assertTrue(owner.accepts(22, "CONTROLLED_LOCAL"))
        assertFalse(owner.accepts(22, "MODEL"))
    }
}
