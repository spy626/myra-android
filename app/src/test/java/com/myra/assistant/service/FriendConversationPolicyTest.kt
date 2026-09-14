package com.myra.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendConversationPolicyTest {
    @Test fun casualRepliesDefaultToShortAnswersWithoutQuestions() {
        val policy = FriendConversationPolicy.REPLY_DISCIPLINE
        assertTrue(policy.contains("Default to one short natural sentence"))
        assertTrue(policy.contains("Answer complete questions directly and stop"))
        assertTrue(policy.contains("never append a closing question"))
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

    @Test
    fun bossStyleIsOccasionalAndRequiresVerifiedActions() {
        val policy = FriendConversationPolicy.BOSS_ASSISTANT_STYLE.lowercase()

        assertTrue(policy.contains("occasionally"))
        assertTrue(policy.contains("never in every reply"))
        assertTrue(policy.contains("before android verifies it"))
        assertFalse(policy.contains("always say 'boss'"))
    }

    @Test
    fun friendModeStaysNaturalRatherThanCustomerService() {
        val policy = FriendConversationPolicy.REPLY_DISCIPLINE.lowercase()

        assertTrue(policy.contains("one short natural sentence"))
        assertTrue(policy.contains("never use customer-support wording"))
    }
}
