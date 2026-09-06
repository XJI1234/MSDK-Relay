package com.skycommand.relay.stream.camera.observer

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class CameraFrameCodec {
    H264,
    H265,
    UNKNOWN,
}

/** Metadata only. Encoded video bytes intentionally never cross this port. */
data class CameraFrameReceipt(
    val byteCount: Int,
    val codec: CameraFrameCodec,
    val width: Int,
    val height: Int,
    val frameRate: Int,
)

fun interface CameraFrameReceiptListener {
    fun onFrame(receipt: CameraFrameReceipt)
}

interface CameraFrameObservationPort {
    fun addReceiveStreamListener(listener: CameraFrameReceiptListener)

    fun removeReceiveStreamListener(listener: CameraFrameReceiptListener)
}

enum class CameraFrameObservationState {
    /** No active listener exists. This is never a DJI connection assertion. */
    UNAVAILABLE,
    /** Listener is active but the current generation has not received a valid frame. */
    UNOBSERVED,
    /** A valid encoded camera frame was observed within the continuity window. */
    RECEIVING,
    /** Valid frames were previously observed but no new frame arrived within the window. */
    STALLED,
}

data class CameraFrameSnapshot(
    val generation: Long,
    val state: CameraFrameObservationState,
    val receivedFrameCount: Long,
    /** Age measured by the phone-local monotonic clock at snapshot time. */
    val lastFrameAgeMillis: Long?,
    val codec: CameraFrameCodec?,
    val width: Int?,
    val height: Int?,
    val frameRate: Int?,
) {
    init {
        require(generation >= 0) { "Camera frame generation cannot be negative" }
        require(receivedFrameCount >= 0) { "Camera frame count cannot be negative" }
        lastFrameAgeMillis?.let { require(it >= 0) { "Camera frame age cannot be negative" } }
        width?.let { require(it > 0) { "Camera frame width must be positive" } }
        height?.let { require(it > 0) { "Camera frame height must be positive" } }
        frameRate?.let { require(it in 1..240) { "Camera frame rate must be in range" } }
        if (receivedFrameCount == 0L) {
            require(lastFrameAgeMillis == null && codec == null && width == null && height == null && frameRate == null) {
                "Unobserved snapshots cannot carry frame facts"
            }
        }
    }
}

sealed interface CameraFrameObservationStartResult {
    data object Started : CameraFrameObservationStartResult

    data object AlreadyStarted : CameraFrameObservationStartResult

    data object Failed : CameraFrameObservationStartResult
}

sealed interface CameraFrameObservationStopResult {
    data object Stopped : CameraFrameObservationStopResult

    data object AlreadyStopped : CameraFrameObservationStopResult

    data object Failed : CameraFrameObservationStopResult
}

fun interface CameraFrameRegistration {
    fun unregister()
}

fun interface CameraFrameStateListener {
    fun onChanged(snapshot: CameraFrameSnapshot)
}

fun interface CameraFrameObserverDiagnosticSink {
    fun record(kind: CameraFrameObserverDiagnosticKind)
}

enum class CameraFrameObserverDiagnosticKind {
    PLATFORM_REGISTRATION_FAILURE,
    PLATFORM_REMOVAL_FAILURE,
    LISTENER_FAILURE,
}

/**
 * Read-only production observation for the DJI camera media source. It is deliberately
 * independent from the RTMP publisher and from the archived WHIP/WebRTC path.
 */
