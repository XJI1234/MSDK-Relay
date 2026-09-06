package com.skycommand.relay.diagnostics.android

import com.skycommand.relay.diagnostics.DiagnosticEvent
import com.skycommand.relay.diagnostics.DiagnosticLevel
import java.io.File
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticEventFileCodecTest {
    @Test
    fun roundTripsEventsAndSkipsDamagedLines() {
        val events = listOf(DiagnosticEvent(1, DiagnosticLevel.ERROR, "device-connection", "SDK_FAILURE", "run-1", 2, "op-1", "safe"))

        val decoded = DiagnosticEventFileCodec.decode(DiagnosticEventFileCodec.encode(events) + "damaged\n")

        assertEquals(events, decoded)
    }

    @Test
    fun coalescesQueuedSnapshotsUntilTheSingleBackgroundWriterRuns() {
        val file = File.createTempFile("sky-command-diagnostics", ".v1").apply { delete() }
        val tasks = ArrayDeque<() -> Unit>()
        try {
            val store = AndroidDiagnosticStore.createForTesting(
                file = file,
                executor = DiagnosticBackgroundExecutor { task -> tasks.addLast(task) },
                log = DiagnosticLogWriter { },
            )
            val persistence = store.persistence()
            val first = DiagnosticEvent(1, DiagnosticLevel.INFO, "relay-gateway", "FIRST", "run-1", 1, null, "first")
            val latest = DiagnosticEvent(2, DiagnosticLevel.ERROR, "relay-gateway", "LATEST", "run-1", 2, null, "latest")

            persistence.persist(listOf(first)) { error("Unexpected persistence failure") }
            persistence.persist(listOf(latest)) { error("Unexpected persistence failure") }

            assertEquals(1, tasks.size)
            tasks.removeFirst().invoke()
            assertEquals(listOf(latest), DiagnosticEventFileCodec.decode(file.readText()))
            assertEquals(0, tasks.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun reportsBackgroundSchedulingFailureWithoutThrowingToTheCaller() {
        val file = File.createTempFile("sky-command-diagnostics", ".v1").apply { delete() }
        var failureReported = false
        try {
            val store = AndroidDiagnosticStore.createForTesting(
                file = file,
                executor = DiagnosticBackgroundExecutor { error("executor unavailable") },
                log = DiagnosticLogWriter { },
            )
            val event = DiagnosticEvent(1, DiagnosticLevel.ERROR, "relay-gateway", "WRITE_FAILED", "run-1", 1, null, "safe")

            store.persistence().persist(listOf(event)) { failureReported = true }

            assertTrue(failureReported)
        } finally {
            file.delete()
        }
    }
}
