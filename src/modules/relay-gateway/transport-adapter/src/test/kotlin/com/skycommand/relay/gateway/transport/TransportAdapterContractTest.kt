package com.skycommand.relay.gateway.transport

import com.skycommand.relay.gateway.session.ActiveFrameConsumer
import com.skycommand.relay.gateway.session.AttachResult
import com.skycommand.relay.gateway.session.CommandSessionCleanup
import com.skycommand.relay.gateway.session.ConfigurationRejected
import com.skycommand.relay.gateway.session.ConnectionSession
import com.skycommand.relay.gateway.session.HandshakeSendResult
import com.skycommand.relay.gateway.session.MissionSessionCleanup
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.OrderedStateNotifier
import com.skycommand.relay.gateway.session.ScheduledCancellation
import com.skycommand.relay.gateway.session.SessionConfig
import com.skycommand.relay.gateway.session.SessionCreated
import com.skycommand.relay.gateway.session.SessionDependencies
import com.skycommand.relay.gateway.session.SessionDiagnosticSink
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.SessionOutbound
import com.skycommand.relay.gateway.session.TransportWriter
import com.skycommand.relay.gateway.session.TransportConnection
import com.skycommand.relay.gateway.session.TransportListener
import com.skycommand.relay.gateway.session.TransportOpenResult
import com.skycommand.relay.gateway.session.TransportWriteResult
import com.skycommand.relay.protocol.Accepted
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.PairedFrame
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import com.skycommand.relay.protocol.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransportAdapterContractTest {

    @Test
    fun connectsThroughTheSessionTransportSeamAndForwardsBinaryBytesWithItsGeneration() {
        val engine = RecordingSocketEngine()
        val connector = EngineTransportConnector(engine)
        val outbound = RecordingOutbound()
        val consumer = RecordingFrameConsumer()
        val session = createSession(connector, outbound, consumer)

        session.start()
        engine.openCurrent()
        assertEquals(1, outbound.attachments.size)
        assertEquals(TransportWriteResult.WriteAccepted, outbound.attachments.single().second.write(byteArrayOf(4, 5)))
        assertEquals(listOf(byteArrayOf(4, 5).toList()), engine.current.sent.map { it.toList() })

        engine.receiveCurrent(encoded(PairedFrame("desktop-session", null)))
        val payload = encoded(TelemetryFrame(JsonObject(emptyMap()), JsonObject(emptyMap())))
        engine.receiveCurrent(payload)
        payload[0] = 0

        assertEquals(outbound.attachments.single().first, consumer.activeSession.generation)
        assertIs<TelemetryFrame>(consumer.frame)
        session.stop()
        assertEquals(1, engine.current.closeCalls)
    }

    @Test
    fun rejectsMalformedAndNonWebSocketEndpointsWithoutOpeningTheEngine() {
        val engine = RecordingSocketEngine()
        val connector = EngineTransportConnector(engine)
        val listener = RecordingTransportListener()
        val generation = generationForTest()

        listOf("https://desktop.example", "ws:///missing-host", "not a url").forEach { endpoint ->
            assertEquals(
                TransportOpenResult.OpenRejected("Transport endpoint is invalid"),
                connector.open(endpoint, generation, listener),
            )
        }
        assertEquals(0, engine.openCalls)
    }

    @Test
    fun defersSynchronousOpenedCallbacksUntilTheAcceptedConnectionIsActivated() {
        val engine = RecordingSocketEngine().apply { openSynchronously = true }
        val connector = EngineTransportConnector(engine)
        var openReturned = false
        val listener = object : TransportListener {
            var opened = 0

            override fun onOpened(connection: TransportConnection) {
                assertTrue(openReturned, "onOpened must not run from TransportConnector.open")
                opened += 1
            }

            override fun onBytes(generation: SessionGeneration, bytes: ByteArray) = Unit

            override fun onClosed(generation: SessionGeneration, reason: String) = Unit

            override fun onFailure(generation: SessionGeneration, reason: String) = Unit
        }

        val connection = assertIs<TransportOpenResult.OpenAccepted>(
            connector.open("ws://desktop.example/relay", generationForTest(), listener),
        ).connection
        openReturned = true
        connection.enableCallbacks()

        assertEquals(1, listener.opened)
    }

    @Test
    fun enforcesWriterLifecycleAndDeliversOnlyOneTerminalCallback() {
        val engine = RecordingSocketEngine()
        val connector = EngineTransportConnector(engine)
        val listener = RecordingTransportListener()
        val generation = generationForTest()
        val connection = assertIs<TransportOpenResult.OpenAccepted>(
            connector.open("wss://desktop.example/relay", generation, listener),
        ).connection

        connection.enableCallbacks()
        assertEquals(TransportWriteResult.WriteRejected, connection.writer.write(byteArrayOf(1)))
        engine.openCurrent()
        engine.current.sendResult = false
        assertEquals(TransportWriteResult.WriteRejected, connection.writer.write(byteArrayOf(1)))
        engine.current.sendResult = true
        assertEquals(TransportWriteResult.WriteAccepted, connection.writer.write(byteArrayOf(2)))
        engine.textCurrent()
        engine.closedCurrent()
        engine.failedCurrent()

        assertEquals(listOf(generation), listener.opened)
        assertEquals(listOf(generation), listener.closed)
        assertEquals(emptyList(), listener.failed)
        assertEquals(emptyList(), listener.bytes)
        assertEquals(TransportWriteResult.WriteRejected, connection.writer.write(byteArrayOf(3)))
    }

    @Test
    fun containsEngineCloseAndListenerFailuresWithoutLeakingThem() {
        val generation = generationForTest()
        val rejectedEngine = RecordingSocketEngine().apply { throwOnOpen = true }
        assertEquals(
            TransportOpenResult.OpenRejected("Transport connection could not be opened"),
            EngineTransportConnector(rejectedEngine).open("ws://desktop.example/relay", generation, RecordingTransportListener()),
        )

        val engine = RecordingSocketEngine()
        val connection = assertIs<TransportOpenResult.OpenAccepted>(
            EngineTransportConnector(engine).open("ws://desktop.example/relay", generation, ThrowingTransportListener()),
        ).connection
        connection.enableCallbacks()
        engine.openCurrent()
        engine.current.closeFailure = true
        assertEquals(com.skycommand.relay.gateway.session.TransportCloseResult.CloseRequested, connection.close("secret"))
        assertEquals(com.skycommand.relay.gateway.session.TransportCloseResult.AlreadyClosed, connection.close("again"))
        engine.failedCurrent()
    }

    @Test
    fun defensivelyCopiesCallerBytesBeforeWriting() {
        val engine = RecordingSocketEngine()
        val generation = generationForTest()
        val connection = assertIs<TransportOpenResult.OpenAccepted>(
            EngineTransportConnector(engine).open("ws://desktop.example/relay", generation, RecordingTransportListener()),
        ).connection
        connection.enableCallbacks()
        engine.openCurrent()
        val bytes = byteArrayOf(7)
        assertEquals(TransportWriteResult.WriteAccepted, connection.writer.write(bytes))
        bytes[0] = 9
        assertEquals(listOf(listOf(7.toByte())), engine.current.sent.map { it.toList() })
    }

    private fun createSession(
        connector: EngineTransportConnector,
        outbound: RecordingOutbound,
        consumer: RecordingFrameConsumer,
    ): ConnectionSession {
        val result = ConnectionSession.create(
            SessionConfig(endpoint = "ws://desktop.example:8765/relay", deviceId = "phone-1"),
            SessionDependencies(
                connector = connector,
                outbound = outbound,
                activeFrameConsumer = consumer,
                commandCleanup = CommandSessionCleanup { _, _ -> },
                missionCleanup = MissionSessionCleanup { _, _ -> },
                scheduler = MonotonicScheduler { _, _ -> ScheduledCancellation { } },
                stateNotifier = OrderedStateNotifier { _, _ -> },
                diagnosticSink = SessionDiagnosticSink { },
            ),
        )
        return when (result) {
            is SessionCreated -> result.session
            is ConfigurationRejected -> error(result.detail)
        }
    }

    private class RecordingSocketEngine : SocketEngine {
        lateinit var current: RecordingSocket
        private lateinit var callbacks: SocketCallbacks
        var openCalls = 0
        var throwOnOpen = false
        var openSynchronously = false

        override fun open(endpoint: String, callbacks: SocketCallbacks): SocketOpenResult {
            if (throwOnOpen) throw IllegalStateException("engine secret")
            openCalls += 1
            this.callbacks = callbacks
            current = RecordingSocket()
            if (openSynchronously) callbacks.onOpened()
            return SocketOpenResult.Accepted(current)
        }

        fun openCurrent() = callbacks.onOpened()

        fun receiveCurrent(bytes: ByteArray) = callbacks.onBinary(bytes)

        fun textCurrent() = callbacks.onText()

        fun closedCurrent() = callbacks.onClosed()

        fun failedCurrent() = callbacks.onFailure()
    }

    private class RecordingSocket : SocketHandle {
        val sent = mutableListOf<ByteArray>()
        var closeCalls = 0
        var sendResult = true
        var closeFailure = false

        override fun send(bytes: ByteArray): Boolean {
            sent += bytes
            return sendResult
        }

        override fun close(): Boolean {
            if (closeFailure) throw IllegalStateException("close secret")
            closeCalls += 1
            return true
        }
    }

    private class RecordingOutbound : SessionOutbound {
        val attachments = mutableListOf<Pair<SessionGeneration, TransportWriter>>()

        override fun attach(generation: SessionGeneration, writer: TransportWriter): AttachResult {
            attachments += generation to writer
            return AttachResult.AttachAccepted
        }

        override fun sendHandshake(
            generation: SessionGeneration,
            frame: com.skycommand.relay.protocol.HelloFrame,
        ): HandshakeSendResult = HandshakeSendResult.SendAccepted

        override fun discard(generation: SessionGeneration) = Unit
    }

    private class RecordingFrameConsumer : ActiveFrameConsumer {
        lateinit var activeSession: com.skycommand.relay.gateway.session.ActiveSession
        lateinit var frame: RelayFrame

        override fun accept(activeSession: com.skycommand.relay.gateway.session.ActiveSession, frame: RelayFrame) {
            this.activeSession = activeSession
            this.frame = frame
        }
    }

    private class RecordingTransportListener : TransportListener {
        val opened = mutableListOf<SessionGeneration>()
        val bytes = mutableListOf<Pair<SessionGeneration, List<Byte>>>()
        val closed = mutableListOf<SessionGeneration>()
        val failed = mutableListOf<SessionGeneration>()

        override fun onOpened(connection: TransportConnection) {
            opened += connection.generation
        }

        override fun onBytes(generation: SessionGeneration, bytes: ByteArray) {
            this.bytes += generation to bytes.toList()
        }

        override fun onClosed(generation: SessionGeneration, reason: String) {
            closed += generation
        }

        override fun onFailure(generation: SessionGeneration, reason: String) {
            failed += generation
        }
    }

    private class ThrowingTransportListener : TransportListener {
        override fun onOpened(connection: TransportConnection) = error("listener secret")

        override fun onBytes(generation: SessionGeneration, bytes: ByteArray) = error("listener secret")

        override fun onClosed(generation: SessionGeneration, reason: String) = error("listener secret")

        override fun onFailure(generation: SessionGeneration, reason: String) = error("listener secret")
    }

    private fun generationForTest(): SessionGeneration {
        val engine = RecordingSocketEngine()
        val outbound = RecordingOutbound()
        val session = createSession(EngineTransportConnector(engine), outbound, RecordingFrameConsumer())
        session.start()
        engine.openCurrent()
        return outbound.attachments.single().first
    }

    private fun encoded(frame: RelayFrame): ByteArray = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value
}
