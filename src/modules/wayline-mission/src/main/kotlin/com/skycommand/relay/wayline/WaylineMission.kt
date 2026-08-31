package com.skycommand.relay.wayline

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.gateway.mission.MissionAbortReason
import com.skycommand.relay.gateway.mission.MissionMetadata as GatewayMissionMetadata
import com.skycommand.relay.gateway.mission.MissionReadable
import com.skycommand.relay.gateway.mission.MissionSink
import com.skycommand.relay.gateway.mission.MissionSinkCompletionResult
import com.skycommand.relay.gateway.mission.MissionSinkResult
import com.skycommand.relay.gateway.mission.StagedMission
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.wayline.command.WaylineActionCompletion
import com.skycommand.relay.wayline.command.WaylineActionResult
import com.skycommand.relay.wayline.command.WaylineActionTerminalOutcome
import com.skycommand.relay.wayline.command.WaylineCommandActions
import com.skycommand.relay.wayline.command.WaylineCommandHandler
import com.skycommand.relay.wayline.command.WaylineCommandRejection
import com.skycommand.relay.wayline.command.WaylineCommandResult
import com.skycommand.relay.wayline.executor.ExecutionRequestResult
import com.skycommand.relay.wayline.executor.ExecutionTerminalListener
import com.skycommand.relay.wayline.executor.ExecutionTerminalOutcome
import com.skycommand.relay.wayline.executor.MissionControlPort
import com.skycommand.relay.wayline.executor.MissionExecutor
import com.skycommand.relay.wayline.executor.MissionStartSafetyGate
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.phase.MissionExecutionSignalRegistration
import com.skycommand.relay.wayline.phase.MissionExecutionSignalSource
import com.skycommand.relay.wayline.phase.MissionFlightPhase
import com.skycommand.relay.wayline.phase.MissionPhase
import com.skycommand.relay.wayline.phase.MissionPhaseFact
import com.skycommand.relay.wayline.phase.MissionPhaseSink
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.staging.MissionStaging
import com.skycommand.relay.wayline.staging.StagingCompleteResult
import com.skycommand.relay.wayline.staging.StagingRequestResult
import com.skycommand.relay.wayline.staging.StagingStorage
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.MissionStateDiagnosticSink
import com.skycommand.relay.wayline.state.MissionStateEvent
import com.skycommand.relay.wayline.state.MissionStateListener
import com.skycommand.relay.wayline.state.MissionStateStore
import com.skycommand.relay.wayline.state.Registration
import com.skycommand.relay.wayline.uploader.MissionUploadPort
import com.skycommand.relay.wayline.uploader.MissionUploader
import com.skycommand.relay.wayline.uploader.StagedMissionContentReader
import com.skycommand.relay.wayline.uploader.UploadStartResult
import com.skycommand.relay.wayline.uploader.UploadTerminalListener
import com.skycommand.relay.wayline.uploader.UploadTerminalOutcome
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class WaylineMissionDependencies(
    val stagingStorage: StagingStorage,
    val contentReader: StagedMissionContentReader,
    val uploadPort: MissionUploadPort,
    val controlPort: MissionControlPort,
    val startSafetyGate: MissionStartSafetyGate,
    val executionSignalSource: MissionExecutionSignalSource,
    val operationCoordinator: DjiOperationCoordinator,
    val uploadTimeoutMillis: Long = 30_000,
    val controlTimeoutMillis: Long = 30_000,
    val diagnosticSink: MissionStateDiagnosticSink = MissionStateDiagnosticSink { },
)

