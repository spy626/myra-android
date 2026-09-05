package com.myra.assistant.data.memory

import java.util.UUID

/** Builds explicit Settings memories without inferring or rewriting the user's text. */
object ManualMemoryPolicy {
    const val SOURCE = "manual_settings"

    fun candidate(
        fact: String,
        category: MemoryCategory,
        stableKey: String = "manual:${UUID.randomUUID()}"
    ): MemoryCandidate? {
        val clean = fact.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 3..200) return null
        return MemoryCandidate(
            category = category,
            fact = clean,
            stableKey = stableKey,
            sensitivity = MemorySensitivity.PERSONAL,
            confidence = 1.0,
            explicitlyRequested = true,
            source = SOURCE
        )
    }
}
