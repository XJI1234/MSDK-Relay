package com.skycommand.relay.telemetry

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.Registration
import com.skycommand.relay.telemetry.command.TelemetryCommandHandler
import com.skycommand.relay.telemetry.command.TelemetryReadResult
import com.skycommand.relay.telemetry.publish.PublishTelemetryResult
import com.skycommand.relay.telemetry.publish.TelemetryPublisher
import com.skycommand.relay.telemetry.publish.TelemetrySink
import com.skycommand.relay.telemetry.snapshot.SnapshotAssembler
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface TelemetryStartResult {
    data object Started : TelemetryStartResult

    data object AlreadyStarted : TelemetryStartResult
}

sealed interface TelemetryStopResult {
    data object Stopped : TelemetryStopResult

    data object AlreadyStopped : TelemetryStopResult
}

class Telemetry private constructor(
    private val store: DeviceStateStore,
    sink: TelemetrySink,
) {
    private val lock = ReentrantLock()
    private val publisher = TelemetryPublisher.create(sink)
    private val commandHandler = TelemetryCommandHandler.create { store.snapshot() }
    private var registration: Registration? = null

    fun start(): TelemetryStartResult {
        lock.withLock {
            if (registration != null) return TelemetryStartResult.AlreadyStarted
            registration = store.onChanged { event -> publish(event.current) }
            return TelemetryStartResult.Started
        }
    }

    fun stop(): TelemetryStopResult {
        val currentRegistration = lock.withLock {
            registration.also { registration = null }
        } ?: return TelemetryStopResult.AlreadyStopped

        currentRegistration.unregister()
        publisher.reset()
        return TelemetryStopResult.Stopped
    }

    fun read(): TelemetryReadResult = commandHandler.read()

    private fun publish(snapshot: DeviceSnapshot): PublishTelemetryResult =
        publisher.publish(SnapshotAssembler.assemble(snapshot))

    companion object {
        fun create(store: DeviceStateStore, sink: TelemetrySink): Telemetry = Telemetry(store, sink)
    }
}