class WaylineMission private constructor(dependencies: WaylineMissionDependencies) {
    private val stagingLock = ReentrantLock()
    private val lifecycleLock = ReentrantLock()
    private val activeOperations = mutableSetOf<TrackedOperation>()
    private val stagingRevision = AtomicLong(0)
    private val executionStateRevision = AtomicLong(0)
    private val phaseListeners = mutableSetOf<MissionPhaseListener>()
    private var incomingTransferActive = false
    private val groundedReadinessGate = dependencies.startSafetyGate
    private val executionSignalSource = dependencies.executionSignalSource
    private val staging = MissionStaging.create(dependencies.stagingStorage)
    private val state = MissionStateStore.create(dependencies.diagnosticSink)
    private val uploader = MissionUploader.create(
        stateStore = state,
        contentReader = dependencies.contentReader,
        uploadPort = dependencies.uploadPort,
        operationCoordinator = dependencies.operationCoordinator,
        timeoutMillis = dependencies.uploadTimeoutMillis,
    )
    private val executor = MissionExecutor.create(
        stateStore = state,
        controlPort = dependencies.controlPort,
        coordinator = dependencies.operationCoordinator,
        timeoutMillis = dependencies.controlTimeoutMillis,
        executionSourceRevision = executionStateRevision,
        startSafetyGate = dependencies.startSafetyGate,
    )
    private val flightPhase = MissionFlightPhase.create(MissionPhaseSink(::acceptPhaseFact))
    @Suppress("unused")
    private val executionSignalRegistration: MissionExecutionSignalRegistration =
        dependencies.executionSignalSource.onSignal(::acceptExecutionSignal)
    private val commands = WaylineCommandHandler.create(Actions())
    private val contentReader = dependencies.contentReader

    fun commandHandler(): CommandHandler = CommandHandler(::handleCommand)

    fun missionSink(): MissionSink = GatewayMissionSink()

    fun snapshot(): MissionSnapshot = state.snapshot()

    fun onChanged(listener: MissionStateListener): Registration = state.onChanged(listener)

    fun onPhaseChanged(listener: MissionPhaseListener): Registration {
        lifecycleLock.withLock { phaseListeners += listener }
        return Registration { lifecycleLock.withLock { phaseListeners.remove(listener) } }
    }

    fun markDeviceUnavailable(): MissionSnapshot = lifecycleLock.withLock {
        stagingLock.withLock { staging.cancel() }
        incomingTransferActive = false
        val current = state.snapshot()
        executionSignalSource.invalidateStartAttempt()
        flightPhase.invalidate(current.missionRevision, current.deviceGeneration)
        val snapshot = state.markDeviceUnavailable().snapshot
        activeOperations.toList().also { activeOperations.clear() }.forEach { it.cancellation.cancel() }
        snapshot
    }

    private fun handleCommand(command: CommandFrame, completion: CommandCompletion) {
        val terminal = RelayCompletion(completion, command.name)
        when (val result = commands.handle(command, terminal)) {
            is WaylineCommandResult.Accepted -> Unit
            is WaylineCommandResult.Rejected -> terminal.reject(detailFor(result.reason))
            is WaylineCommandResult.Succeeded -> terminal.succeed(result.detail)
        }
    }

    private fun recordStaged(metadata: MissionMetadata) {
        val current = state.snapshot()
        executionSignalSource.invalidateStartAttempt()
        flightPhase.invalidate(current.missionRevision, current.deviceGeneration)
        state.apply(MissionStateEvent.FileStaged(stagingRevision.incrementAndGet(), metadata))
    }

    private fun detailFor(reason: WaylineCommandRejection): String = when (reason) {
        WaylineCommandRejection.UNKNOWN_COMMAND -> "Wayline command is not available"
        WaylineCommandRejection.INVALID_FIELDS -> "Wayline command fields are invalid"
        WaylineCommandRejection.CONFIRMATION_REQUIRED -> "Confirmation is required"
        WaylineCommandRejection.CAPABILITY_REJECTED -> "Mission operation was rejected"
    }

    private inner class Actions : WaylineCommandActions {
        override fun upload(completion: WaylineActionCompletion): WaylineActionResult = lifecycleLock.withLock {
            if (incomingTransferActive) return WaylineActionResult.Rejected
            val tracked = TrackedOperation()
            track(uploader.start(UploadTerminalListener {
                completeTrackedOperation(tracked)
                completion.complete(it.toWaylineOutcome())
            }), tracked)
        }

        override fun start(completion: WaylineActionCompletion): WaylineActionResult = requestStart(completion)

        override fun pause(completion: WaylineActionCompletion): WaylineActionResult = requestControl(completion) { listener -> executor.pause(listener) }

        override fun resume(completion: WaylineActionCompletion): WaylineActionResult = requestControl(completion) { listener -> executor.resume(listener) }

        override fun stop(completion: WaylineActionCompletion): WaylineActionResult = requestStop(completion)
    }

