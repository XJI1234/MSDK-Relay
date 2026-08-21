package com.skycommand.relay.stream.whip

import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.stream.video.EncodedVideoSource
import com.skycommand.relay.stream.whip.command.WhipActionCompletion
import com.skycommand.relay.stream.whip.command.WhipActionResult
import com.skycommand.relay.stream.whip.command.WhipActionTerminalOutcome
import com.skycommand.relay.stream.whip.command.WhipCommandHandler
import com.skycommand.relay.stream.whip.command.WhipCommandRejection
import com.skycommand.relay.stream.whip.command.WhipCommandResult
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import com.skycommand.relay.stream.whip.publisher.WhipPublisher
import com.skycommand.relay.stream.whip.publisher.WhipPublisherDependencies
import com.skycommand.relay.stream.whip.publisher.WhipPublisherFailure
import com.skycommand.relay.stream.whip.publisher.WhipPublisherListener
import com.skycommand.relay.stream.whip.publisher.WhipPublisherStartRejection
import com.skycommand.relay.stream.whip.publisher.WhipPublisherStartResult
import com.skycommand.relay.stream.whip.publisher.WhipPublisherStopResult
import com.skycommand.relay.stream.whip.publisher.WhipTransport
import com.skycommand.relay.stream.whip.state.WhipDeviceSnapshot
import com.skycommand.relay.stream.whip.state.WhipStopRejection
import com.skycommand.relay.stream.whip.state.WhipStopResult
import com.skycommand.relay.stream.whip.state.WhipStreamFailure
import com.skycommand.relay.stream.whip.state.WhipStreamMetrics
import com.skycommand.relay.stream.whip.state.WhipStreamStateDiagnosticSink
import com.skycommand.relay.stream.whip.state.WhipStreamStateRegistration
import com.skycommand.relay.stream.whip.state.WhipStreamStateStore
import java.util.concurrent.atomic.AtomicBoolean

data class WhipLiveStreamDependencies(
    val deviceId: String,
    val source: EncodedVideoSource,
    val transport: WhipTransport,
    val diagnosticSink: WhipStreamStateDiagnosticSink = WhipStreamStateDiagnosticSink { },
)

