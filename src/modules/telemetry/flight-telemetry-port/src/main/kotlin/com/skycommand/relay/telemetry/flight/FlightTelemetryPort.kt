package com.skycommand.relay.telemetry.flight

import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot

fun interface FlightTelemetryRegistration {
    fun unregister()
}

interface FlightTelemetrySource {
    fun snapshot(): FlightTelemetrySnapshot
    fun onChanged(listener: () -> Unit): FlightTelemetryRegistration
    fun close()
}
