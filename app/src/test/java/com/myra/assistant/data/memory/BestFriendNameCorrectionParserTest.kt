package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestFriendNameCorrectionParserTest {
    @Test fun parsesCommonNehiAsrCorrection() {
        assertEquals(
            BestFriendNameCorrection("Nauphara", "Naufal"),
            BestFriendNameCorrectionParser.parse("Nauphara nehi, Naufal", "Nauphara")
        )
    }

    @Test fun correctionTargetsRecentStoredAliasAndCanonicalizesNewPronunciation() {
        assertEquals(
            BestFriendNameCorrection("Now Farah", "Naufal"),
            BestFriendNameCorrectionParser.parse("Nowar nahi, now fal", "Now Farah")
        )
    }

    @Test fun explicitCorrectionRenamesTheLastSavedPerson() {
        val correction = BestFriendNameCorrectionParser.parse("Nahi, Kareem", "Karima")
        assertEquals("Karima", correction?.oldName)
        assertEquals("Kareem", correction?.newName)
    }

    @Test fun explicitOldToNewCorrectionWorksAfterReconnectWithoutSessionState() {
        val correction = BestFriendNameCorrectionParser.parse("Karima nahi, Kareem", null)
        assertEquals("Karima", correction?.oldName)
        assertEquals("Kareem", correction?.newName)
    }

    @Test fun karimaToKareemTargetsTheStoredOldIdentity() {
        assertEquals(
            BestFriendNameCorrection("Karima", "Kareem"),
            BestFriendNameCorrectionParser.parse("Karima nahi, Kareem", "Karima")
        )
    }

    @Test fun shortObservedNaufalCorrectionIsAcceptedButNewPersonIsNot() {
        // Now Pal canonicalizes to Naufal immediately, so repeating Nauphala is not a rename.
        assertNull(BestFriendNameCorrectionParser.parse("Nauphala", "Now Pal"))
        assertNull(BestFriendNameCorrectionParser.parse("Ayesha", "Karima"))
        assertNull(BestFriendNameCorrectionParser.parse("haan", "Karima"))
    }

    @Test fun identicalMistranscribedCorrectionRequiresClearRepeat() {
        assertEquals(true, BestFriendNameCorrectionParser.needsClearCorrectedName("Karima nahi karima"))
        assertEquals(false, BestFriendNameCorrectionParser.needsClearCorrectedName("Karima nahi Kareem"))
        assertEquals(
            "Karima",
            BestFriendNameCorrectionParser.ambiguousOldName("Karima nahi karima", "Karima")
        )
    }

    @Test fun ordinaryHindiFragmentsNeverManufactureCorrectionIntent() {
        listOf(
            "Karima ne", "Karima ko", "Karima se", "Karima ka", "Karima hai",
            "Kareem acha hai", "Karima ne mujhe call kiya"
        ).forEach { transcript ->
            val decision = BestFriendNameCorrectionParser.analyze(transcript, "Kareem")
            assertEquals(transcript, false, decision.correctionIntentDetected)
            assertEquals(transcript, false, decision.databaseMutationAllowed)
            assertEquals(transcript, "no_explicit_correction_intent", decision.rejectionReason)
        }
    }

    @Test fun parsesAllSupportedExplicitCorrectionStructures() {
        assertEquals(
            BestFriendNameCorrection("Karima", "Kareem"),
            BestFriendNameCorrectionParser.parse("Karima ka naam Kareem hai", null)
        )
        assertEquals(
            BestFriendNameCorrection("Karima", "Kareem"),
            BestFriendNameCorrectionParser.parse("Maine Karima nahi kaha, Kareem kaha", null)
        )
    }

    @Test fun explicitIntentStillRejectsIncompleteParticleName() {
        val decision = BestFriendNameCorrectionParser.analyze("Kareem nahi, Karima ne", "Kareem")
        assertEquals(true, decision.correctionIntentDetected)
        assertEquals("contains_hindi_particle", decision.rejectionReason)
        assertEquals(false, decision.databaseMutationAllowed)
        assertNull(decision.correction)
    }

    @Test fun rejectedFalseCorrectionLeavesPreviouslyVerifiedNameUnchanged() {
        var storedName = "Kareem"
        BestFriendNameCorrectionParser.parse("Karima ne", storedName)?.let { storedName = it.newName }
        assertEquals("Kareem", storedName)
    }
}
