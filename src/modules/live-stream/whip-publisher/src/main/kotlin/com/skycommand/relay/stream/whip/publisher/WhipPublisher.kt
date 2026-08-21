package com.skycommand.relay.stream.whip.publisher

import com.skycommand.relay.stream.video.EncodedVideoFrame
import com.skycommand.relay.stream.video.EncodedVideoListener
import com.skycommand.relay.stream.video.EncodedVideoSource
import com.skycommand.relay.stream.video.SourceFailure
import com.skycommand.relay.stream.video.SourceStartResult
import com.skycommand.relay.stream.video.SourceStopResult
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import com.skycommand.relay.stream.whip.config.WhipConfigValidationResult
import com.skycommand.relay.stream.whip.config.WhipStreamConfigValidator
import com.skycommand.relay.stream.whip.state.WhipStreamMetrics

interface WhipTransport {
    fun open(config: ValidatedWhipStreamConfig, listener: WhipTransportListener): WhipTransportOpenResult

    fun send(frame: EncodedVideoFrame): WhipTransportSendResult

    fun close(): WhipTransportCloseResult
}

interface WhipTransportListener {
    fun onConnected()

    fun onFailed(reason: WhipTransportFailure)

    fun onDisconnected()
}

sealed interface WhipTransportOpenResult {
    data object Accepted : WhipTransportOpenResult

    data class Rejected(val reason: WhipTransportRejection) : WhipTransportOpenResult
}

enum class WhipTransportRejection {
    ENCODED_H264_UNAVAILABLE,
    INVALID_CONFIGURATION,
    INTERNAL,
}

enum class WhipTransportFailure {
    SIGNALING,
    ICE,
    NETWORK,
    TIMEOUT,
    INTERNAL,
}

sealed interface WhipTransportSendResult {
    data object Accepted : WhipTransportSendResult

    data object Dropped : WhipTransportSendResult

    data object Backpressured : WhipTransportSendResult

    data object NotConnected : WhipTransportSendResult

    data class Failed(val reason: WhipTransportFailure) : WhipTransportSendResult
}

sealed interface WhipTransportCloseResult {
    data object Closed : WhipTransportCloseResult

    data object AlreadyClosed : WhipTransportCloseResult

    data class Failed(val reason: WhipTransportFailure) : WhipTransportCloseResult
}

interface WhipPublisherListener {
    fun onPublishing(generation: Long, metrics: WhipStreamMetrics?)

    fun onStopped(generation: Long)

    fun onFailed(generation: Long, reason: WhipPublisherFailure)

    fun onDisconnected(generation: Long)
}

data class WhipPublisherDependencies(
    val transport: WhipTransport,
    val diagnosticSink: WhipPublisherDiagnosticSink = WhipPublisherDiagnosticSink { },
)

sealed interface WhipPublisherStartResult {
    data class Accepted(val generation: Long) : WhipPublisherStartResult

    data object AlreadyActive : WhipPublisherStartResult

    data class Rejected(val reason: WhipPublisherStartRejection) : WhipPublisherStartResult
}

enum class WhipPublisherStartRejection {
    INVALID_CONFIGURATION,
    TRANSPORT_REJECTED,
    SOURCE_REJECTED,
    INTERNAL,
}

sealed interface WhipPublisherStopResult {
    data class Accepted(val generation: Long) : WhipPublisherStopResult

    data object AlreadyStopped : WhipPublisherStopResult

    data object AlreadyStopping : WhipPublisherStopResult
}

enum class WhipPublisherState {
    IDLE,
    CONNECTING,
    PUBLISHING,
    STOPPING,
    FAILED,
    DISCONNECTED,
}

enum class WhipPublisherFailure {
    ENCODED_H264_UNAVAILABLE,
    TRANSPORT_REJECTED,
    SOURCE_REJECTED,
    SIGNALING,
    ICE,
    NETWORK,
    TIMEOUT,
    INTERNAL,
    STOP_FAILED,
}

data class WhipPublisherSnapshot(
    val revision: Long,
    val generation: Long?,
    val state: WhipPublisherState,
    val parameterSetsReady: Boolean,
    val keyFrameReady: Boolean,
    val droppedFrames: Long,
    val failure: WhipPublisherFailure?,
)

fun interface WhipPublisherDiagnosticSink {
    fun record(kind: WhipPublisherDiagnosticKind)
}

