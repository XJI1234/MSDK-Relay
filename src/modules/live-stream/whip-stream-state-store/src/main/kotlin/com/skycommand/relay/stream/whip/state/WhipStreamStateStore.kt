package com.skycommand.relay.stream.whip.state

import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class WhipStreamLifecycle {
    IDLE,
    CONNECTING,
    PUBLISHING,
    STOPPING,
    FAILED,
    DISCONNECTED,
}

enum class WhipVideoCodec {
    H264,
}

enum class WhipStreamFailure {
    NETWORK,
    SIGNALING,
    ICE,
    TIMEOUT,
    CANCELLED,
    UNSUPPORTED_CODEC,
    INTERNAL,
    DISCONNECTED,
}

enum class WhipStreamNotice {
    NONE,
    CONNECTING,
    PUBLISHING,
    STOPPING,
    FAILED,
    DISCONNECTED,
}

data class WhipStreamMetrics(
    val codec: WhipVideoCodec = WhipVideoCodec.H264,
    val resolution: String? = null,
    val fps: Double? = null,
    val bitrateKbps: Double? = null,
    val rttMillis: Long? = null,
)

data class WhipDeviceSnapshot(
    val deviceId: String,
    val revision: Long,
    val state: WhipStreamLifecycle,
    val targetConfigured: Boolean,
    val notice: WhipStreamNotice,
    val failure: WhipStreamFailure?,
    val metrics: WhipStreamMetrics?,
)

sealed interface WhipStartResult {
    data class Accepted(val operationId: Long) : WhipStartResult

    data class Rejected(val reason: WhipStartRejection) : WhipStartResult
}

enum class WhipStartRejection {
    INVALID_DEVICE_ID,
    ALREADY_ACTIVE,
}

sealed interface WhipStopResult {
    data class Accepted(val operationId: Long) : WhipStopResult

    data class Rejected(val reason: WhipStopRejection) : WhipStopResult
}

enum class WhipStopRejection {
    INVALID_DEVICE_ID,
    NO_ACTIVE_STREAM,
    ALREADY_STOPPING,
}

sealed interface WhipUpdateResult {
    data class Applied(val snapshot: WhipDeviceSnapshot) : WhipUpdateResult

    data class IgnoredStale(val operationId: Long) : WhipUpdateResult
}

data class WhipStreamStateEvent(
    val previous: WhipDeviceSnapshot,
    val current: WhipDeviceSnapshot,
)

fun interface WhipStreamStateListener {
    fun onChanged(event: WhipStreamStateEvent)
}

fun interface WhipStreamStateRegistration {
    fun unregister()
}

fun interface WhipStreamStateDiagnosticSink {
    fun record(kind: WhipStreamStateDiagnosticKind)
}

enum class WhipStreamStateDiagnosticKind {
    LISTENER_FAILURE,
}