class WhipLiveStream private constructor(
    private val dependencies: WhipLiveStreamDependencies,
) : AutoCloseable {
    private val lock = Any()
    private val state = WhipStreamStateStore.create(dependencies.diagnosticSink)
    private val publisher = WhipPublisher.create(WhipPublisherDependencies(dependencies.transport))
    private val commands = WhipCommandHandler.create(Actions())
    private var activeSession: Session? = null
    private var closed = false

    fun commandHandler(): CommandHandler = CommandHandler(::handleCommand)

    fun snapshot(): WhipDeviceSnapshot = state.snapshot(dependencies.deviceId)

    fun onChanged(listener: (WhipDeviceSnapshot) -> Unit): WhipStreamStateRegistration =
        state.onChanged { listener(it.current) }

    fun markDeviceUnavailable(): WhipDeviceSnapshot {
        val session = synchronized(lock) {
            activeSession.also {
                activeSession = null
                it?.invalidate()
            }
        }
        val snapshot = state.markDeviceUnavailable(dependencies.deviceId)
        if (session != null) {
            session.complete(WhipActionTerminalOutcome.FAILED)
            stopPublisher()
        }
        return snapshot
    }

    override fun close() {
        val session = synchronized(lock) {
            if (closed) return
            closed = true
            activeSession.also {
                activeSession = null
                it?.invalidate()
            }
        }
        state.markDeviceUnavailable(dependencies.deviceId)
        session?.complete(WhipActionTerminalOutcome.FAILED)
        stopPublisher()
    }

    private fun handleCommand(command: CommandFrame, completion: CommandCompletion) {
        val result = try {
            commands.handle(
                command,
                WhipActionCompletion { outcome ->
                    when (outcome) {
                        WhipActionTerminalOutcome.SUCCEEDED ->
                            completion.succeed(successDetail(command))

                        WhipActionTerminalOutcome.FAILED,
                        WhipActionTerminalOutcome.TIMED_OUT,
                        WhipActionTerminalOutcome.CANCELLED,
                        -> completion.reject("WHIP stream failed")
                    }
                },
            )
        } catch (_: Throwable) {
            completion.reject("WHIP stream operation was rejected")
            return
        }
        if (result is WhipCommandResult.Rejected) {
            completion.reject(commandRejectionDetail(result.reason))
        }
    }

    private inner class Actions : com.skycommand.relay.stream.whip.command.WhipCommandActions {
        override fun start(
            config: ValidatedWhipStreamConfig,
            completion: WhipActionCompletion,
        ): WhipActionResult {
            val accepted = state.requestStart(dependencies.deviceId, config)
            if (accepted !is com.skycommand.relay.stream.whip.state.WhipStartResult.Accepted) {
                return WhipActionResult.Rejected
            }
            val session = Session(accepted.operationId, completion)
            synchronized(lock) {
                if (closed || activeSession != null) {
                    session.invalidate()
                    state.markFailed(
                        dependencies.deviceId,
                        accepted.operationId,
                        WhipStreamFailure.INTERNAL,
                    )
                    return WhipActionResult.Rejected
                }
                activeSession = session
            }

            val result = try {
                publisher.start(config, dependencies.source, session.listener)
            } catch (_: Throwable) {
                WhipPublisherStartResult.Rejected(WhipPublisherStartRejection.INTERNAL)
            }
            return when (result) {
                is WhipPublisherStartResult.Accepted -> {
                    session.publisherGeneration = result.generation
                    WhipActionResult.Accepted
                }

                WhipPublisherStartResult.AlreadyActive -> {
                    failStart(session, WhipStreamFailure.INTERNAL)
                    WhipActionResult.Rejected
                }

                is WhipPublisherStartResult.Rejected -> {
                    failStart(session, result.reason.toFailure())
                    WhipActionResult.Rejected
                }
            }
        }

        override fun stop(completion: WhipActionCompletion): WhipActionResult {
            val accepted = state.requestStop(dependencies.deviceId)
            if (accepted is WhipStopResult.Rejected) {
                if (accepted.reason == WhipStopRejection.NO_ACTIVE_STREAM) {
                    completion.complete(WhipActionTerminalOutcome.SUCCEEDED)
                    return WhipActionResult.Accepted
                }
                return WhipActionResult.Rejected
            }
            if (accepted !is WhipStopResult.Accepted) {
                return WhipActionResult.Rejected
            }
            val session = synchronized(lock) {
                activeSession?.also { it.retarget(accepted.operationId, completion) }
            }
            if (session == null) {
                state.markStopped(dependencies.deviceId, accepted.operationId)
                completion.complete(WhipActionTerminalOutcome.SUCCEEDED)
                return WhipActionResult.Accepted
            }
            val result = try {
                publisher.stop()
            } catch (_: Throwable) {
                WhipPublisherStopResult.AlreadyStopped
            }
            return when (result) {
                is WhipPublisherStopResult.Accepted -> WhipActionResult.Accepted
                WhipPublisherStopResult.AlreadyStopping -> WhipActionResult.Rejected
                WhipPublisherStopResult.AlreadyStopped -> {
                    val operationId = session.operationId()
                    synchronized(lock) { if (activeSession === session) activeSession = null }
                    state.markStopped(dependencies.deviceId, operationId)
                    session.complete(WhipActionTerminalOutcome.SUCCEEDED)
                    session.invalidate()
                    WhipActionResult.Accepted
                }
            }
        }
    }

    private fun failStart(session: Session, failure: WhipStreamFailure) {
        val shouldComplete = synchronized(lock) {
            if (activeSession !== session) false else {
                activeSession = null
                session.invalidate()
                true
            }
        }
        state.markFailed(dependencies.deviceId, session.operationId(), failure)
        if (shouldComplete) session.complete(WhipActionTerminalOutcome.FAILED)
    }

    private fun failStop(session: Session) {
        val operationId = session.operationId()
        val shouldComplete = synchronized(lock) {
            if (activeSession !== session) false else {
                activeSession = null
                session.invalidate()
                true
            }
        }
        state.markFailed(dependencies.deviceId, operationId, WhipStreamFailure.INTERNAL)
        if (shouldComplete) session.complete(WhipActionTerminalOutcome.FAILED)
        stopPublisher()
    }

    private fun stopPublisher() {
        val result = runCatching { publisher.stop() }.getOrNull()
        if (result is WhipPublisherStopResult.AlreadyStopped || result == null) {
            runCatching { dependencies.source.stop() }
            runCatching { dependencies.transport.close() }
        }
    }

    private fun successDetail(command: CommandFrame): String = when (command.name) {
        "live-stream-webrtc.start" -> "WHIP stream started"
        "live-stream-webrtc.stop" -> "WHIP stream stopped"
        else -> "WHIP stream operation completed"
    }

    private fun commandRejectionDetail(reason: WhipCommandRejection): String = when (reason) {
        WhipCommandRejection.UNKNOWN_COMMAND -> "WHIP command is not available"
        WhipCommandRejection.INVALID_FIELDS -> "WHIP command fields are invalid"
        WhipCommandRejection.INVALID_CONFIGURATION -> "WHIP configuration is invalid"
        WhipCommandRejection.CAPABILITY_REJECTED -> "WHIP stream operation was rejected"
    }

    private inner class Session(
        initialOperationId: Long,
        private val completion: WhipActionCompletion,
    ) {
        private val active = AtomicBoolean(true)
        private val completed = AtomicBoolean(false)
        @Volatile private var operationIdValue = initialOperationId
        var publisherGeneration: Long? = null
        val listener = object : WhipPublisherListener {
            override fun onPublishing(generation: Long, metrics: WhipStreamMetrics?) {
                if (!isActive()) return
                val operationId = operationId()
                state.markPublishing(dependencies.deviceId, operationId, metrics)
                complete(WhipActionTerminalOutcome.SUCCEEDED)
            }

            override fun onStopped(generation: Long) {
                if (!isActive()) return
                val operationId = operationId()
                val current = state.markStopped(dependencies.deviceId, operationId)
                if (current is com.skycommand.relay.stream.whip.state.WhipUpdateResult.Applied) {
                    synchronized(lock) { if (activeSession === this@Session) activeSession = null }
                    complete(WhipActionTerminalOutcome.SUCCEEDED)
                    invalidate()
                }
            }

            override fun onFailed(generation: Long, reason: WhipPublisherFailure) {
                if (!isActive()) return
                val operationId = operationId()
                state.markFailed(dependencies.deviceId, operationId, reason.toFailure())
                synchronized(lock) { if (activeSession === this@Session) activeSession = null }
                complete(WhipActionTerminalOutcome.FAILED)
                invalidate()
            }

            override fun onDisconnected(generation: Long) {
                if (!isActive()) return
                val operationId = operationId()
                state.markDisconnected(dependencies.deviceId, operationId)
                synchronized(lock) { if (activeSession === this@Session) activeSession = null }
                complete(WhipActionTerminalOutcome.FAILED)
                invalidate()
            }
        }

        fun retarget(operationId: Long, nextCompletion: WhipActionCompletion) {
            operationIdValue = operationId
            completed.set(false)
            // The stop completion is the only completion that can still be pending here.
            completionRef = nextCompletion
        }

        private var completionRef: WhipActionCompletion = completion

        fun operationId(): Long = operationIdValue

        fun isActive(): Boolean = active.get()

        fun invalidate() {
            active.set(false)
        }

        fun complete(outcome: WhipActionTerminalOutcome) {
            if (completed.compareAndSet(false, true)) runCatching { completionRef.complete(outcome) }
        }
    }

    companion object {
        fun create(dependencies: WhipLiveStreamDependencies): WhipLiveStream {
            require(dependencies.deviceId.isNotBlank()) { "Device ID must not be blank" }
            require(dependencies.deviceId.codePointCount(0, dependencies.deviceId.length) <= 128) {
                "Device ID is too long"
            }
            require(dependencies.deviceId.none(Char::isISOControl)) { "Device ID contains a control character" }
            return WhipLiveStream(dependencies)
        }
    }
}