    private fun requestStart(completion: WaylineActionCompletion): WaylineActionResult = lifecycleLock.withLock {
        if (incomingTransferActive) return WaylineActionResult.Rejected
        val snapshot = state.snapshot()
        val missionRevision = snapshot.missionRevision
        val shouldArm = missionRevision != null &&
            snapshot.upload is com.skycommand.relay.wayline.state.UploadState.UPLOADED &&
            snapshot.execution in setOf(
                com.skycommand.relay.wayline.state.ExecutionState.NOT_STARTED,
                com.skycommand.relay.wayline.state.ExecutionState.FAILED,
        )
        if (shouldArm) {
            executionSignalSource.beginStartAttempt()
            flightPhase.prepareStart(missionRevision, snapshot.deviceGeneration, requireNotNull(snapshot.file).fileName)
        }
        val tracked = TrackedOperation()
        val result = executor.start(ExecutionTerminalListener { outcome ->
            if (outcome == ExecutionTerminalOutcome.SUCCEEDED && missionRevision != null) {
                executionSignalSource.confirmStartAttempt()
                flightPhase.confirmStart(missionRevision, snapshot.deviceGeneration).forEach { signal ->
                    applyAcceptedExecutionSignal(signal, missionRevision, snapshot.deviceGeneration)
                }
            } else if (missionRevision != null) {
                executionSignalSource.invalidateStartAttempt()
                flightPhase.invalidate(missionRevision, snapshot.deviceGeneration)
            }
            completeTrackedOperation(tracked)
            completion.complete(outcome.toWaylineOutcome())
        })
        if (result is ExecutionRequestResult.Rejected && missionRevision != null) {
            executionSignalSource.invalidateStartAttempt()
            flightPhase.invalidate(missionRevision, snapshot.deviceGeneration)
        }
        track(result, tracked)
    }

    private fun requestStop(completion: WaylineActionCompletion): WaylineActionResult = lifecycleLock.withLock {
        if (incomingTransferActive) return WaylineActionResult.Rejected
        val snapshot = state.snapshot()
        val tracked = TrackedOperation()
        val result = executor.stop(ExecutionTerminalListener {
            completeTrackedOperation(tracked)
            completion.complete(it.toWaylineOutcome())
        })
        if (result is ExecutionRequestResult.Accepted) {
            executionSignalSource.invalidateStartAttempt()
            flightPhase.invalidate(snapshot.missionRevision, snapshot.deviceGeneration)
        }
        track(result, tracked)
    }

    private fun requestControl(
        completion: WaylineActionCompletion,
        request: (ExecutionTerminalListener) -> ExecutionRequestResult,
    ): WaylineActionResult = lifecycleLock.withLock {
        if (incomingTransferActive) return WaylineActionResult.Rejected
        val tracked = TrackedOperation()
        track(request(ExecutionTerminalListener {
            completeTrackedOperation(tracked)
            completion.complete(it.toWaylineOutcome())
        }), tracked)
    }

    private fun track(result: UploadStartResult, tracked: TrackedOperation): WaylineActionResult = when (result) {
        is UploadStartResult.Accepted -> {
            tracked.install(result.cancellation)
            if (!tracked.completed.get()) activeOperations += tracked
            WaylineActionResult.Accepted
        }

        is UploadStartResult.Rejected -> WaylineActionResult.Rejected
    }

    private fun track(result: ExecutionRequestResult, tracked: TrackedOperation): WaylineActionResult = when (result) {
        is ExecutionRequestResult.Accepted -> {
            tracked.install(result.cancellation)
            if (!tracked.completed.get()) activeOperations += tracked
            WaylineActionResult.Accepted
        }

        is ExecutionRequestResult.Rejected -> WaylineActionResult.Rejected
    }

    private fun completeTrackedOperation(tracked: TrackedOperation) {
        tracked.completed.set(true)
        lifecycleLock.withLock { activeOperations.remove(tracked) }
    }

    private fun acceptExecutionSignal(signal: MissionExecutionSignal) {
        val snapshot = state.snapshot()
        val missionRevision = snapshot.missionRevision ?: return
        val accepted = flightPhase.accept(signal, missionRevision, snapshot.deviceGeneration)
        if (accepted !is com.skycommand.relay.wayline.phase.MissionSignalAcceptance.Accepted) return

        applyAcceptedExecutionSignal(signal, missionRevision, snapshot.deviceGeneration)
        when (signal) {
            MissionExecutionSignal.PAUSED ->
                executor.observeExecutionState(ExecutionState.PAUSED, missionRevision, snapshot.deviceGeneration)
            MissionExecutionSignal.EXECUTING ->
                executor.observeExecutionState(ExecutionState.EXECUTING, missionRevision, snapshot.deviceGeneration)
            else -> Unit
        }
    }

