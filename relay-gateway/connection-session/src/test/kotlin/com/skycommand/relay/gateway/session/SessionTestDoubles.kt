package com.skycommand.relay.gateway.session

import com.skycommand.relay.protocol.Accepted
import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.PairedFrame
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs

internal class SessionFixture private constructor(
    val session: ConnectionSession,
    val connector: RecordingConnector,
    val outbound: RecordingOutbound,
    val consumer: RecordingFrameConsumer,
    val commandCleanup: RecordingCommandCleanup,
    val missionCleanup: RecordingMissionCleanup,
    val scheduler: ManualScheduler,
    val notifier: RecordingStateNotifier,
    val diagnostics: RecordingDiagnosticSink,
    val order: MutableList<String>,
) {
    companion object {
        fun create(
            endpoint: String = "ws://192.168.1.10:8765/relay",
            deviceId: String = "android-device",
            handshakeTimeoutMillis: Long = 10_000,
            reconnectInitialDelayMillis: Long = 1_000,
            reconnectMaxDelayMillis: Long = 30_000,
        ): SessionFixture {
            val order = mutableListOf<String>()
            val connector = RecordingConnector(order)
            val outbound = RecordingOutbound(order)
            val consumer = RecordingFrameConsumer()
            val commandCleanup = RecordingCommandCleanup(order)
            val missionCleanup = RecordingMissionCleanup(order)
            val scheduler = ManualScheduler()
            val notifier = RecordingStateNotifier(order)
            val diagnostics = RecordingDiagnosticSink()
            val result = createResult(
                endpoint = endpoint,
                deviceId = deviceId,
                handshakeTimeoutMillis = handshakeTimeoutMillis,
                reconnectInitialDelayMillis = reconnectInitialDelayMillis,
                reconnectMaxDelayMillis = reconnectMaxDelayMillis,
                connector = connector,
                outbound = outbound,
                consumer = consumer,
                commandCleanup = commandCleanup,
                missionCleanup = missionCleanup,
                scheduler = scheduler,
                notifier = notifier,
                diagnostics = diagnostics,
            )
            return SessionFixture(
                session = (result as SessionCreated).session,
                connector = connector,
                outbound = outbound,
                consumer = consumer,
                commandCleanup = commandCleanup,
                missionCleanup = missionCleanup,
                scheduler = scheduler,
                notifier = notifier,
                diagnostics = diagnostics,
                order = order,
            )
        }

        fun createResult(
            endpoint: String = "ws://192.168.1.10:8765/relay",
            deviceId: String = "android-device",
            handshakeTimeoutMillis: Long = 10_000,
            reconnectInitialDelayMillis: Long = 1_000,
            reconnectMaxDelayMillis: Long = 30_000,
            connector: RecordingConnector = RecordingConnector(mutableListOf()),
            outbound: RecordingOutbound = RecordingOutbound(mutableListOf()),
            consumer: RecordingFrameConsumer = RecordingFrameConsumer(),
            commandCleanup: RecordingCommandCleanup = RecordingCommandCleanup(mutableListOf()),
            missionCleanup: RecordingMissionCleanup = RecordingMissionCleanup(mutableListOf()),
            scheduler: ManualScheduler = ManualScheduler(),
            notifier: RecordingStateNotifier = RecordingStateNotifier(mutableListOf()),
            diagnostics: RecordingDiagnosticSink = RecordingDiagnosticSink(),
        ): SessionCreationResult = ConnectionSession.create(
            SessionConfig(
                endpoint = endpoint,
                deviceId = deviceId,
                handshakeTimeoutMillis = handshakeTimeoutMillis,
                reconnectInitialDelayMillis = reconnectInitialDelayMillis,
                reconnectMaxDelayMillis = reconnectMaxDelayMillis,
            ),
            SessionDependencies(
                connector = connector,
                outbound = outbound,
                activeFrameConsumer = consumer,
                commandCleanup = commandCleanup,
                missionCleanup = missionCleanup,
                scheduler = scheduler,
                stateNotifier = notifier,
                diagnosticSink = diagnostics,
            )
        )
    }

    fun becomeActive(sessionId: String = "desktop-session") {
        session.start()
        connector.current.open()
        connector.current.receive(encoded(PairedFrame(sessionId, null)))
    }
}

internal class RecordingConnector(private val order: MutableList<String>) : TransportConnector {
    private val mutableAttempts = mutableListOf<FakeTransportConnection>()
    val openCalls = mutableListOf<Pair<String, SessionGeneration>>()
    val attempts: List<FakeTransportConnection>
        get() = mutableAttempts.toList()

    var rejectNextReason: String? = null

    val openCount: Int
        get() = mutableAttempts.size

    val current: FakeTransportConnection
        get() = mutableAttempts.last()

    override fun open(
        endpoint: String,
        generation: SessionGeneration,
        listener: TransportListener,
    ): TransportOpenResult {
        openCalls += endpoint to generation
        rejectNextReason?.let {
            rejectNextReason = null
            return TransportOpenResult.OpenRejected(it)
        }
        return FakeTransportConnection(endpoint, generation, listener, order).also(mutableAttempts::add)
            .let(TransportOpenResult::OpenAccepted)
    }
}

