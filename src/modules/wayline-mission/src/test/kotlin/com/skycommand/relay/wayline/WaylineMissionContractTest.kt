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
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.phase.MissionExecutionSignalListener
import com.skycommand.relay.wayline.phase.MissionExecutionSignalRegistration
import com.skycommand.relay.wayline.phase.MissionExecutionSignalSource
import com.skycommand.relay.wayline.phase.MissionExecutionObservation
import com.skycommand.relay.wayline.phase.MissionExecutionObservationListener
import com.skycommand.relay.wayline.phase.MissionExecutionRawState
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
    fun recordsTheIdentityMatchedRawDjiObservationForTheCurrentMission() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()

        fixture.signals.emit(MissionExecutionObservation(MissionExecutionSignal.EXECUTING, MissionExecutionRawState.RETURN_TO_START_POINT))

        assertEquals(MissionExecutionRawState.RETURN_TO_START_POINT, fixture.mission.snapshot().missionDjiExecutionState)
    }

    @Test
    fun rejectsReplacementTransferWhileTheCurrentMissionIsExecuting() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()
        fixture.signals.emit(MissionExecutionSignal.EXECUTING)

        val replacement = fixture.mission.missionSink()

        assertEquals(
            MissionSinkResult.Rejected,
            replacement.begin(GatewayMissionMetadata("replacement", "replacement.kmz", 3, hash(byteArrayOf(4, 5, 6)))),
        )
        assertEquals("survey.kmz", fixture.mission.snapshot().file?.fileName)
        assertEquals(ExecutionState.EXECUTING, fixture.mission.snapshot().execution)
    }

    @Test
    fun rejectsStartWhileAReplacementTransferIsInProgress() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        val replacement = fixture.mission.missionSink()

        assertEquals(
            MissionSinkResult.Accepted,
            replacement.begin(GatewayMissionMetadata("replacement", "replacement.kmz", 3, hash(byteArrayOf(4, 5, 6)))),
        )
        val completion = Completion()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), completion)

        assertEquals(listOf("reject:Mission operation was rejected"), completion.events)
        assertEquals(false, fixture.control.hasStarted)
        replacement.abort(com.skycommand.relay.gateway.mission.MissionAbortReason.TRANSFER_FAILED)
    }

    @Test
    fun confirmsAnUncertainPauseOnlyAfterTheMatchingDjiStateArrives() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()
        fixture.signals.emit(MissionExecutionSignal.EXECUTING)

        fixture.mission.commandHandler().handle(confirm("wayline.pause"), Completion())
        fixture.scheduler.fire()
        fixture.signals.emit(MissionExecutionSignal.PAUSED)

        val resume = Completion()
        fixture.mission.commandHandler().handle(confirm("wayline.resume"), resume)

        assertEquals(emptyList(), resume.events)
        assertEquals("resume", fixture.control.command)
    }

    @Test
    fun ignoresTerminalSignalsUntilTheCurrentMissionReportsExecuting() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()

        fixture.signals.emit(MissionExecutionSignal.COMPLETED)

        assertEquals(ExecutionState.STARTING, fixture.mission.snapshot().execution)
        fixture.signals.emit(MissionExecutionSignal.EXECUTING)
        fixture.signals.emit(MissionExecutionSignal.COMPLETED)
        assertEquals(ExecutionState.FINISHED, fixture.mission.snapshot().execution)
    }

    @Test
    fun abortsAnIncomingReplacementWhenTheDeviceBecomesUnavailable() {
        val fixture = Fixture()
        stageTransferred(fixture)
        val replacement = fixture.mission.missionSink()
        val bytes = byteArrayOf(4, 5, 6)

        assertEquals(
            MissionSinkResult.Accepted,
            replacement.begin(GatewayMissionMetadata("replacement", "replacement.kmz", 3, hash(bytes))),
        )
        assertEquals(MissionSinkResult.Accepted, replacement.append(bytes))
        fixture.mission.markDeviceUnavailable()

        assertEquals(MissionSinkCompletionResult.Rejected, replacement.complete())
        assertEquals("survey.kmz", fixture.mission.snapshot().file?.fileName)
        assertEquals(ExecutionState.FAILED, fixture.mission.snapshot().execution)
    }

    @Test
    fun allowsReplacementAfterFailureWithoutRequiringLocalDeviceSafetyTelemetry() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.markDeviceUnavailable()
        val replacement = fixture.mission.missionSink()
        val metadata = GatewayMissionMetadata("replacement", "replacement.kmz", 3, hash(byteArrayOf(4, 5, 6)))

        assertEquals(MissionSinkResult.Accepted, replacement.begin(metadata))
        replacement.abort(com.skycommand.relay.gateway.mission.MissionAbortReason.TRANSFER_FAILED)
    }

    @Test
    fun holdsEarlyExecutionStateUntilDjiAcknowledgesStartThenCommitsIt() {
        val fixture = Fixture()
        val facts = mutableListOf<MissionPhaseFact>()
        fixture.mission.onPhaseChanged { facts += it }
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.signals.emit(MissionExecutionSignal.EXECUTING)

        assertEquals(ExecutionState.STARTING, fixture.mission.snapshot().execution)
        assertEquals(emptyList(), facts)

        fixture.control.completeSuccess()

        assertEquals(ExecutionState.EXECUTING, fixture.mission.snapshot().execution)
        assertEquals(
            listOf(MissionPhaseFact(1, 0, 1, MissionPhase.ROUTE_EXECUTION_STARTED, "survey.kmz")),
            facts,
        )
    }

    @Test
    fun ignoresTerminalStateObservedBeforeDjiAcknowledgesStart() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.signals.emit(MissionExecutionSignal.COMPLETED)
        fixture.control.completeSuccess()

        assertEquals(ExecutionState.STARTING, fixture.mission.snapshot().execution)
    }

    @Test
    fun leavesStartUnconfirmedAfterTimeoutAndIgnoresSubsequentExecutionState() {
        val fixture = Fixture()
        val facts = mutableListOf<MissionPhaseFact>()
        fixture.mission.onPhaseChanged { facts += it }
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.scheduler.fire()
        fixture.signals.emit(MissionExecutionSignal.EXECUTING)

        assertEquals(ExecutionState.STARTING, fixture.mission.snapshot().execution)
        assertEquals(emptyList(), facts)
    }

    @Test
    fun submitsStartToDjiWhenTheCurrentUploadedMissionIsInALegalState() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), completion)

        assertEquals(ExecutionState.STARTING, fixture.mission.snapshot().execution)
        assertEquals(true, fixture.control.hasStarted)
        assertEquals(emptyList(), completion.events)
    }

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
        fixture.signals.emit(MissionExecutionSignal.EXECUTING)

        fixture.signals.emit(MissionExecutionSignal.COMPLETED)

        assertEquals(ExecutionState.FINISHED, fixture.mission.snapshot().execution)
        assertEquals(
            listOf(
                MissionPhaseFact(1, 0, 1, MissionPhase.START_POINT_REACHED, "survey.kmz"),
                MissionPhaseFact(1, 0, 2, MissionPhase.ROUTE_EXECUTION_STARTED, "survey.kmz"),
            ),
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
        assertEquals(listOf("reject:Mission operation result was not confirmed"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("wayline"),
                    "outcome" to JsonString("RESULT_UNCONFIRMED"),
                ),
            ),
            completion.result,
        )
        fixture.upload.completeSuccess()
        assertEquals(UploadState.FAILED, fixture.mission.snapshot().upload)
        assertEquals(listOf("reject:Mission operation result was not confirmed"), completion.events)
    }

    @Test
    fun reportsInvocationFailureWhenTheAdapterProvidesNoDjiError() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), completion)
        assertEquals(emptyList(), completion.events)
        fixture.control.completeFailure()

        assertEquals(listOf("reject:Mission operation failed before DJI reported a result"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("wayline"),
                    "outcome" to JsonString("INVOCATION_FAILED"),
                ),
            ),
            completion.result,
        )
    }

    @Test
    fun restoresNotStartedWhenDjiExplicitlyRejectsMissionStart() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.start"), completion)
        fixture.control.completeFailure(
            com.skycommand.relay.wayline.executor.MissionControlFailure.fromDjiError(
                "WAYPOINT_MISSION_BUSY",
                "The mission manager is busy",
            ),
        )

        assertEquals(ExecutionState.NOT_STARTED, fixture.mission.snapshot().execution)
        assertEquals(listOf("reject:Mission action was rejected"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("wayline"),
                    "outcome" to JsonString("ACTION_REJECTED"),
                    "errorCode" to JsonString("WAYPOINT_MISSION_BUSY"),
                    "errorDescription" to JsonString("The mission manager is busy"),
                ),
            ),
            completion.result,
        )
    }

    @Test
    fun reportsTheNormalizedDjiRejectionAsAStructuredWaylineFailure() {
        val fixture = Fixture()
        stageTransferred(fixture)
        fixture.mission.commandHandler().handle(confirm("wayline.upload"), Completion())
        fixture.upload.completeSuccess()
        fixture.mission.commandHandler().handle(confirm("wayline.start"), Completion())
        fixture.control.completeSuccess()
        fixture.signals.emit(MissionExecutionSignal.EXECUTING)
        val completion = Completion()

        fixture.mission.commandHandler().handle(confirm("wayline.pause"), completion)
        fixture.control.completeFailure(
            com.skycommand.relay.wayline.executor.MissionControlFailure.fromDjiError(
                "WAYPOINT_MISSION_BUSY",
                "The mission manager is busy",
            ),
        )

        assertEquals(listOf("reject:Mission action was rejected"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("wayline"),
                    "outcome" to JsonString("ACTION_REJECTED"),
                    "errorCode" to JsonString("WAYPOINT_MISSION_BUSY"),
                    "errorDescription" to JsonString("The mission manager is busy"),
                ),
            ),
            completion.result,
        )
        assertEquals(ExecutionState.EXECUTING, fixture.mission.snapshot().execution)
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
        assertEquals(listOf("reject:Mission operation result was not confirmed"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("wayline"),
                    "outcome" to JsonString("RESULT_UNCONFIRMED"),
                ),
            ),
            completion.result,
        )
        fixture.control.completeSuccess()
        assertEquals(ExecutionState.FAILED, fixture.mission.snapshot().execution)
        assertEquals(listOf("reject:Mission operation result was not confirmed"), completion.events)
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
        val scheduler = Scheduler()
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
                    scheduler = scheduler,
                ),
            ),
        )
    }

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()
        var result: JsonObject? = null
        override fun succeed(detail: String) { events += "ok:$detail" }
        override fun reject(detail: String) { events += "reject:$detail" }
        override fun reject(detail: String, result: JsonObject?) {
            events += "reject:$detail"
            this.result = result
        }
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
        var command: String? = null
        val hasStarted: Boolean get() = completion != null
        override fun start(completion: ControlCompletion) { command = "start"; this.completion = completion }
        override fun pause(completion: ControlCompletion) { command = "pause"; this.completion = completion }
        override fun resume(completion: ControlCompletion) { command = "resume"; this.completion = completion }
        override fun stop(completion: ControlCompletion) { command = "stop"; this.completion = completion }
        fun completeSuccess() { requireNotNull(completion).succeed() }
        fun completeFailure(failure: com.skycommand.relay.wayline.executor.MissionControlFailure? = null) {
            requireNotNull(completion).fail(failure)
        }
    }

    private class SignalSource : MissionExecutionSignalSource {
        private var listener: MissionExecutionSignalListener? = null
        private var observationListener: MissionExecutionObservationListener? = null
        override fun onSignal(listener: MissionExecutionSignalListener): MissionExecutionSignalRegistration {
            this.listener = listener
            return MissionExecutionSignalRegistration { this.listener = null }
        }
        override fun onObservation(listener: MissionExecutionObservationListener): MissionExecutionSignalRegistration {
            observationListener = listener
            return MissionExecutionSignalRegistration { observationListener = null }
        }
        override fun beginStartAttempt() = Unit
        override fun confirmStartAttempt() = Unit
        override fun invalidateStartAttempt() = Unit
        fun emit(signal: MissionExecutionSignal) = emit(MissionExecutionObservation(signal, MissionExecutionRawState.UNKNOWN))
        fun emit(observation: MissionExecutionObservation) {
            listener?.onSignal(observation.signal)
            observationListener?.onObservation(observation)
        }
    }

    private class Scheduler : OperationScheduler {
        private var callback: (() -> Unit)? = null

        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            this.callback = callback
            return OperationCancellation { }
        }

        fun fire() { callback?.invoke() }
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
