package com.myra.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendConversationPolicyTest {
    @Test fun casualRepliesDefaultToShortAnswersWithoutQuestions() {
        val policy = FriendConversationPolicy.REPLY_DISCIPLINE
        assertTrue(policy.contains("one or two short natural sentences"))
        assertTrue(policy.contains("answer directly and stop"))
        assertTrue(policy.contains("Do not force every exchange"))
        assertTrue(policy.contains("only question in the entire reply"))
        assertTrue(policy.contains("help kar sakti hoon"))
        assertTrue(policy.contains("isse zyada main kya boloon"))
        assertTrue(policy.contains("never sound dismissive"))
        assertTrue(policy.contains("pressure the user"))
        assertFalse(policy.contains("two questions"))
    }

    @Test fun femaleVoiceDoesNotApplyFemaleGrammarToZopy() {
        val policy = FriendConversationPolicy.MALE_USER_GRAMMAR
        assertTrue(policy.contains("Zopy is male"))
        assertTrue(policy.contains("sakte ho"))
        assertTrue(policy.contains("never address him as sakti ho"))
    }
}
