package com.skycommand.relay.gateway.session

import com.skycommand.relay.protocol.DecodeResult
import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.PairedFrame
import com.skycommand.relay.protocol.ProtocolErrorCode
import com.skycommand.relay.protocol.ProtocolLimits
import com.skycommand.relay.protocol.Rejected
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import com.skycommand.relay.protocol.validate
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ConnectionSession private constructor(
    private val config: SessionConfig,
    private val dependencies: SessionDependencies,
) {
    @Volatile
    private var currentSnapshot = SessionSnapshot(SessionState.STOPPED, null, null)

    private val eventLoop = SerialEventLoop { error ->
        recordDiagnostic(SessionDiagnosticKind.DEPENDENCY_FAILURE, "Unhandled session event failure: ${error.javaClass.simpleName}")
    }
    private val listeners = mutableListOf<ListenerSlot>()
    private val transportListener = object : TransportListener {
        override fun onOpened(connection: TransportConnection) {
            eventLoop.execute { handleOpened(connection) }
        }

        override fun onBytes(generation: SessionGeneration, bytes: ByteArray) {
            val copiedBytes = bytes.copyOf()
            eventLoop.execute { handleBytes(generation, copiedBytes) }
        }

        override fun onClosed(generation: SessionGeneration, reason: String) {
            eventLoop.execute { handleTransportEnded(generation, "Transport closed") }
        }

        override fun onFailure(generation: SessionGeneration, reason: String) {
            eventLoop.execute { handleTransportEnded(generation, "Transport failed") }
        }
    }

    private var desiredRunning = false
    private var currentGeneration: SessionGeneration? = null
    private var currentConnection: TransportConnection? = null
    private var opened = false
    private var handshakeTimeout: ScheduledCancellation? = null
    private var retryCancellation: ScheduledCancellation? = null
    private var retryToken: RetryToken? = null
    private var consecutiveFailures = 0

    fun start(): StartResult = eventLoop.call {
        when (currentSnapshot.state) {
            SessionState.STOPPED -> {
                desiredRunning = true
                consecutiveFailures = 0
                startAttempt()
                StartResult.StartAccepted
            }

            SessionState.RECONNECT_WAIT -> {
                desiredRunning = true
                cancelRetry()
                startAttempt()
                StartResult.StartAccepted
            }

            SessionState.CONNECTING,
            SessionState.AWAITING_PAIRING,
            SessionState.ACTIVE,
            -> StartResult.AlreadyRunning(currentSnapshot)
        }
    }

    fun stop(): StopResult = eventLoop.call {
        when (currentSnapshot.state) {
            SessionState.STOPPED -> StopResult.AlreadyStopped
            SessionState.RECONNECT_WAIT -> {
                desiredRunning = false
                cancelRetry()
                val reason = reason(SessionEndKind.EXPLICIT_STOP, "Session stopped by caller")
                transition(SessionSnapshot(SessionState.STOPPED, null, null), reason)
                StopResult.Stopped
            }

            SessionState.CONNECTING,
            SessionState.AWAITING_PAIRING,
            SessionState.ACTIVE,
            -> {
                desiredRunning = false
                endCurrent(reason(SessionEndKind.EXPLICIT_STOP, "Session stopped by caller"))
                StopResult.Stopped
            }
        }
    }

    fun snapshot(): SessionSnapshot = currentSnapshot

    fun onStateChanged(listener: SessionStateListener): Registration {
        val slot = ListenerSlot(listener)
        eventLoop.call { listeners += slot }
        return Registration {
            if (slot.deactivate()) {
                eventLoop.execute { listeners.remove(slot) }
            }
        }
    }

    private fun startAttempt() {
        val generation = SessionGeneration.create()
        currentGeneration = generation
        currentConnection = null
        opened = false
        handshakeTimeout = null
        transition(SessionSnapshot(SessionState.CONNECTING, generation, null), null)

        val openResult = try {
            dependencies.connector.open(config.endpoint, generation, transportListener)
        } catch (_: Throwable) {
            recordDiagnostic(SessionDiagnosticKind.DEPENDENCY_FAILURE, "Transport connector threw an exception")
            TransportOpenResult.OpenRejected("connector failure")
        }

        when (openResult) {
            is TransportOpenResult.OpenAccepted -> {
                if (openResult.connection.generation != generation) {
                    closeStale(openResult.connection)
                    recordDiagnostic(SessionDiagnosticKind.ADAPTER_VIOLATION, "Transport returned a mismatched generation")
                    endCurrent(reason(SessionEndKind.NOT_CONNECTED, "Transport open failed"))
                } else {
                    currentConnection = openResult.connection
                }
            }

            is TransportOpenResult.OpenRejected ->
                endCurrent(reason(SessionEndKind.NOT_CONNECTED, "Transport open was rejected"))
        }
    }

    private fun handleOpened(connection: TransportConnection) {
        val generation = currentGeneration
        if (generation == null || connection.generation != generation) {
            closeStale(connection)
            recordDiagnostic(SessionDiagnosticKind.STALE_CALLBACK, "Ignored opened callback from an old session")
            return
        }
        if (connection !== currentConnection) {
            closeStale(connection)
            recordDiagnostic(SessionDiagnosticKind.ADAPTER_VIOLATION, "Transport opened an unknown connection")
            return
        }
        if (currentSnapshot.state != SessionState.CONNECTING || opened) {
            recordDiagnostic(SessionDiagnosticKind.ADAPTER_VIOLATION, "Ignored duplicate opened callback")
            return
        }
        opened = true

        val attachResult = try {
            dependencies.outbound.attach(generation, connection.writer)
        } catch (_: Throwable) {
            recordDiagnostic(SessionDiagnosticKind.DEPENDENCY_FAILURE, "Outbound attach failed")
            AttachResult.AttachRejected
        }
        if (attachResult != AttachResult.AttachAccepted) {
            endCurrent(reason(SessionEndKind.NOT_CONNECTED, "Handshake channel could not be attached"))
            return
        }

        val sendResult = try {
            dependencies.outbound.sendHandshake(
                generation,
                HelloFrame(config.deviceId, ProtocolLimits.protocolVersion),
            )
        } catch (_: Throwable) {
            recordDiagnostic(SessionDiagnosticKind.DEPENDENCY_FAILURE, "Outbound handshake failed")
            HandshakeSendResult.SendRejected
        }
        if (sendResult != HandshakeSendResult.SendAccepted) {
            endCurrent(reason(SessionEndKind.NOT_CONNECTED, "Handshake could not be sent"))
            return
        }

        transition(SessionSnapshot(SessionState.AWAITING_PAIRING, generation, null), null)
        handshakeTimeout = schedule(config.handshakeTimeoutMillis) {
            eventLoop.execute { handleHandshakeTimeout(generation) }
        }
        if (handshakeTimeout == null) {
            endCurrent(reason(SessionEndKind.NOT_CONNECTED, "Handshake timeout could not be scheduled"))
        }
    }

    private fun handleBytes(generation: SessionGeneration, bytes: ByteArray) {
        if (generation != currentGeneration) {
            recordDiagnostic(SessionDiagnosticKind.STALE_CALLBACK, "Ignored bytes from an old session")
            return
        }

        when (currentSnapshot.state) {
            SessionState.CONNECTING -> {
                recordDiagnostic(SessionDiagnosticKind.ADAPTER_VIOLATION, "Ignored bytes before transport opened")
                return
            }

            SessionState.AWAITING_PAIRING,
            SessionState.ACTIVE,
            -> Unit

            SessionState.STOPPED,
            SessionState.RECONNECT_WAIT,
            -> return
        }

        when (val decoded = runCatching { RelayFrameCodec.decode(bytes) }.getOrNull()) {
            null -> handleRejectedFrame(isUnsupportedVersion = false)
            is DecodeResult.Ignored -> Unit
            is DecodeResult.Rejected ->
                handleRejectedFrame(decoded.error.code == ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED)

            is DecodeResult.Decoded -> handleDecodedFrame(decoded.frame)
        }
    }

    private fun handleRejectedFrame(isUnsupportedVersion: Boolean) {
        if (isUnsupportedVersion) {
            endCurrent(reason(SessionEndKind.PROTOCOL_VERSION_UNSUPPORTED, "Protocol version is unsupported"))
            return
        }
        if (currentSnapshot.state == SessionState.AWAITING_PAIRING) {
            endCurrent(reason(SessionEndKind.INVALID_FRAME, "Handshake frame is invalid"))
        } else {
            recordDiagnostic(SessionDiagnosticKind.INVALID_FRAME, "Discarded an invalid active frame")
        }
    }

    private fun handleDecodedFrame(frame: RelayFrame) {
        when (currentSnapshot.state) {
            SessionState.AWAITING_PAIRING -> {
                if (frame is PairedFrame) {
                    activate(frame)
                } else {
                    endCurrent(reason(SessionEndKind.UNSUPPORTED_FRAME, "Frame is not allowed during handshake"))
                }
            }

            SessionState.ACTIVE -> {
                if (frame is PairedFrame || frame is HelloFrame) {
                    endCurrent(reason(SessionEndKind.UNSUPPORTED_FRAME, "Duplicate handshake is not allowed"))
                    return
                }
                val generation = currentGeneration ?: return
                val sessionId = currentSnapshot.sessionId ?: return
                try {
                    dependencies.activeFrameConsumer.accept(ActiveSession(generation, sessionId), frame)
                } catch (_: Throwable) {
                    recordDiagnostic(SessionDiagnosticKind.DEPENDENCY_FAILURE, "Active frame consumer failed")
                }
            }

            else -> Unit
        }
    }

    private fun activate(frame: PairedFrame) {
        val generation = currentGeneration ?: return
        cancelHandshakeTimeout()
        consecutiveFailures = 0
        transition(SessionSnapshot(SessionState.ACTIVE, generation, frame.sessionId), null)
    }

    private fun handleHandshakeTimeout(generation: SessionGeneration) {
        if (generation != currentGeneration || currentSnapshot.state != SessionState.AWAITING_PAIRING) {
            recordDiagnostic(SessionDiagnosticKind.STALE_CALLBACK, "Ignored stale handshake timeout")
            return
        }
        endCurrent(reason(SessionEndKind.HANDSHAKE_TIMEOUT, "Handshake timed out"))
    }

    private fun handleTransportEnded(generation: SessionGeneration, detail: String) {
        if (generation != currentGeneration) {
            recordDiagnostic(SessionDiagnosticKind.STALE_CALLBACK, "Ignored transport callback from an old session")
            return
        }
        endCurrent(reason(SessionEndKind.NOT_CONNECTED, detail))
    }

    private fun endCurrent(endReason: SessionEndReason) {
        val generation = currentGeneration ?: return
        val connection = currentConnection
        val previousState = currentSnapshot.state
        val reconnect = desiredRunning && endReason.kind != SessionEndKind.EXPLICIT_STOP

        currentGeneration = null
        opened = false
        currentSnapshot = SessionSnapshot(
            state = if (reconnect) SessionState.RECONNECT_WAIT else SessionState.STOPPED,
            generation = null,
            sessionId = null,
        )

        cancelHandshakeTimeout()
        safeDependency("Transport close failed") { connection?.close("session ended") }
        safeDependency("Command cleanup failed") { dependencies.commandCleanup.cancel(generation, endReason) }
        safeDependency("Mission cleanup failed") { dependencies.missionCleanup.abort(generation, endReason) }
        safeDependency("Outbound cleanup failed") { dependencies.outbound.discard(generation) }
        currentConnection = null

        if (reconnect) {
            consecutiveFailures += 1
            scheduleRetry()
        }
        enqueueStateEvent(previousState, currentSnapshot, endReason)
    }

    private fun scheduleRetry() {
        val token = RetryToken()
        retryToken = token
        retryCancellation = schedule(retryDelayMillis()) {
            eventLoop.execute { handleRetry(token) }
        }
    }

    private fun handleRetry(token: RetryToken) {
        if (
            token !== retryToken ||
            currentSnapshot.state != SessionState.RECONNECT_WAIT ||
            !desiredRunning
        ) {
            recordDiagnostic(SessionDiagnosticKind.STALE_CALLBACK, "Ignored stale reconnect timer")
            return
        }
        retryToken = null
        retryCancellation = null
        startAttempt()
    }

    private fun retryDelayMillis(): Long {
        var delay = config.reconnectInitialDelayMillis
        repeat((consecutiveFailures - 1).coerceAtLeast(0)) {
            if (delay >= config.reconnectMaxDelayMillis) {
                return config.reconnectMaxDelayMillis
            }
            delay = (delay * 2).coerceAtMost(config.reconnectMaxDelayMillis)
        }
        return delay
    }

    private fun cancelHandshakeTimeout() {
        val cancellation = handshakeTimeout
        handshakeTimeout = null
        safeDependency("Handshake timeout cancellation failed") { cancellation?.cancel() }
    }

    private fun cancelRetry() {
        retryToken = null
        val cancellation = retryCancellation
        retryCancellation = null
        safeDependency("Reconnect cancellation failed") { cancellation?.cancel() }
    }

    private fun schedule(delayMillis: Long, callback: () -> Unit): ScheduledCancellation? = try {
        dependencies.scheduler.schedule(delayMillis, callback)
    } catch (_: Throwable) {
        recordDiagnostic(SessionDiagnosticKind.DEPENDENCY_FAILURE, "Session timer scheduling failed")
        null
    }

    private fun transition(snapshot: SessionSnapshot, endReason: SessionEndReason?) {
        val previousState = currentSnapshot.state
        currentSnapshot = snapshot
        enqueueStateEvent(previousState, snapshot, endReason)
    }

    private fun enqueueStateEvent(
        previousState: SessionState,
        snapshot: SessionSnapshot,
        endReason: SessionEndReason?,
    ) {
        val event = SessionStateEvent(previousState, snapshot, endReason)
        val activeListeners = listeners.filter(ListenerSlot::isActive)
        safeDependency("State notification enqueue failed") {
            dependencies.stateNotifier.enqueue(event, activeListeners)
        }
    }

    private fun closeStale(connection: TransportConnection) {
        safeDependency("Stale transport close failed") { connection.close("stale session") }
    }

    private fun safeDependency(detail: String, action: () -> Unit) {
        try {
            action()
        } catch (_: Throwable) {
            recordDiagnostic(SessionDiagnosticKind.DEPENDENCY_FAILURE, detail)
        }
    }

    private fun recordDiagnostic(kind: SessionDiagnosticKind, detail: String) {
        runCatching {
            dependencies.diagnosticSink.record(SessionDiagnostic(kind, currentSnapshot.state, detail))
        }
    }

    private fun reason(kind: SessionEndKind, detail: String): SessionEndReason =
        SessionEndReason.create(kind, detail)

    private class RetryToken {
        @Suppress("unused")
        private val id: UUID = UUID.randomUUID()
    }

    private class ListenerSlot(
        private val delegate: SessionStateListener,
    ) : SessionStateListener {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private val callbackDepth = ThreadLocal.withInitial { 0 }
        private var active = true
        private var inFlight = 0

        fun isActive(): Boolean = lock.withLock { active }

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

        override fun onStateChanged(event: SessionStateEvent) {
            val shouldNotify = lock.withLock {
                if (active) {
                    inFlight += 1
                    true
                } else {
                    false
                }
            }
            if (!shouldNotify) {
                return
            }

            val previousDepth = callbackDepth.get()
            callbackDepth.set(previousDepth + 1)
            try {
                delegate.onStateChanged(event)
            } finally {
                if (previousDepth == 0) {
                    callbackDepth.remove()
                } else {
                    callbackDepth.set(previousDepth)
                }
                lock.withLock {
                    inFlight -= 1
                    if (inFlight == 0) {
                        idle.signalAll()
                    }
                }
            }
        }
    }

    companion object {
        fun create(config: SessionConfig, dependencies: SessionDependencies): SessionCreationResult {
            val error = validateConfig(config)
            return if (error == null) {
                SessionCreated(ConnectionSession(config, dependencies))
            } else {
                ConfigurationRejected(error)
            }
        }

        private fun validateConfig(config: SessionConfig): String? {
            if (config.endpoint.isBlank()) {
                return "Endpoint is invalid"
            }
            if (validate(HelloFrame(config.deviceId)) is Rejected) {
                return "Device identity is invalid"
            }
            if (config.handshakeTimeoutMillis !in 1_000..60_000) {
                return "Handshake timeout is outside the allowed range"
            }
            if (config.reconnectInitialDelayMillis !in 250..30_000) {
                return "Reconnect initial delay is outside the allowed range"
            }
            if (
                config.reconnectMaxDelayMillis < config.reconnectInitialDelayMillis ||
                config.reconnectMaxDelayMillis > 300_000
            ) {
                return "Reconnect maximum delay is outside the allowed range"
            }
            return null
        }
    }
}
