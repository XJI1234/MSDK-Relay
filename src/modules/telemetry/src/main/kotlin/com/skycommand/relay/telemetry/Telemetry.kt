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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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

/** Result of accepting the latest state for asynchronous desktop publication. */
sealed interface TelemetryPublicationRequestResult {
    /** The active telemetry generation accepted the request. It is not a network delivery receipt. */
    data object Queued : TelemetryPublicationRequestResult

    /** Telemetry is stopped, so no snapshot can be published. */
    data object Rejected : TelemetryPublicationRequestResult
}

fun interface TelemetryRegistration {
    fun unregister()
}

interface TelemetryStateSource : SnapshotSource {
    fun onChanged(listener: () -> Unit): TelemetryRegistration
}

/** Schedules replaceable telemetry publication work away from DJI and UI callbacks. */
fun interface TelemetryPublicationExecutor {
    fun execute(task: () -> Unit)
}

class Telemetry private constructor(
    private val source: TelemetryStateSource,
    sink: TelemetrySink,
    publicationExecutor: TelemetryPublicationExecutor,
) {
    private val lock = ReentrantLock()
    private val publisher = TelemetryPublisher.create(sink)
    private val commandHandler = TelemetryCommandHandler.create(source)
    private val publication = LatestTelemetryPublication(
        publicationExecutor,
        publisher::reset,
        ::publishLatest,
    )
    private var generation = 0L
    private var activeGeneration: Long? = null
    private var registration: TelemetryRegistration? = null

    fun start(): TelemetryStartResult {
        val startedGeneration = lock.withLock {
            if (activeGeneration != null) return TelemetryStartResult.AlreadyStarted
            (++generation).also { activeGeneration = it }
        }
        publication.activate(startedGeneration)
        val newRegistration = runCatching {
            source.onChanged { publication.request(startedGeneration) }
        }.getOrElse { failure ->
            lock.withLock {
                if (activeGeneration == startedGeneration) activeGeneration = null
            }
            publication.deactivate(startedGeneration)
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
        val stopped = lock.withLock {
            if (activeGeneration == null) return TelemetryStopResult.AlreadyStopped
            val stoppedGeneration = activeGeneration
            activeGeneration = null
            StoppedTelemetry(registration.also { registration = null }, checkNotNull(stoppedGeneration))
        }

        publication.deactivate(stopped.generation)
        stopped.registration?.unregister()
        return TelemetryStopResult.Stopped
    }

    /** Makes the next current snapshot publishable to a newly established relay session. */
    fun resetPublicationBaseline() = publication.reset()

    fun read(): TelemetryReadResult = commandHandler.read()

    fun publishCurrent(): TelemetryPublicationRequestResult {
        val currentGeneration = lock.withLock { activeGeneration } ?: return TelemetryPublicationRequestResult.Rejected
        publication.request(currentGeneration)
        return TelemetryPublicationRequestResult.Queued
    }

    private fun publishLatest(callbackGeneration: Long) {
        if (!isActive(callbackGeneration)) return
        val inputs = runCatching { source.snapshot() }.getOrNull() ?: return
        val snapshot = runCatching { SnapshotAssembler.assemble(inputs) }.getOrNull() ?: return
        if (!isActive(callbackGeneration)) return
        runCatching { publisher.publish(snapshot) }
    }

    private fun isActive(candidateGeneration: Long): Boolean =
        lock.withLock { activeGeneration == candidateGeneration }

    private data class StoppedTelemetry(
        val registration: TelemetryRegistration?,
        val generation: Long,
    )

    companion object {
        fun create(
            source: TelemetryStateSource,
            sink: TelemetrySink,
            publicationExecutor: TelemetryPublicationExecutor = BackgroundTelemetryPublicationExecutor,
        ): Telemetry = Telemetry(source, sink, publicationExecutor)

        fun create(
            store: DeviceStateStore,
            sink: TelemetrySink,
            publicationExecutor: TelemetryPublicationExecutor = BackgroundTelemetryPublicationExecutor,
        ): Telemetry = Telemetry(DeviceStoreTelemetrySource(store), sink, publicationExecutor)
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

/**
 * Holds at most one replaceable state publication while the sink is busy. The active worker
 * never executes on a DJI KeyManager or Android UI callback thread.
 */
private class LatestTelemetryPublication(
    private val executor: TelemetryPublicationExecutor,
    private val reset: () -> Unit,
    private val publish: (Long) -> Unit,
) {
    private val lock = ReentrantLock()
    private var activeGeneration: Long? = null
    private var latestGeneration: Long? = null
    private var resetRequested = false
    private var scheduled = false

    fun activate(generation: Long) {
        val shouldSchedule = lock.withLock {
            activeGeneration = generation
            latestGeneration = null
            resetRequested = true
            scheduleLocked()
        }
        if (shouldSchedule) submit()
    }

    fun deactivate(generation: Long) {
        lock.withLock {
            if (activeGeneration == generation) {
                activeGeneration = null
                latestGeneration = null
            }
        }
    }

    fun reset() {
        val shouldSchedule = lock.withLock {
            if (activeGeneration == null) return
            resetRequested = true
            scheduleLocked()
        }
        if (shouldSchedule) submit()
    }

    fun request(generation: Long) {
        val shouldSchedule = lock.withLock {
            if (activeGeneration != generation) return
            latestGeneration = generation
            scheduleLocked()
        }
        if (shouldSchedule) submit()
    }

    private fun scheduleLocked(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }

    private fun submit() {
        runCatching { executor.execute(::drain) }
            .onFailure {
                lock.withLock {
                    scheduled = false
                    latestGeneration = null
                }
            }
    }

    private fun drain() {
        val work = lock.withLock {
            val generation = activeGeneration
            val shouldReset = resetRequested
            resetRequested = false
            val shouldPublish = generation != null && latestGeneration == generation
            latestGeneration = null
            if (generation == null && !shouldReset) null else PublicationWork(generation, shouldReset, shouldPublish)
        }
        if (work != null) {
            if (work.shouldReset) runCatching(reset)
            if (work.shouldPublish) work.generation?.let(publish)
        }
        val shouldSchedule = lock.withLock {
            if (activeGeneration == null) latestGeneration = null
            if (resetRequested || latestGeneration != null) {
                true
            } else {
                scheduled = false
                false
            }
        }
        if (shouldSchedule) submit()
    }

    private data class PublicationWork(
        val generation: Long?,
        val shouldReset: Boolean,
        val shouldPublish: Boolean,
    )
}

private object BackgroundTelemetryPublicationExecutor : TelemetryPublicationExecutor {
    private val delegate: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "sky-command-telemetry").apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit) {
        delegate.execute(task)
    }
}
