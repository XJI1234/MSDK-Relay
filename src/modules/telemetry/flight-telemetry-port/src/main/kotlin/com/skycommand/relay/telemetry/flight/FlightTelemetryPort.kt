package com.skycommand.relay.telemetry.flight

import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot

fun interface FlightTelemetryRegistration {
    fun unregister()
}

interface FlightTelemetrySource {
    fun snapshot(): FlightTelemetrySnapshot
    fun onChanged(listener: () -> Unit): FlightTelemetryRegistration
    /** Drops only facts owned by FlightControllerKey after a confirmed controller disconnect. */
    fun invalidateFlightControllerFacts()
    /** Starts a fresh FlightControllerKey observation generation after the controller reconnects. */
    fun refreshFlightControllerFacts()
    fun close()
}
