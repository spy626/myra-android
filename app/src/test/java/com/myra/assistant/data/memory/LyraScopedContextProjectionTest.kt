package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyraScopedContextProjectionTest {
    @Test fun progressiveDepthReadsExistingSourceWithoutCreatingMemory() {
        val context = LyraContextRegistry()
        val value = "This is one relevant user-authorized task context from the current screen. " +
            "A longer detail passage helps the agent when full context is needed. " +
            "Read access must not create another persistent memory truth."
        context.ingest(ContextEntry("active_task", value, 31L, 7L),
            ContextMutation.REPLACE_SELF)
        val l0 = context.readScoped("active_task", ScopedContextDepth.L0)
        val l1 = context.readScoped("active_task", ScopedContextDepth.L1)
        val l2 = context.readScoped("active_task", ScopedContextDepth.L2)
        assertEquals(80, l0.single().text.length)
        assertTrue(l1.single().text.length > l0.single().text.length)
        assertEquals(value, l2.single().text)
        assertEquals(1, context.bucket("active_task").size)
    }

    @Test fun staleContextGenerationAndUnknownScopeCannotLeak() {
        val context = LyraContextRegistry()
        context.ingest(ContextEntry("screen", "verified screenshot information",
            12L, 2L), ContextMutation.REPLACE_SELF)
        assertTrue(context.readScoped("screen", ScopedContextDepth.L2, 3L).isEmpty())
        assertTrue(context.readScoped("other_scope", ScopedContextDepth.L0).isEmpty())
    }

    @Test fun expiredEntriesCannotBeProjected() {
        val context = LyraContextRegistry(clock = { 100L })
        context.ingest(ContextEntry("temporary", "expired", 1L, 1L, expiresAt = 99L),
            ContextMutation.REPLACE_SELF)
        assertTrue(context.readScoped("temporary", ScopedContextDepth.L0).isEmpty())
    }
}