class WhipStreamStateStore private constructor(
    private val diagnosticSink: WhipStreamStateDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private val devices = linkedMapOf<String, MutableDevice>()
    private var nextOperationId = 0L
    private val listeners = mutableListOf<ListenerSlot>()
    private val pendingEvents = ArrayDeque<PendingEvent>()
    private var draining = false

    fun requestStart(deviceId: String, config: ValidatedWhipStreamConfig): WhipStartResult {
        if (!isValidDeviceId(deviceId)) return WhipStartResult.Rejected(WhipStartRejection.INVALID_DEVICE_ID)
        require(config.whipUrl.isNotBlank()) { "Validated WHIP URL must not be blank" }

        var shouldDrain = false
        val result = lock.withLock {
            val device = devices.getOrPut(deviceId) { MutableDevice() }
            if (device.state in ACTIVE_STATES) {
                WhipStartResult.Rejected(WhipStartRejection.ALREADY_ACTIVE)
            } else {
                val operationId = nextOperationId()
                val previous = device.snapshot(deviceId)
                device.operationId = operationId
                device.state = WhipStreamLifecycle.CONNECTING
                device.targetConfigured = true
                device.notice = WhipStreamNotice.CONNECTING
                device.failure = null
                device.metrics = null
                val current = device.commit(previous.revision + 1, deviceId)
                shouldDrain = enqueue(WhipStreamStateEvent(previous, current))
                WhipStartResult.Accepted(operationId)
            }
        }
        if (shouldDrain) drain()
        return result
    }

    fun requestStop(deviceId: String): WhipStopResult {
        if (!isValidDeviceId(deviceId)) return WhipStopResult.Rejected(WhipStopRejection.INVALID_DEVICE_ID)

        var shouldDrain = false
        val result = lock.withLock {
            val device = devices[deviceId]
            when {
                device == null || device.state == WhipStreamLifecycle.IDLE ||
                    device.state == WhipStreamLifecycle.FAILED ||
                    device.state == WhipStreamLifecycle.DISCONNECTED ->
                    WhipStopResult.Rejected(WhipStopRejection.NO_ACTIVE_STREAM)

                device.state == WhipStreamLifecycle.STOPPING ->
                    WhipStopResult.Rejected(WhipStopRejection.ALREADY_STOPPING)

                else -> {
                    val operationId = nextOperationId()
                    val previous = device.snapshot(deviceId)
                    device.operationId = operationId
                    device.state = WhipStreamLifecycle.STOPPING
                    device.notice = WhipStreamNotice.STOPPING
                    device.failure = null
                    device.metrics = null
                    val current = device.commit(previous.revision + 1, deviceId)
                    shouldDrain = enqueue(WhipStreamStateEvent(previous, current))
                    WhipStopResult.Accepted(operationId)
                }
            }
        }
        if (shouldDrain) drain()
        return result
    }

    fun markPublishing(
        deviceId: String,
        operationId: Long,
        metrics: WhipStreamMetrics? = null,
    ): WhipUpdateResult {
        validateMetrics(metrics)
        return transition(deviceId, operationId, setOf(WhipStreamLifecycle.CONNECTING)) { device ->
            device.state = WhipStreamLifecycle.PUBLISHING
            device.notice = WhipStreamNotice.PUBLISHING
            device.failure = null
            device.metrics = metrics
        }
    }

    fun markStopped(deviceId: String, operationId: Long): WhipUpdateResult =
        transition(deviceId, operationId, setOf(WhipStreamLifecycle.STOPPING)) { device ->
            device.operationId = null
            device.state = WhipStreamLifecycle.IDLE
            device.targetConfigured = false
            device.notice = WhipStreamNotice.NONE
            device.failure = null
            device.metrics = null
        }

    fun markFailed(
        deviceId: String,
        operationId: Long,
        failure: WhipStreamFailure,
    ): WhipUpdateResult = transition(deviceId, operationId, ACTIVE_STATES) { device ->
        device.operationId = null
        device.state = WhipStreamLifecycle.FAILED
        device.targetConfigured = false
        device.notice = WhipStreamNotice.FAILED
        device.failure = failure
        device.metrics = null
    }

    fun markDisconnected(deviceId: String, operationId: Long): WhipUpdateResult =
        transition(deviceId, operationId, ACTIVE_STATES) { device ->
            device.operationId = null
            device.state = WhipStreamLifecycle.DISCONNECTED
            device.targetConfigured = false
            device.notice = WhipStreamNotice.DISCONNECTED
            device.failure = WhipStreamFailure.DISCONNECTED
            device.metrics = null
        }

    fun markDeviceUnavailable(deviceId: String): WhipDeviceSnapshot {
        require(isValidDeviceId(deviceId)) { "Device ID is invalid" }
        var shouldDrain = false
        val current = lock.withLock {
            val device = devices.getOrPut(deviceId) { MutableDevice() }
            val previous = device.snapshot(deviceId)
            device.operationId = null
            if (device.state != WhipStreamLifecycle.IDLE) {
                device.state = WhipStreamLifecycle.DISCONNECTED
                device.notice = WhipStreamNotice.DISCONNECTED
            } else {
                device.state = WhipStreamLifecycle.IDLE
                device.notice = WhipStreamNotice.NONE
            }
            device.targetConfigured = false
            device.failure = null
            device.metrics = null
            val next = device.commit(previous.revision + 1, deviceId)
            shouldDrain = enqueue(WhipStreamStateEvent(previous, next))
            next
        }
        if (shouldDrain) drain()
        return current
    }

    fun updateMetrics(deviceId: String, operationId: Long, metrics: WhipStreamMetrics): WhipUpdateResult {
        validateMetrics(metrics)
        return transition(deviceId, operationId, setOf(WhipStreamLifecycle.PUBLISHING)) { device ->
            device.metrics = metrics
        }
    }

    fun snapshot(deviceId: String): WhipDeviceSnapshot {
        require(isValidDeviceId(deviceId)) { "Device ID is invalid" }
        return lock.withLock { devices[deviceId]?.snapshot(deviceId) ?: initialSnapshot(deviceId) }
    }

    fun snapshots(): List<WhipDeviceSnapshot> = lock.withLock {
        Collections.unmodifiableList(
            devices.keys.sorted().map { deviceId -> devices.getValue(deviceId).snapshot(deviceId) },
        )
    }

    fun onChanged(listener: WhipStreamStateListener): WhipStreamStateRegistration {
        val slot = ListenerSlot(listener)
        lock.withLock { listeners += slot }
        return WhipStreamStateRegistration {
            if (slot.deactivate()) lock.withLock { listeners.remove(slot) }
        }
    }

    private fun transition(
        deviceId: String,
        operationId: Long,
        expectedStates: Set<WhipStreamLifecycle>,
        transform: (MutableDevice) -> Unit,
    ): WhipUpdateResult {
        var shouldDrain = false
        val result = lock.withLock {
            val device = devices[deviceId]
            if (device == null || device.operationId != operationId || device.state !in expectedStates) {
                WhipUpdateResult.IgnoredStale(operationId)
            } else {
                val previous = device.snapshot(deviceId)
                transform(device)
                val current = device.commit(previous.revision + 1, deviceId)
                shouldDrain = enqueue(WhipStreamStateEvent(previous, current))
                WhipUpdateResult.Applied(current)
            }
        }
        if (shouldDrain) drain()
        return result
    }

    private fun nextOperationId(): Long {
        nextOperationId += 1
        return nextOperationId
    }

    private fun enqueue(event: WhipStreamStateEvent): Boolean {
        pendingEvents.addLast(PendingEvent(event, listeners.toList()))
        if (draining) return false
        draining = true
        return true
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
                    runCatching { diagnosticSink.record(WhipStreamStateDiagnosticKind.LISTENER_FAILURE) }
                }
            }
        }
    }

    private class MutableDevice {
        var revision = 0L
        var state = WhipStreamLifecycle.IDLE
        var targetConfigured = false
        var notice = WhipStreamNotice.NONE
        var failure: WhipStreamFailure? = null
        var metrics: WhipStreamMetrics? = null
        var operationId: Long? = null

        fun snapshot(deviceId: String) = WhipDeviceSnapshot(
            deviceId = deviceId,
            revision = revision,
            state = state,
            targetConfigured = targetConfigured,
            notice = notice,
            failure = failure,
            metrics = metrics,
        )

        fun commit(nextRevision: Long, deviceId: String): WhipDeviceSnapshot {
            revision = nextRevision
            return snapshot(deviceId)
        }
    }

    private class ListenerSlot(
        private val delegate: WhipStreamStateListener,
    ) : WhipStreamStateListener {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private val callbackDepth = ThreadLocal.withInitial { 0 }
        private var active = true
        private var inFlight = 0

        fun deactivate(): Boolean {
            var interrupted = false
            lock.lock()
            try {
                val changed = active
                active = false
                if (callbackDepth.get() == 0) {
                    while (inFlight > 0) {
                        try {
                            idle.await()
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                }
                return changed
            } finally {
                lock.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        override fun onChanged(event: WhipStreamStateEvent) {
            if (!lock.withLock { if (active) { inFlight += 1; true } else false }) return
            val depth = callbackDepth.get()
            callbackDepth.set(depth + 1)
            try {
                delegate.onChanged(event)
            } finally {
                if (depth == 0) callbackDepth.remove() else callbackDepth.set(depth)
                lock.withLock {
                    inFlight -= 1
                    if (inFlight == 0) idle.signalAll()
                }
            }
        }
    }

    private data class PendingEvent(
        val event: WhipStreamStateEvent,
        val listeners: List<ListenerSlot>,
    )

    private fun validateMetrics(metrics: WhipStreamMetrics?) {
        if (metrics == null) return
        metrics.resolution?.let {
            require(it.isNotBlank() && it.codePointCount(0, it.length) <= 64 && it.none(Char::isISOControl))
        }
        metrics.fps?.let { require(it.isFinite() && it in 0.0..240.0) }
        metrics.bitrateKbps?.let { require(it.isFinite() && it in 0.0..1_000_000.0) }
        metrics.rttMillis?.let { require(it in 0..60_000) }
    }

    companion object {
        private val ACTIVE_STATES = setOf(
            WhipStreamLifecycle.CONNECTING,
            WhipStreamLifecycle.PUBLISHING,
            WhipStreamLifecycle.STOPPING,
        )

        fun create(
            diagnosticSink: WhipStreamStateDiagnosticSink = WhipStreamStateDiagnosticSink { },
        ): WhipStreamStateStore = WhipStreamStateStore(diagnosticSink)

        private fun initialSnapshot(deviceId: String) = WhipDeviceSnapshot(
            deviceId = deviceId,
            revision = 0,
            state = WhipStreamLifecycle.IDLE,
            targetConfigured = false,
            notice = WhipStreamNotice.NONE,
            failure = null,
            metrics = null,
        )

        private fun isValidDeviceId(value: String): Boolean =
            value.isNotBlank() && value.codePointCount(0, value.length) <= 128 && value.none(Char::isISOControl)
    }
}
