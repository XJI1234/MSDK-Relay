package com.skycommand.relay.wayline

import com.skycommand.relay.device.operation.DjiOperationCoordinator
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
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.staging.MissionStaging
import com.skycommand.relay.wayline.staging.StagingCompleteResult
import com.skycommand.relay.wayline.staging.StagingRequestResult
import com.skycommand.relay.wayline.staging.StagingStorage
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
    val operationCoordinator: DjiOperationCoordinator,
    val uploadTimeoutMillis: Long = 30_000,
    val controlTimeoutMillis: Long = 30_000,
    val diagnosticSink: MissionStateDiagnosticSink = MissionStateDiagnosticSink { },
)

class WaylineMission private constructor(dependencies: WaylineMissionDependencies) {
    private val stagingLock = ReentrantLock()
    private val stagingRevision = AtomicLong(0)
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
    )
    private val commands = WaylineCommandHandler.create(staging, Actions())
    private val contentReader = dependencies.contentReader

    fun commandHandler(): CommandHandler = CommandHandler(::handleCommand)

    fun missionSink(): MissionSink = GatewayMissionSink()

    fun snapshot(): MissionSnapshot = state.snapshot()

    fun onChanged(listener: MissionStateListener): Registration = state.onChanged(listener)

    private fun handleCommand(command: CommandFrame, completion: CommandCompletion) {
        if (command.name == "wayline.generate") {
            stagingLock.withLock {
                when (val result = commands.handle(command)) {
                    is WaylineCommandResult.Succeeded -> {
                        val metadata = staging.current()
                        if (metadata == null) {
                            completion.reject("Mission staging failed")
                        } else {
                            recordStaged(metadata)
                            completion.succeed(result.detail)
                        }
                    }
                    is WaylineCommandResult.Rejected -> completion.reject(detailFor(result.reason))
                    is WaylineCommandResult.Accepted -> completion.reject("Mission command is invalid")
                }
            }
            return
        }

        val terminal = RelayCompletion(completion, command.name)
        when (val result = commands.handle(command, terminal)) {
            is WaylineCommandResult.Accepted -> Unit
            is WaylineCommandResult.Rejected -> terminal.reject(detailFor(result.reason))
            is WaylineCommandResult.Succeeded -> terminal.succeed(result.detail)
        }
    }

    private fun recordStaged(metadata: MissionMetadata) {
        state.apply(MissionStateEvent.FileStaged(stagingRevision.incrementAndGet(), metadata))
    }

    private fun detailFor(reason: WaylineCommandRejection): String = when (reason) {
        WaylineCommandRejection.UNKNOWN_COMMAND -> "Wayline command is not available"
        WaylineCommandRejection.INVALID_FIELDS -> "Wayline command fields are invalid"
        WaylineCommandRejection.CONFIRMATION_REQUIRED -> "Confirmation is required"
        WaylineCommandRejection.GENERATION_FAILED -> "Mission generation failed"
        WaylineCommandRejection.STAGING_FAILED -> "Mission staging failed"
        WaylineCommandRejection.CAPABILITY_REJECTED -> "Mission operation was rejected"
    }

    private inner class Actions : WaylineCommandActions {
        override fun upload(completion: WaylineActionCompletion): WaylineActionResult =
            uploader.start(UploadTerminalListener { completion.complete(it.toWaylineOutcome()) }).toActionResult()

        override fun start(completion: WaylineActionCompletion): WaylineActionResult =
            executor.start(ExecutionTerminalListener { completion.complete(it.toWaylineOutcome()) }).toActionResult()

        override fun pause(completion: WaylineActionCompletion): WaylineActionResult =
            executor.pause(ExecutionTerminalListener { completion.complete(it.toWaylineOutcome()) }).toActionResult()

        override fun resume(completion: WaylineActionCompletion): WaylineActionResult =
            executor.resume(ExecutionTerminalListener { completion.complete(it.toWaylineOutcome()) }).toActionResult()

        override fun stop(completion: WaylineActionCompletion): WaylineActionResult =
            executor.stop(ExecutionTerminalListener { completion.complete(it.toWaylineOutcome()) }).toActionResult()
    }

    private inner class GatewayMissionSink : MissionSink {
        private var transferId: String? = null

        override fun begin(metadata: GatewayMissionMetadata): MissionSinkResult = stagingLock.withLock {
            val result = staging.begin(MissionMetadata(metadata.fileName, metadata.size, metadata.sha256))
            if (result is StagingRequestResult.Accepted) {
                transferId = metadata.transferId
                MissionSinkResult.Accepted
            } else {
                MissionSinkResult.Rejected
            }
        }

        override fun append(bytes: ByteArray): MissionSinkResult = stagingLock.withLock {
            if (staging.write(bytes.copyOf()) is StagingRequestResult.Accepted) MissionSinkResult.Accepted else MissionSinkResult.Rejected
        }

        override fun complete(): MissionSinkCompletionResult = stagingLock.withLock {
            val result = staging.complete()
            val metadata = (result as? StagingCompleteResult.Staged)?.metadata
                ?: return MissionSinkCompletionResult.Rejected
            recordStaged(metadata)
            MissionSinkCompletionResult.Accepted(
                StagedMission(
                    transferId = requireNotNull(transferId),
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

        override fun abort(reason: MissionAbortReason) {
            stagingLock.withLock {
                staging.cancel()
                transferId = null
            }
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

    private fun UploadStartResult.toActionResult(): WaylineActionResult = when (this) {
        is UploadStartResult.Accepted -> WaylineActionResult.Accepted
        is UploadStartResult.Rejected -> WaylineActionResult.Rejected
    }

    private fun ExecutionRequestResult.toActionResult(): WaylineActionResult = when (this) {
        is ExecutionRequestResult.Accepted -> WaylineActionResult.Accepted
        is ExecutionRequestResult.Rejected -> WaylineActionResult.Rejected
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
