package com.skycommand.relay.device.state

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class SdkAvailability {
    STOPPED,
    STARTING,
    READY,
    FAILED,
}

enum class LinkState {
    DISCONNECTED,
    CONNECTED,
}

enum class PairingState {
    UNKNOWN,
    IDLE,
    PAIRING,
    PAIRED,
    STOPPING,
    FAILED,
}

data class DeviceSnapshot(
    val revision: Long,
    val sdkAvailability: SdkAvailability,
    val remoteController: LinkState,
    val aircraft: LinkState,
    val flightController: LinkState,
    val pairing: PairingState,
    val remoteControllerModel: String?,
    val aircraftModel: String?,
)

data class DeviceObservation(
    val sourceRevision: Long,
    val sdkAvailability: SdkAvailability,
    val remoteController: LinkState,
    val aircraft: LinkState,
    val flightController: LinkState,
    val pairing: PairingState,
    val remoteControllerModel: String?,
    val aircraftModel: String?,
)

enum class DeviceStateSource {
    SDK,
    REMOTE_CONTROLLER,
    AIRCRAFT,
    PAIRING,
}

class DeviceStatePatch private constructor(
    val source: DeviceStateSource,
    val sourceRevision: Long,
    val sdkAvailability: SdkAvailability? = null,
    val remoteController: LinkState? = null,
    val remoteControllerModel: String? = null,
    val aircraft: LinkState? = null,
    val flightController: LinkState? = null,
    val aircraftModel: String? = null,
    val pairing: PairingState? = null,
) {
    companion object {
        fun sdk(
            sourceRevision: Long,
            availability: SdkAvailability,
        ): DeviceStatePatch = DeviceStatePatch(
            source = DeviceStateSource.SDK,
            sourceRevision = sourceRevision,
            sdkAvailability = availability,
        )

        fun remoteController(
            sourceRevision: Long,
            link: LinkState,
            model: String?,
        ): DeviceStatePatch = DeviceStatePatch(
            source = DeviceStateSource.REMOTE_CONTROLLER,
            sourceRevision = sourceRevision,
            remoteController = link,
            remoteControllerModel = model,
        )

        fun aircraft(
            sourceRevision: Long,
            aircraft: LinkState,
            flightController: LinkState,
            model: String?,
        ): DeviceStatePatch = DeviceStatePatch(
            source = DeviceStateSource.AIRCRAFT,
            sourceRevision = sourceRevision,
            aircraft = aircraft,
            flightController = flightController,
            aircraftModel = model,
        )

        fun pairing(sourceRevision: Long, pairing: PairingState): DeviceStatePatch = DeviceStatePatch(
            source = DeviceStateSource.PAIRING,
            sourceRevision = sourceRevision,
            pairing = pairing,
        )
    }
}

data class DeviceStateEvent(
    val previous: DeviceSnapshot,
    val current: DeviceSnapshot,
)

fun interface DeviceStateListener {
    fun onChanged(event: DeviceStateEvent)
}

fun interface Registration {
    fun unregister()
}

fun interface DeviceStateDiagnosticSink {
    fun record(diagnostic: DeviceStateDiagnostic)
}

data class DeviceStateDiagnostic(
    val kind: DeviceStateDiagnosticKind,
)

enum class DeviceStateDiagnosticKind {
    LISTENER_FAILURE,
}

sealed interface ApplyResult {
    data class Applied(val snapshot: DeviceSnapshot) : ApplyResult

    data class IgnoredStale(val sourceRevision: Long) : ApplyResult
}

