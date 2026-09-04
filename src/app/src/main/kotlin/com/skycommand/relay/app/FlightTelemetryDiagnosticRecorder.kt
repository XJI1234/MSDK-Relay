package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import kotlin.math.abs

/**
 * Records the flight facts needed to explain an accepted takeoff/landing action.
 * It observes snapshots only; it never reads DJI, changes telemetry, or controls a vehicle.
 */
internal class FlightTelemetryDiagnosticRecorder(
    private val journal: DiagnosticJournal,
) {
    private val lock = Any()
    private var previous = FlightTelemetrySnapshot()

    fun record(next: FlightTelemetrySnapshot) {
        val observation = synchronized(lock) {
            val before = previous
            previous = next
            FlightObservation(before, next)
        }
        val changed = observation.changedFacts()
        val altitudeChanged = observation.altitudeChanged()
        if (changed.isEmpty() && !altitudeChanged) return

        val changedText = buildList {
            addAll(changed)
            if (altitudeChanged) {
                add(
                    "FlightControllerKey.KeyAltitude=${value(observation.before.altitudeMeters)}->${value(observation.after.altitudeMeters)}",
                )
            }
        }.joinToString(",")
        journal.record(
            DiagnosticLevel.INFO,
            "telemetry",
            "FLIGHT_STATE_CHANGED",
            null,
            "sourceGeneration=${next.sourceGeneration};sourceRevision=${next.sourceRevision};changed=$changedText",
        )
    }

    fun reset() {
        synchronized(lock) { previous = FlightTelemetrySnapshot() }
    }

    private data class FlightObservation(
        val before: FlightTelemetrySnapshot,
        val after: FlightTelemetrySnapshot,
    ) {
        fun changedFacts(): List<String> = buildList {
            if (before.isFlying != after.isFlying) add(change("FlightControllerKey.KeyIsFlying", before.isFlying, after.isFlying))
            if (before.motorsOn != after.motorsOn) add(change("FlightControllerKey.KeyAreMotorsOn", before.motorsOn, after.motorsOn))
            if (before.flightMode != after.flightMode) add(change("FlightControllerKey.KeyFCFlightMode", before.flightMode, after.flightMode))
            if (before.landingConfirmationNeeded != after.landingConfirmationNeeded) {
                add(change("FlightControllerKey.KeyIsLandingConfirmationNeeded", before.landingConfirmationNeeded, after.landingConfirmationNeeded))
            }
            if (before.landingProtectionState != after.landingProtectionState) {
                add(change("FlightAssistantKey.KeyLandingProtectionState", before.landingProtectionState, after.landingProtectionState))
            }
        }

        fun altitudeChanged(): Boolean {
            if (before.altitudeMeters == after.altitudeMeters) return false
            val old = before.altitudeMeters
            val next = after.altitudeMeters
            return old == null || next == null || abs(next - old) >= ALTITUDE_LOG_STEP_METERS
        }

        private fun change(key: String, old: Any?, next: Any?): String =
            "$key=${value(old)}->${value(next)}"
    }

    private companion object {
        const val ALTITUDE_LOG_STEP_METERS = 0.5

        fun value(value: Any?): String = value?.toString() ?: "null"
    }
}