class CameraFrameObserver private constructor(
    private val port: CameraFrameObservationPort,
    private val clock: () -> Long,
    private val diagnosticSink: CameraFrameObserverDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private val listeners = mutableListOf<CameraFrameStateListener>()
    private var nextGeneration = 0L
    private var active: Active? = null
    private var fact = Fact.initial()

    fun start(): CameraFrameObservationStartResult {
        val candidate = lock.withLock {
            if (active != null) return CameraFrameObservationStartResult.AlreadyStarted
            Active(nextGeneration + 1).also { active = it }
        }
        try {
            port.addReceiveStreamListener(candidate.listener)
        } catch (_: Throwable) {
            val changed = lock.withLock {
                if (active !== candidate) return@withLock null
                active = null
                snapshotLocked(clock())
            }
            record(CameraFrameObserverDiagnosticKind.PLATFORM_REGISTRATION_FAILURE)
            changed?.let(::notifyListeners)
            return CameraFrameObservationStartResult.Failed
        }

        val started = lock.withLock {
            if (active !== candidate) false else {
                nextGeneration = candidate.generation
                candidate.started = true
                fact = Fact.unobserved(candidate.generation)
                true
            }
        }
        if (!started) {
            runCatching { port.removeReceiveStreamListener(candidate.listener) }
            return CameraFrameObservationStartResult.Failed
        }
        notifyListeners(snapshot())
        return CameraFrameObservationStartResult.Started
    }

    fun stop(): CameraFrameObservationStopResult {
        val previous = lock.withLock {
            active?.also { active = null }
        } ?: return CameraFrameObservationStopResult.AlreadyStopped

        val stoppedSnapshot = lock.withLock {
            fact = Fact.unavailable(nextGeneration)
            snapshotLocked(clock())
        }
        notifyListeners(stoppedSnapshot)
        return try {
            port.removeReceiveStreamListener(previous.listener)
            CameraFrameObservationStopResult.Stopped
        } catch (_: Throwable) {
            record(CameraFrameObserverDiagnosticKind.PLATFORM_REMOVAL_FAILURE)
            CameraFrameObservationStopResult.Failed
        }
    }

    /** Evaluates only video continuity; it never changes any DJI Key connection fact. */
    fun evaluate(nowMillis: Long = clock()): CameraFrameSnapshot {
        val changed = lock.withLock {
            val current = active ?: return@withLock null
            val lastFrameAt = fact.lastFrameAtMillis ?: return@withLock null
            if (nowMillis < lastFrameAt || fact.state != CameraFrameObservationState.RECEIVING) return@withLock null
            if (nowMillis - lastFrameAt < stallWindowMillis(fact.frameRate)) return@withLock null
            if (current.generation != fact.generation) return@withLock null
            fact = fact.copy(state = CameraFrameObservationState.STALLED)
            snapshotLocked(nowMillis)
        }
        changed?.let(::notifyListeners)
        return snapshot(nowMillis)
    }

    fun snapshot(nowMillis: Long = clock()): CameraFrameSnapshot = lock.withLock { snapshotLocked(nowMillis) }

    fun onChanged(listener: CameraFrameStateListener): CameraFrameRegistration {
        lock.withLock { listeners += listener }
        return CameraFrameRegistration {
            lock.withLock { listeners.remove(listener) }
        }
    }

    private fun received(candidate: Active, receipt: CameraFrameReceipt) {
        if (!receipt.valid()) return
        val changed = lock.withLock {
            if (active !== candidate || !candidate.started || fact.generation != candidate.generation) return@withLock null
            val nowMillis = clock()
            val metadataChanged = fact.codec != receipt.codec || fact.width != receipt.width || fact.height != receipt.height || fact.frameRate != receipt.frameRate
            val stateChanged = fact.state != CameraFrameObservationState.RECEIVING
            fact = Fact(
                generation = candidate.generation,
                state = CameraFrameObservationState.RECEIVING,
                receivedFrameCount = fact.receivedFrameCount + 1,
                lastFrameAtMillis = nowMillis,
                codec = receipt.codec,
                width = receipt.width,
                height = receipt.height,
                frameRate = receipt.frameRate,
            )
            if (metadataChanged || stateChanged) snapshotLocked(nowMillis) else null
        }
        changed?.let(::notifyListeners)
    }

    private fun CameraFrameReceipt.valid(): Boolean =
        byteCount > 0 && width > 0 && height > 0 && frameRate in 1..240

    private fun stallWindowMillis(frameRate: Int?): Long {
        if (frameRate == null || frameRate !in 1..240) return MINIMUM_STALL_WINDOW_MILLIS
        return (CONTINUITY_FRAMES * 1_000L / frameRate).coerceIn(MINIMUM_STALL_WINDOW_MILLIS, MAXIMUM_STALL_WINDOW_MILLIS)
    }

    private fun snapshotLocked(nowMillis: Long): CameraFrameSnapshot {
        val age = fact.lastFrameAtMillis?.let { lastFrameAt ->
            if (nowMillis < lastFrameAt) 0L else nowMillis - lastFrameAt
        }
        return CameraFrameSnapshot(
            generation = fact.generation,
            state = fact.state,
            receivedFrameCount = fact.receivedFrameCount,
            lastFrameAgeMillis = age,
            codec = fact.codec,
            width = fact.width,
            height = fact.height,
            frameRate = fact.frameRate,
        )
    }

    private fun notifyListeners(snapshot: CameraFrameSnapshot) {
        val subscribers = lock.withLock { listeners.toList() }
        subscribers.forEach { listener ->
            try {
                listener.onChanged(snapshot)
            } catch (_: Throwable) {
                record(CameraFrameObserverDiagnosticKind.LISTENER_FAILURE)
            }
        }
    }

    private fun record(kind: CameraFrameObserverDiagnosticKind) {
        runCatching { diagnosticSink.record(kind) }
    }

    private inner class Active(
        val generation: Long,
    ) {
        var started = false
        val listener = CameraFrameReceiptListener { receipt -> received(this, receipt) }
    }

    private data class Fact(
        val generation: Long,
        val state: CameraFrameObservationState,
        val receivedFrameCount: Long,
        val lastFrameAtMillis: Long?,
        val codec: CameraFrameCodec?,
        val width: Int?,
        val height: Int?,
        val frameRate: Int?,
    ) {
        companion object {
            fun initial() = unavailable(0)
            fun unavailable(generation: Long) = Fact(generation, CameraFrameObservationState.UNAVAILABLE, 0, null, null, null, null, null)
            fun unobserved(generation: Long) = Fact(generation, CameraFrameObservationState.UNOBSERVED, 0, null, null, null, null, null)
        }
    }

    companion object {
        private const val CONTINUITY_FRAMES = 180L
        private const val MINIMUM_STALL_WINDOW_MILLIS = 5_000L
        private const val MAXIMUM_STALL_WINDOW_MILLIS = 15_000L

        fun create(
            port: CameraFrameObservationPort,
            clock: () -> Long = { System.nanoTime() / 1_000_000L },
            diagnosticSink: CameraFrameObserverDiagnosticSink = CameraFrameObserverDiagnosticSink { },
        ): CameraFrameObserver = CameraFrameObserver(port, clock, diagnosticSink)
    }
}
