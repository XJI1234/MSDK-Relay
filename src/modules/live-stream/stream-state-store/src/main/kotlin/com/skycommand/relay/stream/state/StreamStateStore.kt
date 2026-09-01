package com.skycommand.relay.stream.state

import com.skycommand.relay.stream.config.ValidatedStreamConfig
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class StreamLifecycleState {
    STOPPED,
    STARTING,
    STREAMING,
    STOPPING,
    FAILED,
}

data class StreamMetrics(
    val resolution: String? = null,
    val fps: Double? = null,
    val videoBitrateKbps: Double? = null,
    val rttMillis: Long? = null,
    /** Raw MSDK LiveStreamStatus.packetLoss value; its unit is intentionally not inferred. */
    val packetLoss: Long? = null,
    /** Raw MSDK LiveStreamStatus.packetCacheLen value; its unit is intentionally not inferred. */
    val packetCacheLength: Long? = null,
)

data class StreamSnapshot(
    val revision: Long,
    val state: StreamLifecycleState,
    val targetConfigured: Boolean,
    val notice: String,
    val metrics: StreamMetrics?,
)

sealed interface StreamStartResult {
    data class Accepted(val operationId: Long) : StreamStartResult

    data class Rejected(val reason: StreamStartRejection) : StreamStartResult
}

enum class StreamStartRejection {
    ALREADY_ACTIVE,
}

sealed interface StreamStopResult {
    data class Accepted(val operationId: Long) : StreamStopResult

    data class Rejected(val reason: StreamStopRejection) : StreamStopResult
}

enum class StreamStopRejection {
    NO_ACTIVE_STREAM,
    ALREADY_STOPPING,
}

sealed interface StreamUpdateResult {
    data class Applied(val snapshot: StreamSnapshot) : StreamUpdateResult

    data class IgnoredStale(val operationId: Long) : StreamUpdateResult
}

data class StreamStateEvent(
    val previous: StreamSnapshot,
    val current: StreamSnapshot,
)

fun interface StreamStateListener {
    fun onChanged(event: StreamStateEvent)
}

fun interface Registration {
    fun unregister()
}

fun interface StreamStateDiagnosticSink {
    fun record(kind: StreamStateDiagnosticKind)
}

enum class StreamStateDiagnosticKind {
    LISTENER_FAILURE,
}

