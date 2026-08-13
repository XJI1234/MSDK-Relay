package com.skycommand.relay.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticJournalContractTest {

    @Test
    fun recordsSanitizedEventsInStrictSequence() {
        val journal = DiagnosticJournal.create("run-1", 4, FixedClock(100))

        val first = journal.record(DiagnosticLevel.ERROR, "device-connection", "SDK_FAILURE", "op-1", "url=https://host/path?token=secret C:\\private\\file")
        val second = journal.record(DiagnosticLevel.WARN, "relay-gateway", "SESSION_FAILURE", null, "safe")

        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals(listOf(first, second), journal.pending(32))
        assertFalse(first.safeDetail.contains("secret"))
        assertFalse(first.safeDetail.contains("host"))
        assertFalse(first.safeDetail.contains("C:\\private"))
    }

    @Test
    fun redactsRelayAndStreamUrlsBeforeTheyReachPersistentDiagnostics() {
        val journal = DiagnosticJournal.create("run-1", 4, FixedClock(100))

        val event = journal.record(
            DiagnosticLevel.ERROR,
            "live-stream",
            "STREAM_FAILURE",
            null,
            "relay=wss://desktop.local/relay?token=relay-secret stream=rtmps://ingest.example/live/stream-secret",
        )

        assertFalse(event.safeDetail.contains("desktop.local"))
        assertFalse(event.safeDetail.contains("relay-secret"))
        assertFalse(event.safeDetail.contains("ingest.example"))
        assertFalse(event.safeDetail.contains("stream-secret"))
    }

    @Test
    fun onlyAcknowledgesCurrentRunAndLeavesLaterEventsPending() {
        val journal = DiagnosticJournal.create("run-1", 4, FixedClock(0))
        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "STARTED", null, "")
        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "READY", null, "")

        assertIs<AcknowledgementResult.IgnoredRun>(journal.acknowledge("other-run", 2))
        assertEquals(2, journal.pending(32).size)
        assertIs<AcknowledgementResult.Applied>(journal.acknowledge("run-1", 1))
        assertEquals(listOf(2L), journal.pending(32).map { it.sequence })
        assertIs<AcknowledgementResult.IgnoredStale>(journal.acknowledge("run-1", 1))
    }

    @Test
    fun dropsOldestPendingEventAtCapacityAndReportsTheLoss() {
        val journal = DiagnosticJournal.create("run-1", 2, FixedClock(0))
        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "ONE", null, "")
        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "TWO", null, "")
        val replacement = journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "THREE", null, "")

        assertEquals(listOf(2L, 3L), journal.pending(32).map { it.sequence })
        assertEquals(1, journal.snapshot().droppedEvents)
        assertTrue(replacement.safeDetail.contains("dropped=1"))
    }

    @Test
    fun keepsBusinessCallSafeWhenPersistenceFails() {
        val journal = DiagnosticJournal.create("run-1", 2, FixedClock(0), DiagnosticPersistence { throw IllegalStateException("disk") })

        val event = journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "STARTED", null, "")

        assertEquals(1, event.sequence)
        assertEquals(1, journal.snapshot().persistenceFailures)
    }

    @Test
    fun preservesRecoveredRunUntilThatRunIsAcknowledged() {
        val recovered = DiagnosticEvent(0, DiagnosticLevel.ERROR, "device-connection", "SDK_FAILURE", "old-run", 1, null, "safe")
        val journal = DiagnosticJournal.create("new-run", 4, FixedClock(1), recoveredEvents = listOf(recovered))
        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "STARTED", null, "")

        assertEquals(listOf("old-run"), journal.pending(32).map { it.runId })
        assertIs<AcknowledgementResult.Applied>(journal.acknowledge("old-run", 1))
        assertEquals(listOf("new-run"), journal.pending(32).map { it.runId })
    }

    private class FixedClock(private val time: Long) : DiagnosticClock {
        override fun currentTimeMillis(): Long = time
    }
}
