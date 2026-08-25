package com.myra.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomTranscriptFilterTest {
    @Test fun ignoresObservedNoiseFragments() {
        listOf("jj", "hhhh", "kk", "tt", "as", "ddhgh", "M m", "Ja")
            .forEach { assertTrue(it, PhantomTranscriptFilter.shouldIgnore(it)) }
    }

    @Test fun preservesMeaningfulContextAndNames() {
        listOf("hi", "no", "Naufal", "Mera best friend kaun hai", "Ayesha meri best friend hai")
            .forEach { assertFalse(it, PhantomTranscriptFilter.shouldIgnore(it)) }
    }
}