class StreamStateStore private constructor(
    private val diagnosticSink: StreamStateDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var current = initialSnapshot()
    private var nextOperationId = 0L
    private var activeOperation: Long? = null
    private val listeners = mutableListOf<ListenerSlot>()
    private val pendingEvents = ArrayDeque<PendingEvent>()
    private var draining = false

    fun requestStart(config: ValidatedStreamConfig): StreamStartResult {
        var shouldDrain = false
        val result = lock.withLock {
            if (current.state in setOf(StreamLifecycleState.STARTING, StreamLifecycleState.STREAMING, StreamLifecycleState.STOPPING)) {
                return@withLock StreamStartResult.Rejected(StreamStartRejection.ALREADY_ACTIVE)
            }
            require(config.rtmpUrl.isNotBlank()) { "Validated stream URL must not be blank" }
            val operationId = ++nextOperationId
            activeOperation = operationId
            val previous = current
            current = current.copy(
                revision = current.revision + 1,
                state = StreamLifecycleState.STARTING,
                targetConfigured = true,
                notice = "Starting",
                metrics = null,
            )
            shouldDrain = enqueue(previous, current)
            StreamStartResult.Accepted(operationId)
        }
        if (shouldDrain) drain()
        return result
    }

    fun requestStop(): StreamStopResult {
        var shouldDrain = false
        val result = lock.withLock {
            when (current.state) {
                StreamLifecycleState.STARTING,
                StreamLifecycleState.STREAMING,
                -> {
                    val operationId = ++nextOperationId
                    activeOperation = operationId
                    val previous = current
                    current = current.copy(
                        revision = current.revision + 1,
                        state = StreamLifecycleState.STOPPING,
                        notice = "Stopping",
                    )
                    shouldDrain = enqueue(previous, current)
                    StreamStopResult.Accepted(operationId)
                }

                StreamLifecycleState.STOPPING -> StreamStopResult.Rejected(StreamStopRejection.ALREADY_STOPPING)
                StreamLifecycleState.STOPPED,
                StreamLifecycleState.FAILED,
                -> StreamStopResult.Rejected(StreamStopRejection.NO_ACTIVE_STREAM)
            }
        }
        if (shouldDrain) drain()
        return result
    }

    fun markStarted(operationId: Long, metrics: StreamMetrics? = null): StreamUpdateResult {
        validateMetrics(metrics)
        return complete(operationId, StreamLifecycleState.STARTING) {
            copy(
                state = StreamLifecycleState.STREAMING,
                notice = "Streaming",
                metrics = metrics,
            )
        }
    }

    fun markStopped(operationId: Long, notice: String = "Stopped"): StreamUpdateResult {
        validateNotice(notice)
        return complete(operationId, StreamLifecycleState.STOPPING) {
            copy(
                state = StreamLifecycleState.STOPPED,
                targetConfigured = false,
                notice = notice,
                metrics = null,
            )
        }
    }

    fun markFailed(operationId: Long, notice: String = "Stream failed"): StreamUpdateResult {
        validateNotice(notice)
        return completeAnyActive(operationId) {
            copy(
                state = StreamLifecycleState.FAILED,
                targetConfigured = false,
                notice = notice,
                metrics = null,
            )
        }
    }

    fun markDeviceUnavailable(notice: String = "Device unavailable"): StreamUpdateResult {
        validateNotice(notice)
        var shouldDrain = false
        val result = lock.withLock {
            val previous = current
            activeOperation = null
            current = current.copy(
                revision = current.revision + 1,
                state = if (current.state == StreamLifecycleState.STOPPED) StreamLifecycleState.STOPPED else StreamLifecycleState.FAILED,
                targetConfigured = false,
                notice = notice,
                metrics = null,
            )
            shouldDrain = enqueue(previous, current)
            StreamUpdateResult.Applied(current)
        }
        if (shouldDrain) drain()
        return result
    }

    fun updateMetrics(operationId: Long, metrics: StreamMetrics): StreamUpdateResult {
        validateMetrics(metrics)
        var shouldDrain = false
        val result = lock.withLock {
            if (activeOperation != operationId || current.state != StreamLifecycleState.STREAMING) {
                StreamUpdateResult.IgnoredStale(operationId)
            } else {
                val previous = current
                current = current.copy(revision = current.revision + 1, metrics = metrics)
                shouldDrain = enqueue(previous, current)
                StreamUpdateResult.Applied(current)
            }
        }
        if (shouldDrain) drain()
        return result
    }

    fun snapshot(): StreamSnapshot = lock.withLock { current }

    fun onChanged(listener: StreamStateListener): Registration {
        val slot = ListenerSlot(listener)
        lock.withLock { listeners += slot }
        return Registration {
            if (slot.deactivate()) lock.withLock { listeners.remove(slot) }
        }
    }

    private fun complete(
        operationId: Long,
        expected: StreamLifecycleState,
        transform: StreamSnapshot.() -> StreamSnapshot,
    ): StreamUpdateResult {
        var shouldDrain = false
        val result = lock.withLock {
            if (activeOperation != operationId || current.state != expected) {
                StreamUpdateResult.IgnoredStale(operationId)
            } else {
                val previous = current
                current = current.transform().copy(revision = current.revision + 1)
                activeOperation = if (current.state == StreamLifecycleState.STREAMING) operationId else null
                shouldDrain = enqueue(previous, current)
                StreamUpdateResult.Applied(current)
            }
        }
        if (shouldDrain) drain()
        return result
    }

    private fun completeAnyActive(
        operationId: Long,
        transform: StreamSnapshot.() -> StreamSnapshot,
    ): StreamUpdateResult {
        var shouldDrain = false
        val result = lock.withLock {
            if (activeOperation != operationId || current.state !in setOf(
                    StreamLifecycleState.STARTING,
                    StreamLifecycleState.STREAMING,
                    StreamLifecycleState.STOPPING,
                )
            ) {
                StreamUpdateResult.IgnoredStale(operationId)
            } else {
                val previous = current
                current = current.transform().copy(revision = current.revision + 1)
                activeOperation = null
                shouldDrain = enqueue(previous, current)
                StreamUpdateResult.Applied(current)
            }
        }
        if (shouldDrain) drain()
        return result
    }

    private fun enqueue(previous: StreamSnapshot, current: StreamSnapshot): Boolean {
        pendingEvents.addLast(PendingEvent(StreamStateEvent(previous, current), listeners.toList()))
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
                } else pendingEvents.removeFirst()
            } ?: return
            pending.listeners.forEach { listener ->
                try {
                    listener.onChanged(pending.event)
                } catch (_: Throwable) {
                    runCatching { diagnosticSink.record(StreamStateDiagnosticKind.LISTENER_FAILURE) }
                }
            }
        }
    }

    private class ListenerSlot(private val delegate: StreamStateListener) : StreamStateListener {
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
                        try { idle.await() } catch (_: InterruptedException) { interrupted = true }
                    }
                }
                return changed
            } finally {
                lock.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        override fun onChanged(event: StreamStateEvent) {
            if (!lock.withLock { if (active) { inFlight += 1; true } else false }) return
            val depth = callbackDepth.get()
            callbackDepth.set(depth + 1)
            try { delegate.onChanged(event) } finally {
                if (depth == 0) callbackDepth.remove() else callbackDepth.set(depth)
                lock.withLock { inFlight -= 1; if (inFlight == 0) idle.signalAll() }
            }
        }
    }

    private data class PendingEvent(
        val event: StreamStateEvent,
        val listeners: List<ListenerSlot>,
    )

    private fun validateMetrics(metrics: StreamMetrics?) {
        if (metrics == null) return
        metrics.resolution?.let {
            require(it.isNotBlank() && it.codePointCount(0, it.length) <= 64 && it.none(Char::isISOControl))
        }
        metrics.fps?.let { require(it.isFinite() && it in 0.0..240.0) }
        metrics.videoBitrateKbps?.let { require(it.isFinite() && it in 0.0..1_000_000.0) }
        metrics.rttMillis?.let { require(it in 0..60_000) }
        metrics.packetLoss?.let { require(it in 0..Int.MAX_VALUE.toLong()) }
        metrics.packetCacheLength?.let { require(it in 0..Int.MAX_VALUE.toLong()) }
    }

    private fun validateNotice(notice: String) {
        require(notice.codePointCount(0, notice.length) <= 256 && notice.none(Char::isISOControl))
    }

    companion object {
        fun create(diagnosticSink: StreamStateDiagnosticSink = StreamStateDiagnosticSink { }): StreamStateStore =
            StreamStateStore(diagnosticSink)

        private fun initialSnapshot() = StreamSnapshot(0, StreamLifecycleState.STOPPED, false, "Stopped", null)
    }
}