class DeviceStateStore private constructor(
    private val diagnosticSink: DeviceStateDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var current = initialSnapshot()
    private val sourceRevisions = mutableMapOf<DeviceStateSource, Long>()
    private val listeners = mutableListOf<ListenerSlot>()
    private val pendingEvents = ArrayDeque<PendingEvent>()
    private var draining = false

    fun apply(observation: DeviceObservation): ApplyResult {
        validate(observation)
        var appliedSnapshot: DeviceSnapshot? = null
        val shouldDrain = lock.withLock {
            if (observation.sourceRevision <= current.revision) {
                return ApplyResult.IgnoredStale(observation.sourceRevision)
            }

            val previous = current
            current = observation.toSnapshot()
            appliedSnapshot = current
            pendingEvents.addLast(PendingEvent(DeviceStateEvent(previous, current), listeners.toList()))
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (shouldDrain) {
            drain()
        }
        return ApplyResult.Applied(requireNotNull(appliedSnapshot))
    }

    fun apply(patch: DeviceStatePatch): ApplyResult = commitPatch(patch, localPairing = false)

    fun applyPairing(pairing: PairingState): ApplyResult = commitPatch(
        DeviceStatePatch.pairing(sourceRevision = 1, pairing = pairing),
        localPairing = true,
    )

    fun applySdk(availability: SdkAvailability): ApplyResult = commitPatch(
        DeviceStatePatch.sdk(sourceRevision = 1, availability = availability),
        localSdk = true,
    )

    fun markRuntimeUnavailable(): ApplyResult {
        var appliedSnapshot: DeviceSnapshot? = null
        val shouldDrain = lock.withLock {
            val previous = current
            current = current.copy(
                revision = current.revision + 1,
                sdkAvailability = SdkAvailability.STOPPED,
                remoteController = LinkState.DISCONNECTED,
                aircraft = LinkState.DISCONNECTED,
                flightController = LinkState.DISCONNECTED,
                pairing = PairingState.UNKNOWN,
                remoteControllerModel = null,
                aircraftModel = null,
            )
            DeviceStateSource.entries.forEach { source ->
                sourceRevisions[source] = (sourceRevisions[source] ?: 0) + 1
            }
            appliedSnapshot = current
            pendingEvents.addLast(PendingEvent(DeviceStateEvent(previous, current), listeners.toList()))
            if (draining) false else {
                draining = true
                true
            }
        }
        if (shouldDrain) drain()
        return ApplyResult.Applied(requireNotNull(appliedSnapshot))
    }

    private fun commitPatch(
        patch: DeviceStatePatch,
        localPairing: Boolean = false,
        localSdk: Boolean = false,
    ): ApplyResult {
        validate(patch)
        var appliedSnapshot: DeviceSnapshot? = null
        val shouldDrain = lock.withLock {
            val previousSourceRevision = sourceRevisions[patch.source] ?: 0
            val sourceRevision = if (localSdk) previousSourceRevision + 1 else patch.sourceRevision
            if (!localPairing && sourceRevision <= previousSourceRevision) {
                return ApplyResult.IgnoredStale(sourceRevision)
            }
            val previous = current
            current = when (patch.source) {
                DeviceStateSource.SDK -> current.copy(
                    revision = current.revision + 1,
                    sdkAvailability = requireNotNull(patch.sdkAvailability),
                )

                DeviceStateSource.REMOTE_CONTROLLER -> current.copy(
                    revision = current.revision + 1,
                    remoteController = requireNotNull(patch.remoteController),
                    remoteControllerModel = patch.remoteControllerModel,
                )

                DeviceStateSource.AIRCRAFT -> current.copy(
                    revision = current.revision + 1,
                    aircraft = requireNotNull(patch.aircraft),
                    flightController = requireNotNull(patch.flightController),
                    aircraftModel = patch.aircraftModel,
                )

                DeviceStateSource.PAIRING -> current.copy(
                    revision = current.revision + 1,
                    pairing = requireNotNull(patch.pairing),
                )
            }
            if (!localPairing) {
                sourceRevisions[patch.source] = sourceRevision
            }
            appliedSnapshot = current
            pendingEvents.addLast(PendingEvent(DeviceStateEvent(previous, current), listeners.toList()))
            if (draining) false else {
                draining = true
                true
            }
        }
        if (shouldDrain) drain()
        return ApplyResult.Applied(requireNotNull(appliedSnapshot))
    }

    fun snapshot(): DeviceSnapshot = lock.withLock { current }

    fun onChanged(listener: DeviceStateListener): Registration {
        val slot = ListenerSlot(listener)
        lock.withLock { listeners += slot }
        return Registration {
            if (slot.deactivate()) {
                lock.withLock { listeners.remove(slot) }
            }
        }
    }

    private fun drain() {
        while (true) {
            val pending = lock.withLock {
                if (pendingEvents.isEmpty()) {
                    draining = false
                    null
                } else {
                    pendingEvents.removeFirst()
                }
            } ?: return

            pending.listeners.forEach { listener ->
                try {
                    listener.onChanged(pending.event)
                } catch (_: Throwable) {
                    runCatching {
                        diagnosticSink.record(DeviceStateDiagnostic(DeviceStateDiagnosticKind.LISTENER_FAILURE))
                    }
                }
            }
        }
    }

    private class ListenerSlot(
        private val delegate: DeviceStateListener,
    ) : DeviceStateListener {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private val callbackDepth = ThreadLocal.withInitial { 0 }
        private var active = true
        private var inFlight = 0

        fun deactivate(): Boolean {
            var restoreInterrupt = false
            lock.lock()
            try {
                val changed = active
                active = false
                if (callbackDepth.get() == 0) {
                    while (inFlight > 0) {
                        try {
                            idle.await()
                        } catch (_: InterruptedException) {
                            restoreInterrupt = true
                        }
                    }
                }
                return changed
            } finally {
                lock.unlock()
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        override fun onChanged(event: DeviceStateEvent) {
            val deliver = lock.withLock {
                if (active) {
                    inFlight += 1
                    true
                } else {
                    false
                }
            }
            if (!deliver) return

            val previousDepth = callbackDepth.get()
            callbackDepth.set(previousDepth + 1)
            try {
                delegate.onChanged(event)
            } finally {
                if (previousDepth == 0) callbackDepth.remove() else callbackDepth.set(previousDepth)
                lock.withLock {
                    inFlight -= 1
                    if (inFlight == 0) idle.signalAll()
                }
            }
        }
    }

    private data class PendingEvent(
        val event: DeviceStateEvent,
        val listeners: List<ListenerSlot>,
    )

    companion object {
        fun create(diagnosticSink: DeviceStateDiagnosticSink = DeviceStateDiagnosticSink { }): DeviceStateStore =
            DeviceStateStore(diagnosticSink)

        private fun initialSnapshot() = DeviceSnapshot(
            revision = 0,
            sdkAvailability = SdkAvailability.STOPPED,
            remoteController = LinkState.DISCONNECTED,
            aircraft = LinkState.DISCONNECTED,
            flightController = LinkState.DISCONNECTED,
            pairing = PairingState.UNKNOWN,
            remoteControllerModel = null,
            aircraftModel = null,
        )

        private fun validate(observation: DeviceObservation) {
            require(observation.sourceRevision > 0) { "Observation revision must be positive" }
            validateAircraftConnection(observation.aircraft, observation.flightController)
            validateModel(observation.remoteControllerModel)
            validateModel(observation.aircraftModel)
        }

        private fun validate(patch: DeviceStatePatch) {
            require(patch.sourceRevision > 0) { "Observation revision must be positive" }
            when (patch.source) {
                DeviceStateSource.SDK -> requireNotNull(patch.sdkAvailability)
                DeviceStateSource.REMOTE_CONTROLLER -> requireNotNull(patch.remoteController)
                DeviceStateSource.AIRCRAFT -> {
                    requireNotNull(patch.aircraft)
                    requireNotNull(patch.flightController)
                    validateAircraftConnection(patch.aircraft, patch.flightController)
                }

                DeviceStateSource.PAIRING -> requireNotNull(patch.pairing)
            }
            validateModel(patch.remoteControllerModel)
            validateModel(patch.aircraftModel)
        }

        private fun validateModel(model: String?) {
            if (model == null) return
            require(model.isNotBlank()) { "Device model must not be blank" }
            require(model.codePointCount(0, model.length) <= 128) { "Device model is too long" }
            require(model.none(Char::isISOControl)) { "Device model contains a control character" }
        }

        private fun validateAircraftConnection(aircraft: LinkState, flightController: LinkState) {
            require(aircraft == LinkState.CONNECTED || flightController == LinkState.DISCONNECTED) {
                "Flight controller cannot be connected when aircraft is disconnected"
            }
        }

        private fun DeviceObservation.toSnapshot() = DeviceSnapshot(
            revision = sourceRevision,
            sdkAvailability = sdkAvailability,
            remoteController = remoteController,
            aircraft = aircraft,
            flightController = flightController,
            pairing = pairing,
            remoteControllerModel = remoteControllerModel,
            aircraftModel = aircraftModel,
        )
    }
}