    private fun applyAcceptedExecutionSignal(
        signal: MissionExecutionSignal,
        missionRevision: Long,
        deviceGeneration: Long,
    ) {
        val current = state.snapshot()
        if (
            current.missionRevision != missionRevision ||
            current.deviceGeneration != deviceGeneration
        ) {
            return
        }

        val target = when (signal) {
            MissionExecutionSignal.PAUSED ->
                if (current.execution == ExecutionState.EXECUTING) ExecutionState.PAUSED else null
            MissionExecutionSignal.EXECUTING ->
                if (current.execution == ExecutionState.PAUSED) ExecutionState.EXECUTING else null
            MissionExecutionSignal.COMPLETED ->
                if (current.execution in setOf(ExecutionState.STARTING, ExecutionState.EXECUTING, ExecutionState.PAUSED)) {
                    ExecutionState.FINISHED
                } else {
                    null
                }
            MissionExecutionSignal.INTERRUPTED,
            MissionExecutionSignal.DISCONNECTED,
            -> if (current.execution in setOf(ExecutionState.STARTING, ExecutionState.EXECUTING, ExecutionState.PAUSED)) {
                ExecutionState.FAILED
            } else {
                null
            }
            else -> null
        } ?: return

        state.apply(
            MissionStateEvent.ExecutionChanged(
                sourceRevision = executionStateRevision.incrementAndGet(),
                missionRevision = missionRevision,
                deviceGeneration = deviceGeneration,
                state = target,
            ),
        )
        if (target == ExecutionState.FINISHED || target == ExecutionState.FAILED) {
            executionSignalSource.invalidateStartAttempt()
            flightPhase.invalidate(missionRevision, deviceGeneration)
        }
    }

    private fun acceptPhaseFact(fact: MissionPhaseFact) = lifecycleLock.withLock {
        val snapshot = state.snapshot()
        if (
            snapshot.missionRevision != fact.missionRevision ||
            snapshot.deviceGeneration != fact.deviceGeneration ||
            snapshot.file?.fileName != fact.fileName
        ) {
            return
        }
        if (fact.phase == MissionPhase.ROUTE_EXECUTION_STARTED) {
            state.apply(
                MissionStateEvent.ExecutionChanged(
                    sourceRevision = executionStateRevision.incrementAndGet(),
                    missionRevision = fact.missionRevision,
                    deviceGeneration = fact.deviceGeneration,
                    state = com.skycommand.relay.wayline.state.ExecutionState.EXECUTING,
                ),
            )
        }
        phaseListeners.toList().forEach { listener -> runCatching { listener.onPhaseChanged(fact) } }
    }

    private class TrackedOperation {
        val completed = AtomicBoolean(false)
        lateinit var cancellation: OperationCancellationHandle

        fun install(cancellation: OperationCancellationHandle) {
            this.cancellation = cancellation
        }
    }

