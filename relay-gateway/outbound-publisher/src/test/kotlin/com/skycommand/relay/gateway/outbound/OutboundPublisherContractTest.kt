package com.skycommand.relay.gateway.outbound

import com.skycommand.relay.gateway.session.ActiveFrameConsumer
import com.skycommand.relay.gateway.session.AttachResult
import com.skycommand.relay.gateway.session.CommandSessionCleanup
import com.skycommand.relay.gateway.session.ConfigurationRejected
import com.skycommand.relay.gateway.session.ConnectionSession
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.OrderedStateNotifier
import com.skycommand.relay.gateway.session.SessionConfig
import com.skycommand.relay.gateway.session.SessionCreated
import com.skycommand.relay.gateway.session.SessionDependencies
import com.skycommand.relay.gateway.session.SessionDiagnosticSink
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.gateway.session.TransportConnection
import com.skycommand.relay.gateway.session.TransportConnector
import com.skycommand.relay.gateway.session.TransportListener
import com.skycommand.relay.gateway.session.TransportOpenResult
import com.skycommand.relay.gateway.session.TransportWriter
import com.skycommand.relay.gateway.session.TransportWriteResult
import com.skycommand.relay.gateway.session.MissionSessionCleanup
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.HandshakeSendResult
import com.skycommand.relay.protocol.DecodeResult
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.CommandResultFrame
import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.MissionBeginFrame
import com.skycommand.relay.protocol.MissionChunkFrame
import com.skycommand.relay.protocol.MissionCompleteFrame
import com.skycommand.relay.protocol.MissionResultFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import com.skycommand.relay.protocol.TelemetryFrame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OutboundPublisherContractTest {

    @Test
    fun sessionAttachmentSendsExactlyOneProtocolEncodedHello() {
        val writer = RecordingWriter()
        val connector = RecordingConnector(writer)
        val publisher = OutboundPublisher()
        val session = createSession(connector, publisher)

        session.start()
        connector.openCurrent()
        connector.openCurrent()

        assertEquals(1, writer.writes.size)
        assertEquals(
            HelloFrame("phone-1"),
            assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(writer.writes.single())).frame,
        )
    }

    @Test
    fun activeSessionPublishesAllowedTelemetryThroughTheAttachedWriter() {
        val writer = RecordingWriter()
        val connector = RecordingConnector(writer)
        val publisher = OutboundPublisher()
        val consumer = RecordingActiveConsumer()
        val session = createSession(connector, publisher, consumer)
        val telemetry = TelemetryFrame(
            payload = JsonObject(mapOf("armed" to JsonBoolean(false))),
            capabilities = JsonObject(emptyMap()),
        )

        session.start()
        connector.openCurrent()
        connector.receiveCurrent(encoded(com.skycommand.relay.protocol.PairedFrame("desktop-session", null)))
        connector.receiveCurrent(encoded(CommandFrame("command-1", "telemetry.read", JsonObject(emptyMap()))))

        assertEquals(PublishResult.Delivered, publisher.publish(consumer.activeSession, telemetry))
        assertEquals(2, writer.writes.size)
        assertEquals(
            telemetry,
            assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(writer.writes.last())).frame,
        )
    }

    @Test
    fun publishesEveryAllowedBusinessFrameInCallOrder() {
        val fixture = activeFixture()
        val frames = listOf(
            TelemetryFrame(JsonObject(emptyMap()), JsonObject(emptyMap())),
            CommandResultFrame("command-1", true, "done"),
            MissionResultFrame("mission-1", false, "rejected"),
        )

        frames.forEach { frame ->
            assertEquals(PublishResult.Delivered, fixture.publisher.publish(fixture.consumer.activeSession, frame))
        }

        assertEquals(4, fixture.writer.writes.size)
        assertEquals(frames, fixture.writer.writes.drop(1).map(::decode))
    }

    @Test
    fun rejectsFramesThatCannotTravelFromPhoneToDesktop() {
        val fixture = activeFixture()
        val forbidden = listOf(
            HelloFrame("phone-1"),
            com.skycommand.relay.protocol.PairedFrame("desktop", null),
            CommandFrame("command-1", "telemetry.read", JsonObject(emptyMap())),
            MissionBeginFrame("mission-1", "route.kmz", 1, "0".repeat(64)),
            MissionChunkFrame("mission-1", byteArrayOf(1)),
            MissionCompleteFrame("mission-1"),
        )

        forbidden.forEach { frame ->
            assertEquals(
                PublishResult.Rejected(PublishRejectionKind.DIRECTION_NOT_ALLOWED),
                fixture.publisher.publish(fixture.consumer.activeSession, frame),
            )
        }

        assertEquals(1, fixture.writer.writes.size)
    }

    @Test
    fun rejectsStaleSessionsAndInvalidOrRejectedWritesWithoutLeakingThem() {
        val fixture = activeFixture()
        val validTelemetry = TelemetryFrame(JsonObject(emptyMap()), JsonObject(emptyMap()))
        val invalidTelemetry = TelemetryFrame(
            JsonObject(mapOf("invalid" to JsonNumber("NaN"))),
            JsonObject(emptyMap()),
        )

        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.ENCODING_REJECTED),
            fixture.publisher.publish(fixture.consumer.activeSession, invalidTelemetry),
        )
        fixture.writer.nextResult = TransportWriteResult.WriteRejected
        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.WRITE_REJECTED),
            fixture.publisher.publish(fixture.consumer.activeSession, validTelemetry),
        )
        fixture.writer.nextResult = TransportWriteResult.WriteAccepted
        fixture.writer.failure = IllegalStateException("writer secret")
        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.WRITE_REJECTED),
            fixture.publisher.publish(fixture.consumer.activeSession, validTelemetry),
        )
        fixture.writer.failure = null

        fixture.session.stop()

        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.STALE_SESSION),
            fixture.publisher.publish(fixture.consumer.activeSession, validTelemetry),
        )
        assertEquals(1, fixture.writer.writes.size)
    }

    @Test
    fun concurrentPublishersNeverWriteToTheTransportInParallel() {
        val fixture = activeFixture()
        val telemetry = TelemetryFrame(JsonObject(emptyMap()), JsonObject(emptyMap()))
        val executor = Executors.newFixedThreadPool(8)

        try {
            val futures = List(32) {
                executor.submit {
                    repeat(100) {
                        assertEquals(
                            PublishResult.Delivered,
                            fixture.publisher.publish(fixture.consumer.activeSession, telemetry),
                        )
                    }
                }
            }
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, fixture.writer.maximumConcurrentWrites.get())
        assertEquals(3_201, fixture.writer.writes.size)
    }

    @Test
    fun aNewGenerationCannotReplaceTheAttachedWriterBeforeTheOldOneIsDiscarded() {
        val publisher = OutboundPublisher()
        val firstWriter = RecordingWriter()
        val firstConnector = RecordingConnector(firstWriter)
        val firstSession = createSession(firstConnector, publisher)
        val secondWriter = RecordingWriter()
        val secondConnector = RecordingConnector(secondWriter)
        val secondSession = createSession(secondConnector, publisher)

        firstSession.start()
        firstConnector.openCurrent()
        secondSession.start()
        secondConnector.openCurrent()

        assertEquals(1, firstWriter.writes.size)
        assertEquals(0, secondWriter.writes.size)
        assertEquals(SessionState.RECONNECT_WAIT, secondSession.snapshot().state)

        firstSession.stop()
        secondSession.start()
        secondConnector.openCurrent()

        assertEquals(1, secondWriter.writes.size)
    }

    @Test
    fun repeatedAttachmentIsSilentAndDiscardedHandshakeCannotBeRevived() {
        val writer = RecordingWriter()
        val connector = RecordingConnector(writer)
        val publisher = OutboundPublisher()
        val session = createSession(connector, publisher)

        session.start()
        connector.openCurrent()
        val generation = connector.currentGeneration()

        assertEquals(AttachResult.AttachAccepted, publisher.attach(generation, writer))
        assertEquals(1, writer.writes.size)

        session.stop()

        assertEquals(
            HandshakeSendResult.SendRejected,
            publisher.sendHandshake(generation, HelloFrame("phone-1")),
        )
        assertEquals(1, writer.writes.size)
    }

    private fun activeFixture(): ActiveFixture {
        val writer = RecordingWriter()
        val connector = RecordingConnector(writer)
        val publisher = OutboundPublisher()
        val consumer = RecordingActiveConsumer()
        val session = createSession(connector, publisher, consumer)
        session.start()
        connector.openCurrent()
        connector.receiveCurrent(encoded(com.skycommand.relay.protocol.PairedFrame("desktop-session", null)))
        connector.receiveCurrent(encoded(CommandFrame("command-1", "telemetry.read", JsonObject(emptyMap()))))
        return ActiveFixture(session, publisher, consumer, writer)
    }

    private fun createSession(
        connector: RecordingConnector,
        publisher: OutboundPublisher,
        consumer: ActiveFrameConsumer = ActiveFrameConsumer { _, _ -> },
    ): ConnectionSession {
        val result = ConnectionSession.create(
            SessionConfig(endpoint = "ws://desktop/relay", deviceId = "phone-1"),
            SessionDependencies(
                connector = connector,
                outbound = publisher,
                activeFrameConsumer = consumer,
                commandCleanup = CommandSessionCleanup { _, _ -> },
                missionCleanup = MissionSessionCleanup { _, _ -> },
                scheduler = MonotonicScheduler { _, _ -> com.skycommand.relay.gateway.session.ScheduledCancellation { } },
                stateNotifier = OrderedStateNotifier { _, _ -> },
                diagnosticSink = SessionDiagnosticSink { },
            ),
        )
        return when (result) {
            is SessionCreated -> result.session
            is ConfigurationRejected -> error(result.detail)
        }
    }

    private class RecordingConnector(
        private val writer: RecordingWriter,
    ) : TransportConnector {
        private lateinit var connection: RecordingConnection

        override fun open(
            endpoint: String,
            generation: SessionGeneration,
            listener: TransportListener,
        ): TransportOpenResult {
            connection = RecordingConnection(generation, writer, listener)
            return TransportOpenResult.OpenAccepted(connection)
        }

        fun openCurrent() = connection.open()

        fun receiveCurrent(bytes: ByteArray) = connection.receive(bytes)

        fun currentGeneration(): SessionGeneration = connection.generation
    }

    private class RecordingConnection(
        override val generation: SessionGeneration,
        override val writer: TransportWriter,
        private val listener: TransportListener,
    ) : TransportConnection {
        fun open() = listener.onOpened(this)

        fun receive(bytes: ByteArray) = listener.onBytes(generation, bytes)

        override fun close(reason: String) = com.skycommand.relay.gateway.session.TransportCloseResult.CloseRequested
    }

    private class RecordingWriter : TransportWriter {
        val writes = mutableListOf<ByteArray>()
        val maximumConcurrentWrites = AtomicInteger()
        var nextResult: TransportWriteResult = TransportWriteResult.WriteAccepted
        var failure: RuntimeException? = null
        private val concurrentWrites = AtomicInteger()

        override fun write(bytes: ByteArray): TransportWriteResult {
            val activeWrites = concurrentWrites.incrementAndGet()
            maximumConcurrentWrites.accumulateAndGet(activeWrites, ::maxOf)
            try {
                failure?.let { throw it }
                if (nextResult != TransportWriteResult.WriteAccepted) {
                    return nextResult
                }
                writes += bytes.copyOf()
                return TransportWriteResult.WriteAccepted
            } finally {
                concurrentWrites.decrementAndGet()
            }
        }
    }

    private class RecordingActiveConsumer : ActiveFrameConsumer {
        lateinit var activeSession: com.skycommand.relay.gateway.session.ActiveSession

        override fun accept(
            activeSession: com.skycommand.relay.gateway.session.ActiveSession,
            frame: com.skycommand.relay.protocol.RelayFrame,
        ) {
            this.activeSession = activeSession
        }
    }

    private fun encoded(frame: com.skycommand.relay.protocol.RelayFrame): ByteArray =
        assertIs<com.skycommand.relay.protocol.Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value

    private fun decode(bytes: ByteArray): com.skycommand.relay.protocol.RelayFrame =
        assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(bytes)).frame

    private data class ActiveFixture(
        val session: ConnectionSession,
        val publisher: OutboundPublisher,
        val consumer: RecordingActiveConsumer,
        val writer: RecordingWriter,
    )
}
