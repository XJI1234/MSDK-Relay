package com.skycommand.relay.telemetry

import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.telemetry.command.TelemetryCommandHandler
import com.skycommand.relay.telemetry.command.SnapshotSource
import com.skycommand.relay.telemetry.command.TelemetryReadResult
import com.skycommand.relay.telemetry.publish.PublishTelemetryResult
import com.skycommand.relay.telemetry.publish.TelemetryPublisher
import com.skycommand.relay.telemetry.publish.TelemetrySink
import com.skycommand.relay.telemetry.snapshot.SnapshotAssembler
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.TelemetryInputs
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState
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

fun interface TelemetryRegistration {
    fun unregister()
}

interface TelemetryStateSource : SnapshotSource {
    fun onChanged(listener: () -> Unit): TelemetryRegistration
}

class Telemetry private constructor(
    private val source: TelemetryStateSource,
    sink: TelemetrySink,
) {
    private val lock = ReentrantLock()
    private val publisher = TelemetryPublisher.create(sink)
    private val commandHandler = TelemetryCommandHandler.create(source)
    private var generation = 0L
    private var activeGeneration: Long? = null
    private var registration: TelemetryRegistration? = null

    fun start(): TelemetryStartResult {
        val startedGeneration = lock.withLock {
            if (activeGeneration != null) return TelemetryStartResult.AlreadyStarted
            (++generation).also { activeGeneration = it }
        }
        val newRegistration = runCatching {
            source.onChanged { publishCurrent(startedGeneration) }
        }.getOrElse { failure ->
            lock.withLock {
                if (activeGeneration == startedGeneration) activeGeneration = null
            }
            throw failure
        }
        val stoppedWhileSubscribing = lock.withLock {
            if (activeGeneration == startedGeneration) {
                registration = newRegistration
                false
            } else {
                true
            }
        }
        if (stoppedWhileSubscribing) newRegistration.unregister()
        return TelemetryStartResult.Started
    }

    fun stop(): TelemetryStopResult {
        val currentRegistration = lock.withLock {
            if (activeGeneration == null) return TelemetryStopResult.AlreadyStopped
            activeGeneration = null
            registration.also { registration = null }
        }

        currentRegistration?.unregister()
        publisher.reset()
        return TelemetryStopResult.Stopped
    }

    fun read(): TelemetryReadResult = commandHandler.read()

    private fun publishCurrent(callbackGeneration: Long): PublishTelemetryResult = lock.withLock {
        if (activeGeneration != callbackGeneration) return PublishTelemetryResult.Rejected
        runCatching {
            publisher.publish(SnapshotAssembler.assemble(source.snapshot()))
        }.getOrElse { PublishTelemetryResult.Rejected }
    }

    companion object {
        fun create(source: TelemetryStateSource, sink: TelemetrySink): Telemetry = Telemetry(source, sink)

        fun create(store: DeviceStateStore, sink: TelemetrySink): Telemetry = Telemetry(DeviceStoreTelemetrySource(store), sink)
    }

    private class DeviceStoreTelemetrySource(private val store: DeviceStateStore) : TelemetryStateSource {
        override fun snapshot(): TelemetryInputs = TelemetryInputs(
            device = store.snapshot(),
            flight = FlightTelemetrySnapshot(),
            stream = StreamSnapshot(0, StreamLifecycleState.STOPPED, false, "Stopped", null),
            mission = MissionSnapshot(0, null, 0, null, UploadState.NOT_UPLOADED, ExecutionState.NOT_STARTED),
        )

        override fun onChanged(listener: () -> Unit): TelemetryRegistration {
            val registration = store.onChanged { listener() }
            return TelemetryRegistration { registration.unregister() }
        }
    }
}
