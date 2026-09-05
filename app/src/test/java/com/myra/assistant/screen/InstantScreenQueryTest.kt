package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstantScreenQueryTest {
    @Test fun `overview phrases use instant summary route`() {
        assertEquals(InstantScreenQuery.OVERVIEW, ScreenVisionIntentParser.parseInstantQuery("What do you see?"))
        assertEquals(InstantScreenQuery.OVERVIEW, ScreenVisionIntentParser.parseInstantQuery("Screen mein kya hai?"))
    }

    @Test fun `current app question uses instant app route`() {
        assertEquals(InstantScreenQuery.CURRENT_APP, ScreenVisionIntentParser.parseInstantQuery("Which app is open?"))
    }

    @Test fun `actions and normal chat never enter instant answer route`() {
        assertNull(ScreenVisionIntentParser.parseInstantQuery("Center wala video kholo"))
        assertNull(ScreenVisionIntentParser.parseInstantQuery("Tell me a joke"))
    }
}
