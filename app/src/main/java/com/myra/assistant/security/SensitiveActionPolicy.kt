package com.myra.assistant.security

object SensitiveActionPolicy {
    private val blockedWords = Regex("\\b(bank|banking|otp|pin|password|passcode|payment|upi|pay|transfer money)\\b", RegexOption.IGNORE_CASE)
    fun blocksAccessibility(screenText: String): Boolean = blockedWords.containsMatchIn(screenText)
}
