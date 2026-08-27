package com.myra.assistant.screen

object ScreenPrivacyPolicy {
    private val prohibited = Regex(
        """\b(?:otp|password|passcode|pin|cvv|verification\s*code|security\s*code|private\s*key|seed\s*phrase|auth(?:entication)?\s*token|access\s*token|card\s*number|account\s*number|aadhaar|aadhar|pan\s*number)\b""",
        RegexOption.IGNORE_CASE
    )
    private val privateContent = Regex(
        """\b(?:bank|payment|private\s+message|medical|health\s+record|confidential|email\s+inbox)\b""",
        RegexOption.IGNORE_CASE
    )

    fun blocksLongTermMemory(text: String): Boolean =
        text.isBlank() || prohibited.containsMatchIn(text) || privateContent.containsMatchIn(text)

    fun isMemoryWorthy(category: String, confidence: Double): Boolean =
        category.uppercase() in setOf("PROJECT", "GOAL", "PREFERENCE") && confidence >= 0.90
}