    private inner class GatewayMissionSink : MissionSink {
        private var transferId: String? = null

        override fun begin(metadata: GatewayMissionMetadata): MissionSinkResult = lifecycleLock.withLock {
            if (incomingTransferActive || !canReplaceCurrentMission()) {
                return MissionSinkResult.Rejected
            }
            val result = stagingLock.withLock {
                staging.begin(MissionMetadata(metadata.fileName, metadata.size, metadata.sha256))
            }
            if (result !is StagingRequestResult.Accepted) {
                return MissionSinkResult.Rejected
            }
            incomingTransferActive = true
            transferId = metadata.transferId
            MissionSinkResult.Accepted
        }

        override fun append(bytes: ByteArray): MissionSinkResult = lifecycleLock.withLock {
            if (!incomingTransferActive) {
                return MissionSinkResult.Rejected
            }
            val result = stagingLock.withLock { staging.write(bytes.copyOf()) }
            if (result is StagingRequestResult.Accepted) MissionSinkResult.Accepted else MissionSinkResult.Rejected
        }

        override fun complete(): MissionSinkCompletionResult = lifecycleLock.withLock {
            if (!incomingTransferActive || !canReplaceCurrentMission()) {
                cancelIncomingTransfer()
                return MissionSinkCompletionResult.Rejected
            }
            val result = stagingLock.withLock { staging.complete() }
            val metadata = (result as? StagingCompleteResult.Staged)?.metadata
            if (metadata == null) {
                incomingTransferActive = false
                transferId = null
                return MissionSinkCompletionResult.Rejected
            }
            val completedTransferId = transferId
            incomingTransferActive = false
            transferId = null
            recordStaged(metadata)
            MissionSinkCompletionResult.Accepted(
                StagedMission(
                    transferId = requireNotNull(completedTransferId),
                    fileName = metadata.fileName,
                    size = metadata.expectedSize,
                    sha256 = metadata.sha256,
                    readableByMissionModule = MissionReadable {
                        check(state.snapshot().file == metadata) { "Staged mission is no longer current" }
                        contentReader.read(metadata).inputStream()
                    },
                ),
            )
        }

        override fun abort(reason: MissionAbortReason) = lifecycleLock.withLock { cancelIncomingTransfer() }

        private fun canReplaceCurrentMission(): Boolean {
            val execution = state.snapshot().execution
            val canReplace = execution == ExecutionState.NOT_STARTED ||
                execution == ExecutionState.FINISHED ||
                (execution == ExecutionState.FAILED && runCatching { groundedReadinessGate.allowsStart() }.getOrDefault(false))
            return activeOperations.isEmpty() && canReplace
        }

        private fun cancelIncomingTransfer() {
            stagingLock.withLock { staging.cancel() }
            incomingTransferActive = false
            transferId = null
        }
    }

    private class RelayCompletion(
        private val completion: CommandCompletion,
        private val commandName: String,
    ) : WaylineActionCompletion {
        private val finished = AtomicBoolean(false)

        override fun complete(outcome: WaylineActionTerminalOutcome) {
            if (!finished.compareAndSet(false, true)) return
            if (outcome == WaylineActionTerminalOutcome.SUCCEEDED) {
                completion.succeed(successDetail(commandName))
            } else {
                completion.reject("Mission operation failed")
            }
        }

        fun succeed(detail: String) {
            if (finished.compareAndSet(false, true)) completion.succeed(detail)
        }

        fun reject(detail: String) {
            if (finished.compareAndSet(false, true)) completion.reject(detail)
        }

        private fun successDetail(commandName: String): String = when (commandName) {
            "wayline.upload" -> "Mission uploaded"
            "wayline.start" -> "Mission started"
            "wayline.pause" -> "Mission paused"
            "wayline.resume" -> "Mission resumed"
            "wayline.stop" -> "Mission stopped"
            else -> "Mission operation completed"
        }
    }

    private fun UploadTerminalOutcome.toWaylineOutcome(): WaylineActionTerminalOutcome = when (this) {
        UploadTerminalOutcome.SUCCEEDED -> WaylineActionTerminalOutcome.SUCCEEDED
        UploadTerminalOutcome.FAILED -> WaylineActionTerminalOutcome.FAILED
        UploadTerminalOutcome.TIMED_OUT -> WaylineActionTerminalOutcome.TIMED_OUT
        UploadTerminalOutcome.CANCELLED -> WaylineActionTerminalOutcome.CANCELLED
    }

    private fun ExecutionTerminalOutcome.toWaylineOutcome(): WaylineActionTerminalOutcome = when (this) {
        ExecutionTerminalOutcome.SUCCEEDED -> WaylineActionTerminalOutcome.SUCCEEDED
        ExecutionTerminalOutcome.FAILED -> WaylineActionTerminalOutcome.FAILED
        ExecutionTerminalOutcome.TIMED_OUT -> WaylineActionTerminalOutcome.TIMED_OUT
        ExecutionTerminalOutcome.CANCELLED -> WaylineActionTerminalOutcome.CANCELLED
    }

    companion object {
        fun create(dependencies: WaylineMissionDependencies): WaylineMission = WaylineMission(dependencies)
    }
}

fun interface MissionPhaseListener {
    fun onPhaseChanged(fact: MissionPhaseFact)
}
