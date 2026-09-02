package com.myra.assistant.data.memory

/** Canonical identities for mutually-exclusive preference dimensions. */
object PreferenceMemoryIdentity {
    const val RESPONSE_VERBOSITY_KEY = "preference:response_style"

    private val responseKey = Regex(
        """(?:^|:|_)(?:response_style|response_length|answer_length|verbosity)(?:$|:|_)""",
        RegexOption.IGNORE_CASE
    )
    private val verbosityValue =
        """(?:short|concise|brief|compact|detailed|detail|long|longer|comprehensive)"""
    private val responseNoun =
        """(?:answer|answers|reply|replies|response|responses|explanation|explanations|communication)"""
    private val responseFact = Regex(
        """\b(?:$verbosityValue)\b.{0,35}\b(?:$responseNoun)\b|\b(?:$responseNoun)\b.{0,35}\b(?:$verbosityValue)\b""",
        RegexOption.IGNORE_CASE
    )
    private val supportedCategories = setOf(
        MemoryCategory.PREFERENCE,
        MemoryCategory.COMMUNICATION_STYLE
    )

    fun canonicalize(candidate: MemoryCandidate): MemoryCandidate =
        if (isResponseVerbosity(candidate.category.name, candidate.stableKey, candidate.fact)) {
            candidate.copy(
                category = MemoryCategory.PREFERENCE,
                stableKey = RESPONSE_VERBOSITY_KEY
            )
        } else candidate

    fun isResponseVerbosity(memory: MemoryEntity): Boolean =
        isResponseVerbosity(memory.category, memory.stableKey, memory.fact)

    private fun isResponseVerbosity(categoryName: String, stableKey: String, fact: String): Boolean {
        val category = runCatching { MemoryCategory.valueOf(categoryName) }.getOrNull()
            ?: return false
        if (category !in supportedCategories) return false
        return stableKey == RESPONSE_VERBOSITY_KEY ||
            responseKey.containsMatchIn(stableKey) || responseFact.containsMatchIn(fact)
    }
}
