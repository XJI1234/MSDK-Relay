package com.skycommand.relay.telemetry.flight

import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot

fun interface FlightTelemetryRegistration {
    fun unregister()
}

interface FlightTelemetrySource {
    fun snapshot(): FlightTelemetrySnapshot
    fun onChanged(listener: () -> Unit): FlightTelemetryRegistration
    /** Drops facts captured before a confirmed flight-controller disconnect. */
    fun invalidate()
    /** Starts a fresh hardware observation generation after the controller reconnects. */
    fun refresh()
    fun close()
}
