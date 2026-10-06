package com.myra.assistant.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePipelineLoggerTest {
    @Test fun exportedDiagnosticsRedactTranscriptPayloadsButKeepDecisionMetadata() {
        val input = "final_transcript_duplicate_guard candidateTranscript=Mujhe short answers pasand hain " +
            "finalGeminiTranscript=मुझे शॉर्ट आंसर पसंद है। duplicateFinalDetected=false"
        val safe = VoicePipelineLogger.sanitize(input)
        assertFalse(safe.contains("Mujhe short answers"))
        assertFalse(safe.contains("शॉर्ट आंसर"))
        assertTrue(safe.contains("candidateTranscript=[redacted]"))
        assertTrue(safe.contains("finalGeminiTranscript=[redacted]"))
        assertTrue(safe.contains("duplicateFinalDetected=false"))
    }

    @Test fun userMessageCommitPayloadIsRedacted() {
        val safe = VoicePipelineLogger.sanitize(
            "user_message_commit_attempt raw=secret words normalized=secret words display=secret words source=TURN_COMPLETE accepted=true"
        )
        assertFalse(safe.contains("secret words"))
        assertTrue(safe.contains("source=TURN_COMPLETE"))
        assertTrue(safe.contains("accepted=true"))
    }
}