private fun WhipPublisherStartRejection.toFailure(): WhipStreamFailure = when (this) {
    WhipPublisherStartRejection.INVALID_CONFIGURATION -> WhipStreamFailure.INTERNAL
    WhipPublisherStartRejection.TRANSPORT_REJECTED -> WhipStreamFailure.INTERNAL
    WhipPublisherStartRejection.SOURCE_REJECTED -> WhipStreamFailure.INTERNAL
    WhipPublisherStartRejection.INTERNAL -> WhipStreamFailure.INTERNAL
}

private fun WhipPublisherFailure.toFailure(): WhipStreamFailure = when (this) {
    WhipPublisherFailure.ENCODED_H264_UNAVAILABLE -> WhipStreamFailure.UNSUPPORTED_CODEC
    WhipPublisherFailure.TRANSPORT_REJECTED,
    WhipPublisherFailure.INTERNAL,
    WhipPublisherFailure.STOP_FAILED,
    -> WhipStreamFailure.INTERNAL
    WhipPublisherFailure.SIGNALING -> WhipStreamFailure.SIGNALING
    WhipPublisherFailure.ICE -> WhipStreamFailure.ICE
    WhipPublisherFailure.NETWORK -> WhipStreamFailure.NETWORK
    WhipPublisherFailure.TIMEOUT -> WhipStreamFailure.TIMEOUT
    WhipPublisherFailure.SOURCE_REJECTED -> WhipStreamFailure.INTERNAL
}
