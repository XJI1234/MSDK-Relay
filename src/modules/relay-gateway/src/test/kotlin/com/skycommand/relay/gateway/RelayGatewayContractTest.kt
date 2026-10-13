package com.skycommand.relay.gateway

import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.gateway.command.RegistrationResult
import com.skycommand.relay.gateway.mission.MissionAbortReason
import com.skycommand.relay.gateway.mission.MissionMetadata
import com.skycommand.relay.gateway.mission.MissionSink
import com.skycommand.relay.gateway.mission.MissionSinkCompletionResult
import com.skycommand.relay.gateway.mission.MissionSinkResult
import com.skycommand.relay.gateway.mission.MissionReadable
import com.skycommand.relay.gateway.mission.StagedMission
import com.skycommand.relay.gateway.outbound.PublishRejectionKind
import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.ConnectionSelection
import com.skycommand.relay.gateway.session.ConnectionSelectionResult
import com.skycommand.relay.gateway.session.SelectionResult
import com.skycommand.relay.gateway.session.SessionPairingAdmission
import com.skycommand.relay.gateway.session.ScheduledCancellation
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.gateway.session.TransportCloseResult
import com.skycommand.relay.gateway.session.TransportConnection
import com.skycommand.relay.gateway.session.TransportConnector
import com.skycommand.relay.gateway.session.TransportListener
import com.skycommand.relay.gateway.session.TransportOpenResult
import com.skycommand.relay.gateway.session.TransportWriteResult
import com.skycommand.relay.gateway.session.TransportWriter
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.CommandResultFrame
import com.skycommand.relay.protocol.DiagnosticAcknowledgementFrame
import com.skycommand.relay.protocol.DiagnosticEventFrame
import com.skycommand.relay.protocol.DiagnosticReportFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.MissionBeginFrame
import com.skycommand.relay.protocol.MissionChunkFrame
import com.skycommand.relay.protocol.MissionCompleteFrame
import com.skycommand.relay.protocol.MissionResultFrame
import com.skycommand.relay.protocol.MissionPhase
import com.skycommand.relay.protocol.MissionPhaseFrame
import com.skycommand.relay.protocol.PairedFrame
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import com.skycommand.relay.protocol.TelemetryFrame
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayGatewayContractTest {

    @Test
    fun routesActiveCommandsAndPublishesTheirResultsThroughTheCurrentSession() {
        val fixture = GatewayFixture()
        val completions = mutableListOf<CommandCompletion>()
        assertEquals(
            RegistrationResult.Registered,
            fixture.gateway.registerCommandHandler("telemetry.read", CommandHandler { _, completion -> completions += completion }),
        )

        fixture.activate()
        fixture.connector.current.receive(CommandFrame("command-1", "telemetry.read", JsonObject(emptyMap())))
        completions.single().succeed("Telemetry sampled")

        assertEquals(
            CommandResultFrame("command-1", true, "Telemetry sampled"),
            fixture.sentFrames().last(),
        )
    }

    @Test
    fun routesMissionFramesToTheMissionSinkAndPublishesTheTransferResult() {
        val fixture = GatewayFixture()
        val content = "mission-content".encodeToByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(content).toHex()

        fixture.activate()
        fixture.connector.current.receive(MissionBeginFrame("mission-1", "route.kmz", content.size.toLong(), checksum))
        fixture.connector.current.receive(MissionChunkFrame("mission-1", content))
        fixture.connector.current.receive(MissionCompleteFrame("mission-1"))

        assertEquals(content.toList(), fixture.missionSink.bytes.toList())
        assertEquals(MissionResultFrame("mission-1", true, "Mission staged"), fixture.sentFrames().last())
    }

    @Test
    fun publishesOnlyForTheCurrentActiveSessionAndInvalidatesItOnStop() {
        val fixture = GatewayFixture()
        val telemetry = TelemetryFrame(JsonObject(emptyMap()), JsonObject(emptyMap()))

        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.STALE_SESSION),
            fixture.gateway.publishTelemetry(telemetry),
        )

        fixture.activate()
        assertEquals(PublishResult.Delivered, fixture.gateway.publishTelemetry(telemetry))
        assertEquals(telemetry, fixture.sentFrames().last())
        assertEquals(SessionState.ACTIVE, fixture.gateway.connectionState())

        fixture.gateway.stop()
        assertEquals(SessionState.STOPPED, fixture.gateway.connectionState())
        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.STALE_SESSION),
            fixture.gateway.publishTelemetry(telemetry),
        )
    }

    @Test
    fun exposesExplicitResultPublishingCommandUnregistrationAndOrderedStateNotifications() {
        val fixture = GatewayFixture()
        val states = ConcurrentLinkedQueue<SessionState>()
        val reachedActive = CountDownLatch(3)
        fixture.gateway.onStateChanged { event ->
            states += event.snapshot.state
            reachedActive.countDown()
        }
        assertEquals(
            RegistrationResult.Registered,
            fixture.gateway.registerCommandHandler("pairing.status", CommandHandler { _, _ -> }),
        )

        fixture.activate()

        assertTrue(reachedActive.await(5, TimeUnit.SECONDS), "state listener did not receive the handshake transitions")
        assertEquals(
            listOf(SessionState.CONNECTING, SessionState.AWAITING_PAIRING, SessionState.ACTIVE),
            states.toList(),
        )
        assertEquals(
            PublishResult.Delivered,
            fixture.gateway.publishCommandResult(CommandResultFrame("command-2", true, "Completed")),
        )
        assertEquals(
            PublishResult.Delivered,
            fixture.gateway.publishMissionResult(MissionResultFrame("mission-2", false, "Rejected")),
        )
        assertEquals(
            listOf(
                CommandResultFrame("command-2", true, "Completed"),
                MissionResultFrame("mission-2", false, "Rejected"),
            ),
            fixture.sentFrames().takeLast(2),
        )
        assertEquals(
            com.skycommand.relay.gateway.command.UnregistrationResult.Removed,
            fixture.gateway.unregisterCommandHandler("pairing.status"),
        )
        assertEquals(
            com.skycommand.relay.gateway.command.UnregistrationResult.NotRegistered,
            fixture.gateway.unregisterCommandHandler("pairing.status"),
        )
    }

    @Test
    fun publishesDiagnosticReportsOnlyWhenActiveAndRoutesAcknowledgements() {
        val fixture = GatewayFixture()
        val acknowledgements = mutableListOf<DiagnosticAcknowledgementFrame>()
        fixture.gateway.registerDiagnosticAcknowledgementHandler { acknowledgements += it }
        val report = DiagnosticReportFrame(
            "run-1",
            listOf(DiagnosticEventFrame(1, 0, "ERROR", "device-connection", "SDK_FAILURE", null, "safe")),
        )

        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.STALE_SESSION),
            fixture.gateway.publishDiagnosticReport(report),
        )
        fixture.activate()
        assertEquals(PublishResult.Delivered, fixture.gateway.publishDiagnosticReport(report))
        fixture.connector.current.receive(DiagnosticAcknowledgementFrame("run-1", 1))

        assertEquals(report, fixture.sentFrames().last())
        assertEquals(listOf(DiagnosticAcknowledgementFrame("run-1", 1)), acknowledgements)
    }

    @Test
    fun publishesMissionPhaseFactsOnlyForTheCurrentActiveSession() {
        val fixture = GatewayFixture()
        val frame = MissionPhaseFrame(
            missionRevision = 4,
            deviceGeneration = 1,
            sequence = 2,
            phase = MissionPhase.ROUTE_EXECUTION_STARTED,
            fileName = "survey.kmz",
        )

        assertEquals(
            PublishResult.Rejected(PublishRejectionKind.STALE_SESSION),
            fixture.gateway.publishMissionPhase(frame),
        )

        fixture.activate()

        assertEquals(PublishResult.Delivered, fixture.gateway.publishMissionPhase(frame))
        assertEquals(frame, fixture.sentFrames().last())
    }

    @Test
    fun waitsWithoutEndpointAndResumesOnlyAfterSelection() {
        val fixture = GatewayFixture(endpoint = null)
        fixture.gateway.start()
        assertEquals(SessionState.STOPPED, fixture.gateway.connectionState())
        assertEquals(0, fixture.connector.attempts.size)
        val selected = assertIs<ConnectionSelectionResult.Valid>(ConnectionSelection.create("ws://new/relay")).selection
        assertEquals(SelectionResult.Changed, fixture.gateway.selectConnection(selected))
        assertEquals(SessionState.CONNECTING, fixture.gateway.connectionState())
        assertEquals(fixture.connector.current.generation, fixture.gateway.connectionSnapshot().generation)
        assertEquals(SelectionResult.Unchanged, fixture.gateway.selectConnection(selected))
        assertEquals(1, fixture.connector.attempts.size)
        fixture.connector.current.open(); fixture.connector.current.receive(PairedFrame("new", null))
        fixture.gateway.selectConnection(null)
        assertEquals(SessionState.STOPPED, fixture.gateway.connectionState())
        assertEquals(PublishResult.Rejected(PublishRejectionKind.STALE_SESSION), fixture.gateway.publishCommandResult(CommandResultFrame("late", true, "safe")))
        fixture.gateway.stop()
    }

    @Test
    fun switchingConnectionKeepsRegisteredCommandsAndDiscardsOldCompletionAndFrames() {
        val fixture = GatewayFixture()
        val completions = mutableListOf<CommandCompletion>()
        fixture.gateway.registerCommandHandler("telemetry.read", CommandHandler { _, completion -> completions += completion })
        fixture.activate(); val old = fixture.connector.current
        old.receive(CommandFrame("same-id", "telemetry.read", JsonObject(emptyMap())))
        val selected = assertIs<ConnectionSelectionResult.Valid>(ConnectionSelection.create("ws://new/relay")).selection
        fixture.gateway.selectConnection(selected)
        fixture.connector.current.open(); fixture.connector.current.receive(PairedFrame("new", null))
        val before = fixture.sentFrames()
        completions.first().succeed("old completion")
        old.receive(CommandFrame("old-frame", "telemetry.read", JsonObject(emptyMap())))
        assertEquals(before, fixture.sentFrames()); assertEquals(1, completions.size)
        fixture.connector.current.receive(CommandFrame("same-id", "telemetry.read", JsonObject(emptyMap())))
        completions.last().succeed("new completion")
        assertEquals(2, completions.size)
        assertEquals(CommandResultFrame("same-id", true, "new completion"), fixture.sentFrames().last())
        assertEquals(2, fixture.connector.attempts.size)
        fixture.gateway.stop()
    }

    @Test
    fun revokingConnectionAbortsPartialMissionWithoutReplayingItsOldChunks() {
        val fixture = GatewayFixture()
        fixture.activate(); val old = fixture.connector.current
        val content = "mission".encodeToByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(content).toHex()
        old.receive(MissionBeginFrame("old-mission", "route.kmz", content.size.toLong(), checksum))
        fixture.gateway.selectConnection(null)
        assertEquals(1, fixture.missionSink.abortCount)
        val selected = assertIs<ConnectionSelectionResult.Valid>(ConnectionSelection.create("ws://new/relay")).selection
        fixture.gateway.selectConnection(selected)
        fixture.connector.current.open(); fixture.connector.current.receive(PairedFrame("new", null))
        old.receive(MissionChunkFrame("old-mission", content)); old.receive(MissionCompleteFrame("old-mission"))
        assertTrue(fixture.missionSink.bytes.isEmpty())
        assertTrue(fixture.sentFrames().none { it is MissionResultFrame })
        fixture.gateway.stop()
    }

    @Test
    fun forwardsPairingAdmissionAndNeverDispatchesAnUnadmittedCommand() {
        val fixture = GatewayFixture(pairingAdmission = SessionPairingAdmission { _, _ -> false })
        var dispatched = false
        fixture.gateway.registerCommandHandler("telemetry.read", CommandHandler { _, _ -> dispatched = true })
        fixture.activate()
        fixture.connector.current.receive(CommandFrame("rejected", "telemetry.read", JsonObject(emptyMap())))
        assertEquals(false, dispatched)
        assertEquals(SessionState.STOPPED, fixture.gateway.connectionState())
        assertEquals(PublishResult.Rejected(PublishRejectionKind.STALE_SESSION), fixture.gateway.publishCommandResult(CommandResultFrame("rejected", true, "safe")))
    }

    private class GatewayFixture(
        endpoint: String? = "ws://127.0.0.1:8765/relay",
        pairingAdmission: SessionPairingAdmission = SessionPairingAdmission { _, _ -> true },
    ) {
        val connector = RecordingConnector()
        val missionSink = RecordingMissionSink()
        val gateway = RelayGateway.create(
            RelayGatewayConfig(
                endpoint = endpoint,
                deviceId = "android-device",
                missionSink = missionSink,
                pairingAdmission = pairingAdmission,
            ),
            connector,
            MonotonicScheduler { _, _ -> ScheduledCancellation { } },
        )

        fun activate() {
            gateway.start()
            connector.current.open()
            connector.current.receive(PairedFrame("desktop-session", null))
        }

        fun sentFrames(): List<RelayFrame> = connector.current.writes.map { bytes ->
            val decoded = RelayFrameCodec.decode(bytes)
            assertIs<com.skycommand.relay.protocol.DecodeResult.Decoded>(decoded).frame
        }
    }

    private class RecordingConnector : TransportConnector {
        lateinit var current: RecordingConnection
        val attempts = mutableListOf<RecordingConnection>()

        override fun open(
            endpoint: String,
            generation: SessionGeneration,
            listener: TransportListener,
        ): TransportOpenResult {
            current = RecordingConnection(generation, listener)
            attempts += current
            return TransportOpenResult.OpenAccepted(current)
        }
    }

    private class RecordingConnection(
        override val generation: SessionGeneration,
        private val listener: TransportListener,
    ) : TransportConnection {
        val writes = mutableListOf<ByteArray>()
        private var closed = false

        override val writer: TransportWriter = TransportWriter { bytes ->
            if (closed) TransportWriteResult.WriteRejected else {
                writes += bytes.copyOf()
                TransportWriteResult.WriteAccepted
            }
        }

        override fun close(reason: String): TransportCloseResult = if (closed) {
            TransportCloseResult.AlreadyClosed
        } else {
            closed = true
            TransportCloseResult.CloseRequested
        }

        fun open() = listener.onOpened(this)

        fun receive(frame: RelayFrame) {
            val encoded = RelayFrameCodec.encode(frame)
            val bytes = assertIs<com.skycommand.relay.protocol.Accepted<ByteArray>>(encoded).value
            listener.onBytes(generation, bytes)
        }
    }

    private class RecordingMissionSink : MissionSink {
        var metadata: MissionMetadata? = null
        var abortCount = 0
        val bytes = mutableListOf<Byte>()

        override fun begin(metadata: MissionMetadata): MissionSinkResult {
            this.metadata = metadata
            return MissionSinkResult.Accepted
        }

        override fun append(bytes: ByteArray): MissionSinkResult {
            this.bytes += bytes.toList()
            return MissionSinkResult.Accepted
        }

        override fun complete(): MissionSinkCompletionResult {
            val current = metadata ?: return MissionSinkCompletionResult.Rejected
            return MissionSinkCompletionResult.Accepted(
                StagedMission(
                    current.transferId,
                    current.fileName,
                    current.size,
                    current.sha256,
                    MissionReadable { ByteArrayInputStream(bytes.toByteArray()) },
                ),
            )
        }

        override fun abort(reason: MissionAbortReason) { abortCount++ }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
