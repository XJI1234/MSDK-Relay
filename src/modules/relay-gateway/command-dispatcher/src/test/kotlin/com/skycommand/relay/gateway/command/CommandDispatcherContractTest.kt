package com.skycommand.relay.gateway.command

import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.ActiveFrameConsumer
import com.skycommand.relay.gateway.session.ActiveSession
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
import com.skycommand.relay.gateway.session.TransportCloseResult
import com.skycommand.relay.gateway.session.TransportConnection
import com.skycommand.relay.gateway.session.TransportConnector
import com.skycommand.relay.gateway.session.TransportListener
import com.skycommand.relay.gateway.session.TransportOpenResult
import com.skycommand.relay.gateway.session.TransportWriteResult
import com.skycommand.relay.gateway.session.TransportWriter
import com.skycommand.relay.protocol.Accepted
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.CommandResultFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.PairedFrame
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import com.skycommand.relay.protocol.TelemetryFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandDispatcherContractTest {

    @Test
    fun registeredCommandCompletesWithItsOriginalId() {
        val results = RecordingResultPublisher()
        val dispatcher = CommandDispatcher(results)
        val handler = CommandHandler { _, completion -> completion.succeed("ready") }
        assertEquals(RegistrationResult.Registered, dispatcher.register("telemetry.read", handler))
        val connector = RecordingConnector()
        val session = createSession(connector, dispatcher)

        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        connector.receive(encoded(CommandFrame("command-42", "telemetry.read", JsonObject(emptyMap()))))

        assertEquals(
            CommandResultFrame("command-42", true, "ready"),
            results.frames.single().second,
        )
    }

    @Test
    fun publishesAnOptionalStructuredCommandResultWithoutPuttingItInDetail() {
        val results = RecordingResultPublisher()
        val dispatcher = CommandDispatcher(results)
        val result = JsonObject(mapOf("domain" to JsonString("camera")))
        dispatcher.register("telemetry.read", CommandHandler { _, completion -> completion.succeed("Settings read", result) })
        val connector = RecordingConnector()
        val session = createSession(connector, dispatcher)

        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        connector.receive(encoded(CommandFrame("settings-1", "telemetry.read", JsonObject(emptyMap()))))

        assertEquals(CommandResultFrame("settings-1", true, "Settings read", result), results.frames.single().second)
    }

    @Test
    fun rejectsUnknownAndForbiddenCommandNamesWithoutCallingAHandler() {
        val results = RecordingResultPublisher()
        val dispatcher = CommandDispatcher(results)
        val connector = RecordingConnector()
        val consumer = CapturingConsumer()
        val session = createSession(connector, dispatcher, consumer)

        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        captureActiveSession(connector)

        val unknown = CommandFrame("unknown", "virtual-stick.enable", JsonObject(emptyMap()))
        assertEquals(
            DispatchResult.DispatchRejected(DispatchRejectionKind.UNKNOWN_COMMAND),
            dispatcher.dispatch(consumer.activeSession, unknown),
        )
        assertEquals(CommandResultFrame("unknown", false, "Command is not available"), results.frames.single().second)
    }

    @Test
    fun rejectsDuplicateRegistrationAndOnlyUnregisteredFutureCommands() {
        val dispatcher = CommandDispatcher(RecordingResultPublisher())
        val handler = CommandHandler { _, completion -> completion.succeed("ok") }

        assertEquals(RegistrationResult.Registered, dispatcher.register("telemetry.read", handler))
        assertEquals(RegistrationResult.RegistrationRejected, dispatcher.register("telemetry.read", handler))
        assertEquals(UnregistrationResult.Removed, dispatcher.unregister("telemetry.read"))
        assertEquals(UnregistrationResult.NotRegistered, dispatcher.unregister("telemetry.read"))
        assertEquals(RegistrationResult.RegistrationRejected, dispatcher.register("virtual-stick.enable", handler))
    }

    @Test
    fun acceptsEveryCommandInTheFixedV1Directory() {
        val dispatcher = CommandDispatcher(RecordingResultPublisher())
        val handler = CommandHandler { _, completion -> completion.succeed() }
        val names = listOf(
            "telemetry.read",
            "pairing.start",
            "pairing.stop",
            "pairing.status",
            "wayline.upload",
            "wayline.start",
            "wayline.pause",
            "wayline.resume",
            "wayline.stop",
            "live-stream.start",
            "live-stream.stop",
            "flight.takeoff",
            "flight.land",
            "flight.return-home",
            "device.settings.camera.read",
            "device.settings.camera.write",
            "device.settings.transmission.read",
            "device.settings.transmission.write",
        )

        names.forEach { name ->
            assertEquals(RegistrationResult.Registered, dispatcher.register(name, handler), name)
        }
        assertEquals(RegistrationResult.RegistrationRejected, dispatcher.register("wayline.generate", handler))
    }

    @Test
    fun handlerExceptionAndInvalidDetailBecomeFixedSafeFailures() {
        val results = RecordingResultPublisher()
        val dispatcher = CommandDispatcher(results)
        val connector = RecordingConnector()
        val consumer = CapturingConsumer()
        val session = createSession(connector, dispatcher, consumer)
        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        captureActiveSession(connector)

        dispatcher.register("telemetry.read", CommandHandler { _, completion -> completion.succeed("bad\nline") })
        dispatcher.dispatch(consumer.activeSession, CommandFrame("invalid", "telemetry.read", JsonObject(emptyMap())))
        dispatcher.unregister("telemetry.read")
        dispatcher.register("telemetry.read", CommandHandler { _, _ -> error("secret stack") })
        dispatcher.dispatch(consumer.activeSession, CommandFrame("thrown", "telemetry.read", JsonObject(emptyMap())))

        assertEquals(
            listOf(
                CommandResultFrame("invalid", false, "Command result is invalid"),
                CommandResultFrame("thrown", false, "Command failed"),
            ),
            results.frames.map { it.second },
        )
        assertTrue(results.frames.none { it.second.detail.contains("secret") })
    }

    @Test
    fun duplicateInFlightIdRunsOnceAndLateCompletionAfterStopIsDropped() {
        val results = RecordingResultPublisher()
        val completions = mutableListOf<CommandCompletion>()
        val dispatcher = CommandDispatcher(results)
        val connector = RecordingConnector()
        val consumer = CapturingConsumer()
        val session = createSession(connector, dispatcher, consumer)
        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        captureActiveSession(connector)
        dispatcher.register("telemetry.read", CommandHandler { _, completion -> completions += completion })
        val command = CommandFrame("same", "telemetry.read", JsonObject(emptyMap()))

        assertEquals(DispatchResult.DispatchAccepted, dispatcher.dispatch(consumer.activeSession, command))
        assertEquals(DispatchResult.DuplicateInFlight, dispatcher.dispatch(consumer.activeSession, command))
        assertEquals(1, completions.size)
        session.stop()
        completions.single().succeed("too late")

        assertTrue(results.frames.isEmpty())
    }

    @Test
    fun limitsPendingCommandsPerGenerationAndReleasesCapacityAfterCompletion() {
        val results = RecordingResultPublisher()
        val completions = mutableListOf<CommandCompletion>()
        val dispatcher = CommandDispatcher(results)
        val connector = RecordingConnector()
        val consumer = CapturingConsumer()
        val session = createSession(connector, dispatcher, consumer)
        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        captureActiveSession(connector)
        dispatcher.register("telemetry.read", CommandHandler { _, completion -> completions += completion })

        repeat(64) { index ->
            assertEquals(
                DispatchResult.DispatchAccepted,
                dispatcher.dispatch(
                    consumer.activeSession,
                    CommandFrame("pending-$index", "telemetry.read", JsonObject(emptyMap())),
                ),
            )
        }
        assertEquals(
            DispatchResult.DispatchRejected(DispatchRejectionKind.CAPACITY_EXCEEDED),
            dispatcher.dispatch(consumer.activeSession, CommandFrame("overflow", "telemetry.read", JsonObject(emptyMap()))),
        )
        completions.first().succeed("released")
        assertEquals(
            DispatchResult.DispatchAccepted,
            dispatcher.dispatch(consumer.activeSession, CommandFrame("overflow", "telemetry.read", JsonObject(emptyMap()))),
        )
        assertEquals(
            listOf(
                CommandResultFrame("overflow", false, "Too many commands are pending"),
                CommandResultFrame("pending-0", true, "released"),
            ),
            results.frames.map { it.second },
        )
    }

    @Test
    fun publisherCallbacksNeverRunWhileTheDispatcherLockIsHeld() {
        lateinit var dispatcher: CommandDispatcher
        val registrationFinished = CountDownLatch(1)
        val publisher = CommandResultPublisher { _, _ ->
            thread {
                dispatcher.register("telemetry.read", CommandHandler { _, completion -> completion.succeed() })
                registrationFinished.countDown()
            }
            assertTrue(registrationFinished.await(250, TimeUnit.MILLISECONDS))
            PublishResult.Delivered
        }
        dispatcher = CommandDispatcher(publisher)
        val connector = RecordingConnector()
        val consumer = CapturingConsumer()
        val session = createSession(connector, dispatcher, consumer)
        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        captureActiveSession(connector)

        dispatcher.dispatch(
            consumer.activeSession,
            CommandFrame("unknown", "not-registered", JsonObject(emptyMap())),
        )
    }

    @Test
    fun concurrentCompletionsForOneCommandPublishExactlyOneResult() {
        val results = RecordingResultPublisher()
        val completions = mutableListOf<CommandCompletion>()
        val dispatcher = CommandDispatcher(results)
        val connector = RecordingConnector()
        val consumer = CapturingConsumer()
        val session = createSession(connector, dispatcher, consumer)
        session.start()
        connector.openCurrent()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        captureActiveSession(connector)
        dispatcher.register("telemetry.read", CommandHandler { _, completion -> completions += completion })
        dispatcher.dispatch(consumer.activeSession, CommandFrame("race", "telemetry.read", JsonObject(emptyMap())))
        val completion = completions.single()
        val executor = Executors.newFixedThreadPool(8)

        try {
            val futures = List(32) { index ->
                executor.submit {
                    if (index % 2 == 0) completion.succeed("success-$index") else completion.reject("rejected-$index")
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.frames.size)
        assertEquals("race", results.frames.single().second.id)
    }

    private fun createSession(
        connector: RecordingConnector,
        dispatcher: CommandDispatcher,
        consumer: ActiveFrameConsumer = ActiveFrameConsumer { activeSession, frame ->
            if (frame is CommandFrame) {
                dispatcher.dispatch(activeSession, frame)
            }
        },
    ): ConnectionSession {
        val result = ConnectionSession.create(
            SessionConfig(endpoint = "ws://desktop/relay", deviceId = "phone-1"),
            SessionDependencies(
                connector = connector,
                outbound = AcceptingSessionOutbound(),
                activeFrameConsumer = consumer,
                commandCleanup = dispatcher,
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

    private class RecordingResultPublisher : CommandResultPublisher {
        val frames = mutableListOf<Pair<com.skycommand.relay.gateway.session.ActiveSession, CommandResultFrame>>()

        override fun publish(
            activeSession: com.skycommand.relay.gateway.session.ActiveSession,
            frame: CommandResultFrame,
        ): PublishResult {
            frames += activeSession to frame
            return PublishResult.Delivered
        }
    }

    private class CapturingConsumer : ActiveFrameConsumer {
        lateinit var activeSession: ActiveSession

        override fun accept(activeSession: ActiveSession, frame: RelayFrame) {
            this.activeSession = activeSession
        }
    }

    private class RecordingConnector : TransportConnector {
        private lateinit var connection: RecordingConnection

        override fun open(
            endpoint: String,
            generation: SessionGeneration,
            listener: TransportListener,
        ): TransportOpenResult {
            connection = RecordingConnection(generation, listener)
            return TransportOpenResult.OpenAccepted(connection)
        }

        fun openCurrent() = connection.open()

        fun receive(bytes: ByteArray) = connection.receive(bytes)
    }

    private class RecordingConnection(
        override val generation: SessionGeneration,
        private val listener: TransportListener,
    ) : TransportConnection {
        override val writer = TransportWriter { TransportWriteResult.WriteAccepted }

        fun open() = listener.onOpened(this)

        fun receive(bytes: ByteArray) = listener.onBytes(generation, bytes)

        override fun close(reason: String): TransportCloseResult = TransportCloseResult.CloseRequested
    }

    private class AcceptingSessionOutbound : SessionOutbound {
        override fun attach(generation: SessionGeneration, writer: TransportWriter): AttachResult = AttachResult.AttachAccepted

        override fun sendHandshake(
            generation: SessionGeneration,
            frame: com.skycommand.relay.protocol.HelloFrame,
        ): HandshakeSendResult = HandshakeSendResult.SendAccepted

        override fun discard(generation: SessionGeneration) = Unit
    }

    private fun encoded(frame: RelayFrame): ByteArray =
        assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value

    private fun captureActiveSession(connector: RecordingConnector) {
        connector.receive(encoded(TelemetryFrame(JsonObject(emptyMap()), JsonObject(emptyMap()))))
    }
}