enum class WhipPublisherDiagnosticKind {
    LISTENER_FAILURE,
}

class WhipPublisher private constructor(
    private val dependencies: WhipPublisherDependencies,
) {
    private val lock = Any()
    private var nextGeneration = 0L
    private var active: Session? = null
    private var state = WhipPublisherState.IDLE
    private var revision = 0L
    private var currentGeneration: Long? = null
    private var parameterSetsReady = false
    private var keyFrameReady = false
    private var droppedFrames = 0L
    private var failure: WhipPublisherFailure? = null

    fun start(
        config: ValidatedWhipStreamConfig,
        source: EncodedVideoSource,
        listener: WhipPublisherListener,
    ): WhipPublisherStartResult {
        val validated = when (val result = WhipStreamConfigValidator.validate(config.whipUrl)) {
            is WhipConfigValidationResult.Valid -> result.config
            is WhipConfigValidationResult.Invalid ->
                return WhipPublisherStartResult.Rejected(WhipPublisherStartRejection.INVALID_CONFIGURATION)
        }

        val session = synchronized(lock) {
            if (active != null) return WhipPublisherStartResult.AlreadyActive
            val generation = ++nextGeneration
            val created = Session(
                generation = generation,
                source = source,
                listener = listener,
                sourceListener = EncodedVideoListener { frame -> onFrame(generation, frame) },
                transportListener = transportListener(generation),
            )
            active = created
            state = WhipPublisherState.CONNECTING
            currentGeneration = generation
            parameterSetsReady = false
            keyFrameReady = false
            droppedFrames = 0
            failure = null
            revision += 1
            created
        }

        val opened = try {
            dependencies.transport.open(validated, session.transportListener)
        } catch (_: Throwable) {
            terminate(session.generation, WhipPublisherState.FAILED, WhipPublisherFailure.INTERNAL)
            return WhipPublisherStartResult.Rejected(WhipPublisherStartRejection.INTERNAL)
        }
        session.transportOpened = opened is WhipTransportOpenResult.Accepted
        when (opened) {
            WhipTransportOpenResult.Accepted -> Unit
            is WhipTransportOpenResult.Rejected -> {
                terminate(session.generation, WhipPublisherState.FAILED, opened.reason.toFailure())
                return WhipPublisherStartResult.Rejected(WhipPublisherStartRejection.TRANSPORT_REJECTED)
            }
        }

        if (!isActive(session.generation)) {
            return WhipPublisherStartResult.Rejected(WhipPublisherStartRejection.TRANSPORT_REJECTED)
        }
        val sourceResult = try {
            source.start(session.sourceListener)
        } catch (_: Throwable) {
            SourceStartResult.Failed(SourceFailure.PLATFORM_FAILURE)
        }
        when (sourceResult) {
            SourceStartResult.Started -> {
                synchronized(lock) { if (active === session) session.sourceStarted = true }
                if (!isActive(session.generation)) {
                    runCatching { source.stop() }
                    return WhipPublisherStartResult.Rejected(WhipPublisherStartRejection.SOURCE_REJECTED)
                }
                return WhipPublisherStartResult.Accepted(session.generation)
            }

            SourceStartResult.AlreadyStarted,
            is SourceStartResult.Failed,
            -> {
                terminate(session.generation, WhipPublisherState.FAILED, sourceResult.toFailure())
                return WhipPublisherStartResult.Rejected(WhipPublisherStartRejection.SOURCE_REJECTED)
            }
        }
    }

    fun stop(): WhipPublisherStopResult {
        val session = synchronized(lock) {
            val current = active ?: return WhipPublisherStopResult.AlreadyStopped
            if (state == WhipPublisherState.STOPPING) return WhipPublisherStopResult.AlreadyStopping
            if (state != WhipPublisherState.CONNECTING && state != WhipPublisherState.PUBLISHING) {
                return WhipPublisherStopResult.AlreadyStopped
            }
            state = WhipPublisherState.STOPPING
            failure = null
            revision += 1
            current.stopRequested = true
            current.stopSourceComplete = !current.sourceStarted
            current.stopTransportComplete = !current.transportOpened
            current
        }

        var sourceFailure: WhipPublisherFailure? = null
        if (!session.stopSourceComplete) {
            val result = try {
                session.source.stop()
            } catch (_: Throwable) {
                SourceStopResult.Failed(SourceFailure.PLATFORM_FAILURE)
            }
            sourceFailure = result.toFailure()
            synchronized(lock) {
                if (active === session) {
                    session.stopSourceComplete = true
                    if (sourceFailure != null) session.stopFailure = WhipPublisherFailure.STOP_FAILED
                }
            }
        }

        var transportFailure: WhipPublisherFailure? = null
        if (!session.stopTransportComplete) {
            val result = try {
                dependencies.transport.close()
            } catch (_: Throwable) {
                WhipTransportCloseResult.Failed(WhipTransportFailure.INTERNAL)
            }
            transportFailure = result.toFailure()
            synchronized(lock) {
                if (active === session) {
                    session.stopTransportComplete = true
                    if (transportFailure != null) session.stopFailure = WhipPublisherFailure.STOP_FAILED
                }
            }
        }
        finishStopIfReady(session)
        return WhipPublisherStopResult.Accepted(session.generation)
    }

    fun snapshot(): WhipPublisherSnapshot = synchronized(lock) {
        WhipPublisherSnapshot(
            revision = revision,
            generation = currentGeneration,
            state = state,
            parameterSetsReady = parameterSetsReady,
            keyFrameReady = keyFrameReady,
            droppedFrames = droppedFrames,
            failure = failure,
        )
    }

    private fun transportListener(generation: Long) = object : WhipTransportListener {
        override fun onConnected() {
            synchronized(lock) {
                val session = active
                if (session?.generation == generation && state == WhipPublisherState.CONNECTING) {
                    session.transportConnected = true
                }
            }
            flushBootstrap(generation)
        }

        override fun onFailed(reason: WhipTransportFailure) {
            val shouldFail = synchronized(lock) {
                active?.generation == generation && state != WhipPublisherState.IDLE && state != WhipPublisherState.FAILED && state != WhipPublisherState.DISCONNECTED
            }
            if (shouldFail) {
                if (stateIsStopping(generation)) {
                    synchronized(lock) { active?.takeIf { it.generation == generation }?.apply {
                        stopTransportComplete = true
                        stopFailure = WhipPublisherFailure.STOP_FAILED
                    } }
                    finishStopIfReady(generation)
                } else {
                    terminate(generation, WhipPublisherState.FAILED, reason.toFailure())
                }
            }
        }

        override fun onDisconnected() {
            val stopping = stateIsStopping(generation)
            if (stopping) {
                synchronized(lock) { active?.takeIf { it.generation == generation }?.stopTransportComplete = true }
                finishStopIfReady(generation)
            } else {
                val shouldDisconnect = synchronized(lock) {
                    active?.generation == generation && state != WhipPublisherState.IDLE && state != WhipPublisherState.DISCONNECTED
                }
                if (shouldDisconnect) terminate(generation, WhipPublisherState.DISCONNECTED, null)
            }
        }
    }

    private fun onFrame(generation: Long, frame: EncodedVideoFrame) {
        val session = synchronized(lock) {
            active?.takeIf {
                it.generation == generation &&
                    (state == WhipPublisherState.CONNECTING || state == WhipPublisherState.PUBLISHING)
            }
        } ?: return
        if (!session.transportConnected) {
            stashBootstrap(session, frame)
            return
        }
        sendAndMaybePublish(generation, session, frame)
    }

    private fun stashBootstrap(session: Session, frame: EncodedVideoFrame) {
        val nalTypes = h264NalTypes(frame)
        synchronized(lock) {
            if (active !== session || session.transportConnected) return
            if (7 in nalTypes) session.pendingSps = frame
            if (8 in nalTypes) session.pendingPps = frame
            if (frame.isKeyFrame) session.pendingKeyFrame = frame
        }
    }

    private fun flushBootstrap(generation: Long) {
        val pending = synchronized(lock) {
            val session = active?.takeIf { it.generation == generation && it.transportConnected } ?: return
            val frames = listOfNotNull(session.pendingSps, session.pendingPps, session.pendingKeyFrame).distinct()
            session.pendingSps = null
            session.pendingPps = null
            session.pendingKeyFrame = null
            session to frames
        }
        for (frame in pending.second) sendAndMaybePublish(generation, pending.first, frame)
    }

    private fun sendAndMaybePublish(generation: Long, session: Session, frame: EncodedVideoFrame) {
        val nalTypes = h264NalTypes(frame)
        val result = try {
            dependencies.transport.send(frame)
        } catch (_: Throwable) {
            WhipTransportSendResult.Failed(WhipTransportFailure.INTERNAL)
        }
        when (result) {
            WhipTransportSendResult.Accepted -> {
                var shouldPublish = false
                synchronized(lock) {
                    if (active !== session) return
                    session.seenSps = session.seenSps || 7 in nalTypes
                    session.seenPps = session.seenPps || 8 in nalTypes
                    if (frame.isKeyFrame) session.sentKeyFrame = true
                    parameterSetsReady = session.seenSps && session.seenPps
                    keyFrameReady = session.sentKeyFrame
                    if (state == WhipPublisherState.CONNECTING && parameterSetsReady && keyFrameReady) {
                        state = WhipPublisherState.PUBLISHING
                        revision += 1
                        shouldPublish = true
                    }
                }
                if (shouldPublish) {
                    notify(session) { it.onPublishing(session.generation, metrics(frame)) }
                }
            }

            WhipTransportSendResult.Dropped,
            WhipTransportSendResult.Backpressured,
            WhipTransportSendResult.NotConnected,
            -> synchronized(lock) {
                if (active === session) droppedFrames += 1
            }

            is WhipTransportSendResult.Failed -> terminate(generation, WhipPublisherState.FAILED, result.reason.toFailure())
        }
    }

    private fun terminate(
        generation: Long,
        terminalState: WhipPublisherState,
        terminalFailure: WhipPublisherFailure?,
    ) {
        val session = synchronized(lock) {
            val current = active?.takeIf { it.generation == generation } ?: return
            if (state == WhipPublisherState.IDLE || state == WhipPublisherState.FAILED || state == WhipPublisherState.DISCONNECTED) return
            state = terminalState
            failure = terminalFailure
            revision += 1
            active = null
            current
        }
        cleanup(session)
        when (terminalState) {
            WhipPublisherState.FAILED -> notify(session) { it.onFailed(generation, terminalFailure ?: WhipPublisherFailure.INTERNAL) }
            WhipPublisherState.DISCONNECTED -> notify(session) { it.onDisconnected(generation) }
            else -> Unit
        }
    }

    private fun cleanup(session: Session) {
        if (session.sourceStarted) runCatching { session.source.stop() }
        if (session.transportOpened) runCatching { dependencies.transport.close() }
    }

    private fun finishStopIfReady(session: Session) {
        val completion = synchronized(lock) {
            if (active !== session || state != WhipPublisherState.STOPPING || !session.stopSourceComplete || !session.stopTransportComplete) {
                null
            } else {
                active = null
                val stopFailure = session.stopFailure
                state = if (stopFailure == null) WhipPublisherState.IDLE else WhipPublisherState.FAILED
                failure = stopFailure
                revision += 1
                StopCompletion(stopFailure)
            }
        } ?: return
        if (completion.failure == null) notify(session) { it.onStopped(session.generation) }
        else notify(session) { it.onFailed(session.generation, completion.failure) }
    }

    private fun finishStopIfReady(generation: Long) {
        synchronized(lock) { active?.takeIf { it.generation == generation } }?.let(::finishStopIfReady)
    }

    private fun stateIsStopping(generation: Long): Boolean = synchronized(lock) {
        active?.generation == generation && state == WhipPublisherState.STOPPING
    }

    private fun isActive(generation: Long): Boolean = synchronized(lock) { active?.generation == generation }

    private fun metrics(frame: EncodedVideoFrame) = WhipStreamMetrics(
        resolution = "${frame.width}x${frame.height}",
        fps = frame.frameRate.toDouble(),
    )

    private fun notify(session: Session, action: (WhipPublisherListener) -> Unit) {
        runCatching { action(session.listener) }.onFailure {
            runCatching { dependencies.diagnosticSink.record(WhipPublisherDiagnosticKind.LISTENER_FAILURE) }
        }
    }

    private class Session(
        val generation: Long,
        val source: EncodedVideoSource,
        val listener: WhipPublisherListener,
        val sourceListener: EncodedVideoListener,
        val transportListener: WhipTransportListener,
    ) {
        var transportOpened = false
        var transportConnected = false
        var sourceStarted = false
        var stopRequested = false
        var stopSourceComplete = false
        var stopTransportComplete = false
        var stopFailure: WhipPublisherFailure? = null
        var seenSps = false
        var seenPps = false
        var sentKeyFrame = false
        var pendingSps: EncodedVideoFrame? = null
        var pendingPps: EncodedVideoFrame? = null
        var pendingKeyFrame: EncodedVideoFrame? = null
    }

    private data class StopCompletion(
        val failure: WhipPublisherFailure?,
    )

    companion object {
        fun create(dependencies: WhipPublisherDependencies): WhipPublisher = WhipPublisher(dependencies)
    }
}

