package com.skycommand.relay.app

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.telemetry.TelemetryRegistration
import com.skycommand.relay.telemetry.TelemetryStateSource
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.TelemetryInputs
import com.skycommand.relay.wayline.state.MissionSnapshot
import java.util.concurrent.atomic.AtomicBoolean

interface SnapshotFeed<T> {
    fun snapshot(): T
    fun onChanged(listener: () -> Unit): CloseableRegistration
}

class CompositeTelemetrySource(
    private val device: SnapshotFeed<DeviceSnapshot>,
    private val flight: SnapshotFeed<FlightTelemetrySnapshot>,
    private val stream: SnapshotFeed<StreamSnapshot>,
    private val mission: SnapshotFeed<MissionSnapshot>,
) : TelemetryStateSource {
    override fun snapshot(): TelemetryInputs = TelemetryInputs(
        device.snapshot(),
        flight.snapshot(),
        stream.snapshot(),
        mission.snapshot(),
    )

    override fun onChanged(listener: () -> Unit): TelemetryRegistration {
        val registrations = mutableListOf<CloseableRegistration>()
        try {
            registrations += device.onChanged(listener)
            registrations += flight.onChanged(listener)
            registrations += stream.onChanged(listener)
            registrations += mission.onChanged(listener)
        } catch (failure: Exception) {
            registrations.asReversed().forEach { runCatching { it.unregister() } }
            throw failure
        }
        val active = AtomicBoolean(true)
        return TelemetryRegistration {
            if (active.compareAndSet(true, false)) {
                registrations.asReversed().forEach { runCatching { it.unregister() } }
            }
        }
    }
}
