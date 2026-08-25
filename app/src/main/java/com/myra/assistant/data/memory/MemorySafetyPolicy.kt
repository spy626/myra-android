package com.myra.assistant.data.memory

object MemorySafetyPolicy {
    private val prohibited = Regex(
        """\b(?:otp|one\s*time\s*password|pin|passcode|password|cvv|security\s*code|verification\s*code|recovery\s*code|private\s*key|seed\s*phrase|bank|account\s*number|card\s*number|aadhaar|aadhar|pan\s*number|passport\s*number)\b""",
        RegexOption.IGNORE_CASE
    )

    fun decide(candidate: MemoryCandidate): MemorySaveDecision {
        if (candidate.fact.isBlank() || candidate.stableKey.isBlank()) return MemorySaveDecision.REJECT
        if (candidate.sensitivity == MemorySensitivity.PROHIBITED || prohibited.containsMatchIn(candidate.fact)) {
            return MemorySaveDecision.REJECT
        }
        if (candidate.explicitlyRequested) return MemorySaveDecision.AUTO_SAVE
        if (candidate.confidence < 0.70) return MemorySaveDecision.REJECT
        // "Best friend" is a strong, unique label. ASR can insert "best", so an
        // automatically extracted relationship must be confirmed before persistence.
        if (MemoryRelationshipPolicy.isBestFriend(candidate)) return MemorySaveDecision.ASK_PERMISSION
        if (candidate.sensitivity == MemorySensitivity.SENSITIVE) return MemorySaveDecision.ASK_PERMISSION
        return if (candidate.confidence >= 0.85) MemorySaveDecision.AUTO_SAVE
        else MemorySaveDecision.ASK_PERMISSION
    }
}