private fun WhipTransportRejection.toFailure(): WhipPublisherFailure = when (this) {
    WhipTransportRejection.ENCODED_H264_UNAVAILABLE -> WhipPublisherFailure.ENCODED_H264_UNAVAILABLE
    WhipTransportRejection.INVALID_CONFIGURATION -> WhipPublisherFailure.TRANSPORT_REJECTED
    WhipTransportRejection.INTERNAL -> WhipPublisherFailure.INTERNAL
}

private fun WhipTransportFailure.toFailure(): WhipPublisherFailure = when (this) {
    WhipTransportFailure.SIGNALING -> WhipPublisherFailure.SIGNALING
    WhipTransportFailure.ICE -> WhipPublisherFailure.ICE
    WhipTransportFailure.NETWORK -> WhipPublisherFailure.NETWORK
    WhipTransportFailure.TIMEOUT -> WhipPublisherFailure.TIMEOUT
    WhipTransportFailure.INTERNAL -> WhipPublisherFailure.INTERNAL
}

private fun SourceStartResult.toFailure(): WhipPublisherFailure = when (this) {
    SourceStartResult.AlreadyStarted -> WhipPublisherFailure.SOURCE_REJECTED
    SourceStartResult.Started -> WhipPublisherFailure.INTERNAL
    is SourceStartResult.Failed -> when (reason) {
        SourceFailure.INVALID_LISTENER -> WhipPublisherFailure.SOURCE_REJECTED
        SourceFailure.PLATFORM_FAILURE -> WhipPublisherFailure.SOURCE_REJECTED
        SourceFailure.UNSUPPORTED_CODEC -> WhipPublisherFailure.ENCODED_H264_UNAVAILABLE
    }
}

