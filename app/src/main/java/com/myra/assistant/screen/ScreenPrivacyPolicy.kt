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

    /** Screen analysis is allowed by default. Only labels containing an actual secret value are masked. */
    fun sensitiveCategory(text: String): String? {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return when {
            Regex("\\b(?:otp|one[ -]?time password|verification code)\\b.*\\b\\d{4,8}\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "OTP"
            Regex("\\b(?:password|passcode)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "PASSWORD"
            Regex("\\bpin\\b.*\\b\\d{4,8}\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "PIN"
            Regex("\\b(?:cvv|card number)\\b.*\\b[\\d -]{3,23}\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "CARD"
            Regex("\\b(?:bank )?account number\\b.*\\b[\\d -]{6,24}\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "BANK_ACCOUNT"
            Regex("\\b(?:auth(?:entication)? token|access token|private key|seed phrase)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "AUTH_SECRET"
            Regex("\\b(?:aadhaar|aadhar)\\b.*\\b[\\d -]{12,16}\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "IDENTIFIER"
            else -> null
        }
    }
}
