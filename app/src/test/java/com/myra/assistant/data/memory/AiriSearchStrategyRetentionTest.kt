package com.myra.assistant.data.memory

import com.myra.assistant.agent.GeneralVerificationStatus
import com.myra.assistant.agent.ToolCapability
import com.myra.assistant.agent.VerifiedSearchStrategyFeedback
import org.junit.Assert.*
import org.junit.Test

class AiriSearchStrategyRetentionTest {
    private fun value(i: Int, status: GeneralVerificationStatus = GeneralVerificationStatus.SUCCESS) =
        VerifiedSearchStrategyFeedback.Record(
            taskKey = i.toString(16).padStart(64, 'a'),
            capability = ToolCapability.BROWSER_SEARCH,
            scope = "com.android.chrome", status = status
        )

    @Test fun existingAiriBehaviorRowRoundTripsWithoutUserContentOrNewSchema() {
        val record = value(1)
        val row = AiriSearchStrategyRetention.encode(record, 1000L)
        assertEquals(AiriSearchStrategyRetention.KIND, row.kind)
        assertEquals(record, AiriSearchStrategyRetention.decode(row, 1001L))
        assertFalse(row.toString().contains("https://"))
        assertFalse(row.toString().contains("password"))
        assertFalse(row.toString().contains("research prompt"))
    }

    @Test fun rejectsExpiredFutureForgedOrUnverifiedRows() {
        val row = AiriSearchStrategyRetention.encode(value(1), 1000L)
        assertNull(AiriSearchStrategyRetention.decode(row, 999L))
        assertNull(AiriSearchStrategyRetention.decode(row,
            1000L + AiriSearchStrategyRetention.MAX_AGE_MS + 1))
        assertNull(AiriSearchStrategyRetention.decode(row.copy(state = "UNKNOWN"), 1001L))
        assertNull(AiriSearchStrategyRetention.decode(row.copy(observationCount = 5), 1001L))
        assertNull(AiriSearchStrategyRetention.decode(row.copy(
            metadata = "https://untrusted.example/path"), 1001L))
        assertNull(AiriSearchStrategyRetention.decode(row.copy(
            stableKey = "search-strategy:v1:forged"), 1001L))
        assertNull(AiriSearchStrategyRetention.decode(row.copy(kind = "CONTENT_TOPIC"), 1001L))
    }

    @Test fun restoredHistoryIsBoundedAndDeduplicated() {
        val rows = (1..40).map { i ->
            AiriSearchStrategyRetention.encode(value(i), i.toLong() + 1000L)
        }
        val retained = AiriSearchStrategyRetention.retained(rows + rows.last(), 1050L)
        assertEquals(32, retained.size)
        assertEquals(32, retained.map { it.taskKey to it.capability }.distinct().size)
        assertEquals(value(40), retained.last())
        assertEquals(value(9), retained.first())
    }
}
