package com.skycommand.relay.gateway.session

import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.RelayFrame

sealed interface TransportWriteResult {
    data object WriteAccepted : TransportWriteResult

    data object WriteRejected : TransportWriteResult
}

fun interface TransportWriter {
    fun write(bytes: ByteArray): TransportWriteResult
}

sealed interface TransportCloseResult {
    data object CloseRequested : TransportCloseResult

    data object AlreadyClosed : TransportCloseResult
}

interface TransportConnection {
    val generation: SessionGeneration
    val writer: TransportWriter

    fun close(reason: String): TransportCloseResult
}

sealed interface TransportOpenResult {
    data class OpenAccepted(val connection: TransportConnection) : TransportOpenResult

    data class OpenRejected(val reason: String) : TransportOpenResult
}

interface TransportListener {
    fun onOpened(connection: TransportConnection)

    fun onBytes(generation: SessionGeneration, bytes: ByteArray)

    fun onClosed(generation: SessionGeneration, reason: String)

    fun onFailure(generation: SessionGeneration, reason: String)
}

fun interface TransportConnector {
    fun open(
        endpoint: String,
        generation: SessionGeneration,
        listener: TransportListener,
    ): TransportOpenResult
}

sealed interface AttachResult {
    data object AttachAccepted : AttachResult

    data object AttachRejected : AttachResult
}

sealed interface HandshakeSendResult {
    data object SendAccepted : HandshakeSendResult

    data object SendRejected : HandshakeSendResult
}

interface SessionOutbound {
    fun attach(generation: SessionGeneration, writer: TransportWriter): AttachResult

    fun sendHandshake(generation: SessionGeneration, frame: HelloFrame): HandshakeSendResult

    fun discard(generation: SessionGeneration)
}

fun interface ActiveFrameConsumer {
    fun accept(activeSession: ActiveSession, frame: RelayFrame)
}

fun interface CommandSessionCleanup {
    fun cancel(generation: SessionGeneration, reason: SessionEndReason)
}

fun interface MissionSessionCleanup {
    fun abort(generation: SessionGeneration, reason: SessionEndReason)
}

/**
 * A cancellation handle returned by a monotonic scheduler.
 *
 * Production adapters must make cancellation idempotent and must not throw. The
 * session still invalidates its generation or timer token before invoking this
 * method, so a callback that survives a broken cancellation cannot affect the
 * session.
 */
fun interface ScheduledCancellation {
    fun cancel()
}

fun interface MonotonicScheduler {
    fun schedule(delayMillis: Long, callback: () -> Unit): ScheduledCancellation
}

fun interface SessionStateListener {
    fun onStateChanged(event: SessionStateEvent)
}

/**
 * Enqueues state events for deferred delivery. Implementations must not invoke
 * listeners inline on the session event-loop thread.
 */
fun interface OrderedStateNotifier {
    fun enqueue(event: SessionStateEvent, listeners: List<SessionStateListener>)
}

enum class SessionDiagnosticKind {
    ADAPTER_VIOLATION,
    STALE_CALLBACK,
    INVALID_FRAME,
    DEPENDENCY_FAILURE,
    LISTENER_FAILURE,
}

data class SessionDiagnostic(
    val kind: SessionDiagnosticKind,
    val state: SessionState,
    val detail: String,
)

fun interface SessionDiagnosticSink {
    fun record(diagnostic: SessionDiagnostic)
}

data class SessionDependencies(
    val connector: TransportConnector,
    val outbound: SessionOutbound,
    val activeFrameConsumer: ActiveFrameConsumer,
    val commandCleanup: CommandSessionCleanup,
    val missionCleanup: MissionSessionCleanup,
    val scheduler: MonotonicScheduler,
    val stateNotifier: OrderedStateNotifier,
    val diagnosticSink: SessionDiagnosticSink = SessionDiagnosticSink { },
)