internal class FakeTransportConnection(
    val endpoint: String,
    override val generation: SessionGeneration,
    private val listener: TransportListener,
    private val order: MutableList<String>,
) : TransportConnection {
    override val writer = RecordingWriter()
    var closeCount = 0
        private set
    var closeFailure: RuntimeException? = null

    fun open() = listener.onOpened(this)

    fun receive(bytes: ByteArray) = listener.onBytes(generation, bytes)

    fun closed(reason: String = "closed") = listener.onClosed(generation, reason)

    fun fail(reason: String = "failed") = listener.onFailure(generation, reason)

    override fun close(reason: String): TransportCloseResult {
        closeFailure?.let { throw it }
        return if (closeCount++ == 0) {
            order += "close"
            TransportCloseResult.CloseRequested
        } else {
            TransportCloseResult.AlreadyClosed
        }
    }
}

internal class RecordingWriter : TransportWriter {
    val writes = mutableListOf<ByteArray>()

    override fun write(bytes: ByteArray): TransportWriteResult {
        writes += bytes.copyOf()
        return TransportWriteResult.WriteAccepted
    }
}

internal class RecordingOutbound(private val order: MutableList<String>) : SessionOutbound {
    var attachResult: AttachResult = AttachResult.AttachAccepted
    var sendResult: HandshakeSendResult = HandshakeSendResult.SendAccepted
    var discardFailure: RuntimeException? = null
    val attachments = mutableListOf<Pair<SessionGeneration, TransportWriter>>()
    val handshakes = mutableListOf<Pair<SessionGeneration, HelloFrame>>()
    val discarded = mutableListOf<SessionGeneration>()

    override fun attach(generation: SessionGeneration, writer: TransportWriter): AttachResult {
        attachments += generation to writer
        return attachResult
    }

    override fun sendHandshake(generation: SessionGeneration, frame: HelloFrame): HandshakeSendResult {
        handshakes += generation to frame
        return sendResult
    }

    override fun discard(generation: SessionGeneration) {
        discarded += generation
        order += "outbound"
        discardFailure?.let { throw it }
    }
}

internal class RecordingFrameConsumer : ActiveFrameConsumer {
    val accepted = mutableListOf<Pair<ActiveSession, RelayFrame>>()
    var failure: RuntimeException? = null

    override fun accept(activeSession: ActiveSession, frame: RelayFrame) {
        failure?.let { throw it }
        accepted += activeSession to frame
    }
}

internal class RecordingCommandCleanup(private val order: MutableList<String>) : CommandSessionCleanup {
    val calls = mutableListOf<Pair<SessionGeneration, SessionEndReason>>()
    var failure: RuntimeException? = null

    override fun cancel(generation: SessionGeneration, reason: SessionEndReason) {
        calls += generation to reason
        order += "commands"
        failure?.let { throw it }
    }
}

internal class RecordingMissionCleanup(private val order: MutableList<String>) : MissionSessionCleanup {
    val calls = mutableListOf<Pair<SessionGeneration, SessionEndReason>>()
    var failure: RuntimeException? = null

    override fun abort(generation: SessionGeneration, reason: SessionEndReason) {
        calls += generation to reason
        order += "mission"
        failure?.let { throw it }
    }
}

internal class ManualScheduler : MonotonicScheduler {
    data class Task(
        val delayMillis: Long,
        val callback: () -> Unit,
        var cancelled: Boolean = false,
        var fired: Boolean = false,
        var cancelAttempts: Int = 0,
        var cancellationFailure: RuntimeException? = null,
    )

    val tasks = mutableListOf<Task>()

    override fun schedule(delayMillis: Long, callback: () -> Unit): ScheduledCancellation {
        val task = Task(delayMillis, callback)
        tasks += task
        return ScheduledCancellation {
            task.cancelAttempts += 1
            task.cancellationFailure?.let { throw it }
            task.cancelled = true
        }
    }

    fun fire(task: Task) {
        task.fired = true
        task.callback()
    }

    fun fireNextActive(): Task = tasks.first { !it.cancelled && !it.fired }.also(::fire)
}

internal class RecordingStateNotifier(private val order: MutableList<String>) : OrderedStateNotifier {
    data class Pending(
        val event: SessionStateEvent,
        val listeners: List<SessionStateListener>,
    )

    val pending = mutableListOf<Pending>()

    val events: List<SessionStateEvent>
        get() = pending.map(Pending::event)

    override fun enqueue(event: SessionStateEvent, listeners: List<SessionStateListener>) {
        pending += Pending(event, listeners)
        order += "notify"
    }

    fun drain() {
        val current = pending.toList()
        pending.clear()
        current.forEach { pendingEvent ->
            pendingEvent.listeners.forEach { listener -> listener.onStateChanged(pendingEvent.event) }
        }
    }
}

internal class RecordingDiagnosticSink : SessionDiagnosticSink {
    val diagnostics = CopyOnWriteArrayList<SessionDiagnostic>()

    override fun record(diagnostic: SessionDiagnostic) {
        diagnostics += diagnostic
    }
}

internal fun encoded(frame: RelayFrame): ByteArray =
    assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value
