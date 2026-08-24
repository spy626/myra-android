package com.myra.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendConversationPolicyTest {
    @Test fun casualRepliesDefaultToShortAnswersWithoutQuestions() {
        val policy = FriendConversationPolicy.REPLY_DISCIPLINE
        assertTrue(policy.contains("one or two short natural sentences"))
        assertTrue(policy.contains("Answer and stop by default"))
        assertTrue(policy.contains("only question in the entire reply"))
        assertFalse(policy.contains("two questions"))
    }

    @Test fun femaleVoiceDoesNotApplyFemaleGrammarToZopy() {
        val policy = FriendConversationPolicy.MALE_USER_GRAMMAR
        assertTrue(policy.contains("Zopy is male"))
        assertTrue(policy.contains("sakte ho"))
        assertTrue(policy.contains("never address him as sakti ho"))
    }
}