private fun SourceStopResult.toFailure(): WhipPublisherFailure? = when (this) {
    SourceStopResult.Stopped,
    SourceStopResult.AlreadyStopped,
    -> null

    is SourceStopResult.Failed -> WhipPublisherFailure.STOP_FAILED
}

private fun WhipTransportCloseResult.toFailure(): WhipPublisherFailure? = when (this) {
    WhipTransportCloseResult.Closed,
    WhipTransportCloseResult.AlreadyClosed,
    -> null

    is WhipTransportCloseResult.Failed -> WhipPublisherFailure.STOP_FAILED
}

private fun h264NalTypes(frame: EncodedVideoFrame): Set<Int> {
    val data = frame.data
    val start = frame.offset
    val end = frame.offset + frame.length
    val types = linkedSetOf<Int>()
    var position = start
    var foundStartCode = false
    while (position + 3 < end) {
        val startCodeLength = when {
            data[position] == 0.toByte() && data[position + 1] == 0.toByte() && data[position + 2] == 1.toByte() -> 3
            position + 4 < end && data[position] == 0.toByte() && data[position + 1] == 0.toByte() &&
                data[position + 2] == 0.toByte() && data[position + 3] == 1.toByte() -> 4

            else -> {
                position += 1
                continue
            }
        }
        foundStartCode = true
        val nalStart = position + startCodeLength
        if (nalStart < end) types += data[nalStart].toInt() and 0x1f
        position = nalStart
    }
    if (foundStartCode) return types

    position = start
    while (position + 4 <= end) {
        val size = ((data[position].toInt() and 0xff) shl 24) or
            ((data[position + 1].toInt() and 0xff) shl 16) or
            ((data[position + 2].toInt() and 0xff) shl 8) or
            (data[position + 3].toInt() and 0xff)
        if (size <= 0 || position + 4 + size > end) return emptySet()
        types += data[position + 4].toInt() and 0x1f
        position += 4 + size
    }
    if (position == end) return types
    if (start < end) types += data[start].toInt() and 0x1f
    return types
}
