package com.myra.assistant.voice

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusResultPolicyTest {
    @Test fun rawAndroidFocusResultsAreNotCollapsedIntoMisleadingBooleanState() {
        assertEquals("GRANTED", AudioFocusResultPolicy.interpret(AudioManager.AUDIOFOCUS_REQUEST_GRANTED))
        assertEquals("DELAYED", AudioFocusResultPolicy.interpret(AudioManager.AUDIOFOCUS_REQUEST_DELAYED))
        assertEquals("FAILED", AudioFocusResultPolicy.interpret(AudioManager.AUDIOFOCUS_REQUEST_FAILED))
    }

    @Test fun everyAudibleAssistantOwnerCanUseTransientFocusOverMedia() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioFocusGainPolicy.initialGain(true))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioFocusGainPolicy.initialGain(false))
        assertEquals(true, AssistantPlaybackFocusPolicy.requiresTransient("MODEL"))
        assertEquals(true, AssistantPlaybackFocusPolicy.requiresTransient("CONTROLLED_LOCAL"))
        assertEquals(true, AssistantPlaybackFocusPolicy.requiresTransient("CONTROLLED_SCREEN"))
    }
}
