package com.skycommand.relay.wayline

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.mission.MissionMetadata as GatewayMissionMetadata
import com.skycommand.relay.gateway.mission.MissionSinkCompletionResult
import com.skycommand.relay.gateway.mission.MissionSinkResult
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.phase.MissionExecutionSignalListener
import com.skycommand.relay.wayline.phase.MissionExecutionSignalRegistration
import com.skycommand.relay.wayline.phase.MissionExecutionSignalSource
import com.skycommand.relay.wayline.phase.MissionPhase
import com.skycommand.relay.wayline.phase.MissionPhaseFact
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.UploadState
import com.skycommand.relay.wayline.executor.MissionControlPort
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.staging.StagingStorage
import com.skycommand.relay.wayline.uploader.MissionUploadPort
import com.skycommand.relay.wayline.uploader.StagedMissionContentReader
import com.skycommand.relay.wayline.uploader.UploadCompletion
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WaylineMissionContractTest {
    @Test
    fun djiEnterWaylineKeepsStartingUntilExecutingPublishesRouteExecutionStarted() {
        val fixture = Fixture()
        val facts = mutableListOf<MissionPhaseFact>()
        fixture.mission.onPhaseChanged { facts += it }
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()
        assertEquals(ExecutionState.STARTING, fixture.mission.snapshot().execution)

        fixture.signals.emit(MissionExecutionSignal.ENTER_WAYLINE)

        assertEquals(ExecutionState.STARTING, fixture.mission.snapshot().execution)
        assertEquals(
            listOf(MissionPhaseFact(1, 0, 1, MissionPhase.START_POINT_REACHED, "survey.kmz")),
            facts,
        )

        fixture.signals.emit(MissionExecutionSignal.EXECUTING)

        assertEquals(ExecutionState.EXECUTING, fixture.mission.snapshot().execution)
        assertEquals(
            listOf(
                MissionPhaseFact(1, 0, 1, MissionPhase.START_POINT_REACHED, "survey.kmz"),
                MissionPhaseFact(1, 0, 2, MissionPhase.ROUTE_EXECUTION_STARTED, "survey.kmz"),
            ),
            facts,
        )
    }

    @Test
    fun directExecutingDoesNotInventStartPointReachedAndDeviceLossInvalidatesThePhaseTracker() {
        val fixture = Fixture()
        val facts = mutableListOf<MissionPhaseFact>()
        fixture.mission.onPhaseChanged { facts += it }
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()

        fixture.signals.emit(MissionExecutionSignal.EXECUTING)
        fixture.mission.markDeviceUnavailable()
        fixture.signals.emit(MissionExecutionSignal.ENTER_WAYLINE)

        assertEquals(
            listOf(MissionPhaseFact(1, 0, 1, MissionPhase.ROUTE_EXECUTION_STARTED, "survey.kmz")),
            facts,
        )
        assertEquals(ExecutionState.FAILED, fixture.mission.snapshot().execution)
    }

    @Test
    fun djiTerminalSignalsUpdateOnlyTheCurrentArmedMissionWithoutInventingPhaseFacts() {
        val fixture = Fixture()
        val facts = mutableListOf<MissionPhaseFact>()
        fixture.mission.onPhaseChanged { facts += it }
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()
        fixture.signals.emit(MissionExecutionSignal.ENTER_WAYLINE)

        fixture.signals.emit(MissionExecutionSignal.COMPLETED)

        assertEquals(ExecutionState.FINISHED, fixture.mission.snapshot().execution)
        assertEquals(
            listOf(MissionPhaseFact(1, 0, 1, MissionPhase.START_POINT_REACHED, "survey.kmz")),
            facts,
        )

        fixture.signals.emit(MissionExecutionSignal.INTERRUPTED)
        assertEquals(ExecutionState.FINISHED, fixture.mission.snapshot().execution)
    }

    @Test
    fun rejectsRemovedGenerationCommand() {
        val fixture = Fixture()
        val completion = Completion()

        fixture.mission.commandHandler().handle(
            CommandFrame("generate", "wayline.generate", JsonObject(emptyMap())),
            completion,
        )

        assertEquals(null, fixture.mission.snapshot().file)
        assertEquals(listOf("reject:Wayline command is not available"), completion.events)
    }

    @Test
    fun reportsUploadSuccessOnlyAfterTheAircraftConfirmsIt() {
        val fixture = Fixture()
        stageTransferred(fixture)
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.upload"), completion)
        assertEquals(emptyList(), completion.events)

        fixture.upload.completeSuccess()
        assertEquals(listOf("ok:Mission uploaded"), completion.events)
    }

    @Test
    fun completesAnAcceptedUploadOnlyOnceWhenTheAdapterRepeatsItsCallback() {
        val fixture = Fixture()
        stageTransferred(fixture)
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.upload"), completion)
        fixture.upload.completeSuccess()
        fixture.upload.completeSuccess()

        assertEquals(listOf("ok:Mission uploaded"), completion.events)
    }

    @Test
    fun deviceUnavailabilityCancelsAnUploadAndDropsItsLateSuccess() {
        val fixture = Fixture()
        stageTransferred(fixture)
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.upload"), completion)
        fixture.mission.markDeviceUnavailable()
        fixture.mission.markDeviceUnavailable()

        assertEquals("survey.kmz", fixture.mission.snapshot().file?.fileName)
        assertEquals(UploadState.FAILED, fixture.mission.snapshot().upload)
        assertEquals(ExecutionState.FAILED, fixture.mission.snapshot().execution)
        assertEquals(listOf("reject:Mission operation failed"), completion.events)
        fixture.upload.completeSuccess()
        assertEquals(UploadState.FAILED, fixture.mission.snapshot().upload)
        assertEquals(listOf("reject:Mission operation failed"), completion.events)
    }

    @Test
    fun reportsControlFailureAfterTheAircraftRejectsIt() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), completion)
        assertEquals(emptyList(), completion.events)
        fixture.control.completeFailure()

        assertEquals(listOf("reject:Mission operation failed"), completion.events)
    }

    @Test
    fun deviceUnavailabilityCancelsAControlOperationAndDropsItsLateSuccess() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), completion)
        fixture.mission.markDeviceUnavailable()

        assertEquals(UploadState.FAILED, fixture.mission.snapshot().upload)
        assertEquals(ExecutionState.FAILED, fixture.mission.snapshot().execution)
        assertEquals(listOf("reject:Mission operation failed"), completion.events)
        fixture.control.completeSuccess()
        assertEquals(ExecutionState.FAILED, fixture.mission.snapshot().execution)
        assertEquals(listOf("reject:Mission operation failed"), completion.events)
    }

    @Test
    fun stagesGatewayTransferredMissionAndRecordsItBeforeCompletion() {
        val fixture = Fixture()
        val bytes = byteArrayOf(1, 2, 3)
        val sink = fixture.mission.missionSink()

        assertEquals(
            MissionSinkResult.Accepted,
            sink.begin(GatewayMissionMetadata("transfer-1", "incoming.kmz", 3, hash(bytes))),
        )
        assertEquals(MissionSinkResult.Accepted, sink.append(bytes))
        val completed = assertIs<MissionSinkCompletionResult.Accepted>(sink.complete())

        assertEquals("incoming.kmz", fixture.mission.snapshot().file?.fileName)
        assertEquals(bytes.toList(), completed.mission.readableByMissionModule.openStream().readBytes().toList())
    }

    @Test
    fun rejectsUnconfirmedControlWithoutStartingAnOperation() {
        val fixture = Fixture()
        val completion = Completion()

        fixture.mission.commandHandler().handle(CommandFrame("start", "wayline.start", JsonObject(emptyMap())), completion)

        assertEquals(listOf("reject:Confirmation is required"), completion.events)
        assertEquals(false, fixture.control.hasStarted)
    }

    @Test
    fun abortingAnIncompleteTransferPreservesThePreviouslyStagedMission() {
        val fixture = Fixture()
        stageTransferred(fixture)
        val sink = fixture.mission.missionSink()
        val bytes = byteArrayOf(1, 2, 3)

        assertEquals(MissionSinkResult.Accepted, sink.begin(GatewayMissionMetadata("transfer-2", "incoming.kmz", 3, hash(bytes))))
        sink.abort(com.skycommand.relay.gateway.mission.MissionAbortReason.TRANSFER_FAILED)

        assertEquals("survey.kmz", fixture.mission.snapshot().file?.fileName)
    }

    @Test
    fun transferredMissionContentCannotReadAReplacementMission() {
        val fixture = Fixture()
        val bytes = byteArrayOf(1, 2, 3)
        val sink = fixture.mission.missionSink()
        sink.begin(GatewayMissionMetadata("transfer-3", "incoming.kmz", 3, hash(bytes)))
        sink.append(bytes)
        val completed = assertIs<MissionSinkCompletionResult.Accepted>(sink.complete())

        stageTransferred(fixture)

        assertFailsWith<IllegalStateException> {
            completed.mission.readableByMissionModule.openStream().readBytes()
        }
    }

    private fun stageTransferred(fixture: Fixture) {
        val bytes = byteArrayOf(1, 2, 3)
        val sink = fixture.mission.missionSink()
        assertEquals(MissionSinkResult.Accepted, sink.begin(GatewayMissionMetadata("transfer", "survey.kmz", bytes.size.toLong(), hash(bytes))))
        assertEquals(MissionSinkResult.Accepted, sink.append(bytes))
        assertIs<MissionSinkCompletionResult.Accepted>(sink.complete())
    }

    private fun confirm(name: String) = CommandFrame("command", name, JsonObject(mapOf("confirm" to JsonBoolean(true))))


    private class Fixture {
        val storage = Storage()
        val upload = UploadPort()
        val control = ControlPort()
        val signals = SignalSource()
        val mission = WaylineMission.create(
            WaylineMissionDependencies(
                stagingStorage = storage,
                contentReader = object : StagedMissionContentReader {
                    override fun read(metadata: MissionMetadata): ByteArray = storage.currentBytes.copyOf()
                },
                uploadPort = upload,
                controlPort = control,
                executionSignalSource = signals,
                operationCoordinator = DjiOperationCoordinator.create(
                    executor = OperationExecutor { it() },
                    scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
                ),
            ),
        )
    }

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()
        override fun succeed(detail: String) { events += "ok:$detail" }
        override fun reject(detail: String) { events += "reject:$detail" }
    }

    private class Storage : StagingStorage {
        var currentBytes = ByteArray(0)
        private var temporary = ByteArray(0)
        override fun beginTemporary(metadata: MissionMetadata) { temporary = ByteArray(0) }
        override fun append(bytes: ByteArray) { temporary += bytes }
        override fun flush() = Unit
        override fun replaceCurrent() { currentBytes = temporary.copyOf() }
        override fun deleteTemporary() { temporary = ByteArray(0) }
    }

    private class UploadPort : MissionUploadPort {
        private var completion: UploadCompletion? = null
        override fun upload(metadata: MissionMetadata, bytes: ByteArray, progress: (Int) -> Unit, completion: UploadCompletion) {
            this.completion = completion
        }
        fun completeSuccess() { requireNotNull(completion).succeed() }
    }

    private class ControlPort : MissionControlPort {
        private var completion: ControlCompletion? = null
        val hasStarted: Boolean get() = completion != null
        override fun start(completion: ControlCompletion) { this.completion = completion }
        override fun pause(completion: ControlCompletion) { this.completion = completion }
        override fun resume(completion: ControlCompletion) { this.completion = completion }
        override fun stop(completion: ControlCompletion) { this.completion = completion }
        fun completeSuccess() { requireNotNull(completion).succeed() }
        fun completeFailure() { requireNotNull(completion).fail() }
    }

    private class SignalSource : MissionExecutionSignalSource {
        private var listener: MissionExecutionSignalListener? = null
        override fun onSignal(listener: MissionExecutionSignalListener): MissionExecutionSignalRegistration {
            this.listener = listener
            return MissionExecutionSignalRegistration { this.listener = null }
        }
        fun emit(signal: MissionExecutionSignal) { listener?.onSignal(signal) }
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
