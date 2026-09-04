package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticClock
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightTelemetryDiagnosticRecorderTest {
    @Test
    fun recordsLandingFactsWithTheirMsdkKeysAndObservationMetadata() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        val recorder = FlightTelemetryDiagnosticRecorder(journal)

        recorder.record(
            FlightTelemetrySnapshot(
                isFlying = true,
                motorsOn = true,
                flightMode = "AUTO_LANDING",
                altitudeMeters = 2.0,
                landingConfirmationNeeded = false,
                landingProtectionState = "NONE",
                sourceGeneration = 3,
                sourceRevision = 14,
            ),
        )

        val event = journal.pending(32).single()
        assertEquals("telemetry", event.module)
        assertEquals("FLIGHT_STATE_CHANGED", event.eventCode)
        assertTrue(event.safeDetail.contains("sourceGeneration=3;sourceRevision=14"))
        assertTrue(event.safeDetail.contains("FlightControllerKey.KeyIsFlying=null->true"))
        assertTrue(event.safeDetail.contains("FlightControllerKey.KeyFCFlightMode=null->AUTO_LANDING"))
        assertTrue(event.safeDetail.contains("FlightControllerKey.KeyAltitude=null->2.0"))
        assertTrue(event.safeDetail.contains("FlightAssistantKey.KeyLandingProtectionState=null->NONE"))
    }

    @Test
    fun ignoresSmallAltitudeOnlyJitterButRecordsMeaningfulDescentAndDiscreteChanges() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        val recorder = FlightTelemetryDiagnosticRecorder(journal)
        recorder.record(FlightTelemetrySnapshot(isFlying = true, altitudeMeters = 3.0))
        journal.acknowledge("run-1", 1)

        recorder.record(FlightTelemetrySnapshot(isFlying = true, altitudeMeters = 2.8))
        assertEquals(emptyList(), journal.pending(32))

        recorder.record(FlightTelemetrySnapshot(isFlying = true, altitudeMeters = 2.2))
        assertEquals(1, journal.pending(32).size)
        assertTrue(journal.pending(32).single().safeDetail.contains("FlightControllerKey.KeyAltitude=2.8->2.2"))

        recorder.record(FlightTelemetrySnapshot(isFlying = true, flightMode = "AUTO_LANDING", altitudeMeters = 2.3))
        assertEquals(2, journal.pending(32).size)
        assertTrue(journal.pending(32).last().safeDetail.contains("FlightControllerKey.KeyFCFlightMode=null->AUTO_LANDING"))
    }

    @Test
    fun resetStartsANewObservationBaseline() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        val recorder = FlightTelemetryDiagnosticRecorder(journal)
        recorder.record(FlightTelemetrySnapshot(isFlying = true))
        recorder.reset()
        recorder.record(FlightTelemetrySnapshot(isFlying = true))

        assertEquals(2, journal.pending(32).size)
        assertTrue(journal.pending(32).last().safeDetail.contains("KeyIsFlying=null->true"))
    }

    private object FixedClock : DiagnosticClock {
        override fun currentTimeMillis(): Long = 0
    }
}
