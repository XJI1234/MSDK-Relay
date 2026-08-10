package com.skycommand.relay.wayline.executor

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionStateEvent
import com.skycommand.relay.wayline.state.MissionStateStore
import com.skycommand.relay.wayline.state.UploadState
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface ControlCompletion {
    fun succeed()
    fun fail()
}

interface MissionControlPort {
    fun start(completion: ControlCompletion)
    fun pause(completion: ControlCompletion)
    fun resume(completion: ControlCompletion)
    fun stop(completion: ControlCompletion)
}

sealed interface ExecutionRequestResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : ExecutionRequestResult
    data class Rejected(val reason: ExecutionRejection) : ExecutionRequestResult
}

enum class ExecutionRejection {
    NO_MISSION,
    NOT_UPLOADED,
    INVALID_STATE,
    ALREADY_ACTIVE,
    OPERATION_REJECTED,
}

class MissionExecutor private constructor(
    private val stateStore: MissionStateStore,
    private val controlPort: MissionControlPort,
    private val coordinator: DjiOperationCoordinator,
    private val timeoutMillis: Long,
) {
    private val lock = ReentrantLock()
    private val sourceRevision = AtomicLong(0)
    private var active: ActiveCommand? = null

    fun start(): ExecutionRequestResult = request(Command.START)
    fun pause(): ExecutionRequestResult = request(Command.PAUSE)
    fun resume(): ExecutionRequestResult = request(Command.RESUME)
    fun stop(): ExecutionRequestResult = request(Command.STOP)

    private fun request(command: Command): ExecutionRequestResult {
        val snapshot = stateStore.snapshot()
        val missionRevision = snapshot.missionRevision
            ?: return ExecutionRequestResult.Rejected(ExecutionRejection.NO_MISSION)
        if (snapshot.upload != UploadState.UPLOADED) {
            return ExecutionRequestResult.Rejected(ExecutionRejection.NOT_UPLOADED)
        }
        val activeCommand = lock.withLock { active }
        if (activeCommand != null) {
            return ExecutionRequestResult.Rejected(ExecutionRejection.ALREADY_ACTIVE)
        }
        if (!command.allowedFrom(snapshot.execution)) {
            return ExecutionRequestResult.Rejected(ExecutionRejection.INVALID_STATE)
        }

        val operation = ActiveCommand(Any(), missionRevision, command)
        lock.withLock {
            if (active != null) return ExecutionRequestResult.Rejected(ExecutionRejection.ALREADY_ACTIVE)
            active = operation
        }
        applyState(operation, command.pendingState)

        val submission = coordinator.submit(
            action = DjiOperation { operationCompletion ->
                try {
                    val completion = object : ControlCompletion {
                        override fun succeed() = operationCompletion.succeed()
                        override fun fail() = operationCompletion.fail()
                    }
                    when (command) {
                        Command.START -> controlPort.start(completion)
                        Command.PAUSE -> controlPort.pause(completion)
                        Command.RESUME -> controlPort.resume(completion)
                        Command.STOP -> controlPort.stop(completion)
                    }
                } catch (_: Throwable) {
                    operationCompletion.fail()
                }
            },
            timeoutMillis = timeoutMillis,
            listener = OperationResultListener { outcome -> finish(operation, outcome) },
        )
        val accepted = submission as? SubmissionResult.Accepted
        if (accepted == null) {
            finishBeforeSubmission(operation)
            return ExecutionRequestResult.Rejected(ExecutionRejection.OPERATION_REJECTED)
        }
        return ExecutionRequestResult.Accepted(accepted.cancellation)
    }

    private fun finish(operation: ActiveCommand, outcome: OperationOutcome) {
        if (!clearIfActive(operation)) return
        val next = if (outcome == OperationOutcome.SUCCEEDED) operation.command.successState else ExecutionState.FAILED
        applyState(operation, next)
    }

    private fun finishBeforeSubmission(operation: ActiveCommand) {
        if (!clearIfActive(operation)) return
        applyState(operation, ExecutionState.FAILED)
    }

    private fun applyState(operation: ActiveCommand, state: ExecutionState) {
        runCatching {
            stateStore.apply(
                MissionStateEvent.ExecutionChanged(
                    sourceRevision = sourceRevision.incrementAndGet(),
                    missionRevision = operation.missionRevision,
                    state = state,
                ),
            )
        }
    }

    private fun clearIfActive(operation: ActiveCommand): Boolean = lock.withLock {
        if (active !== operation) false else {
            active = null
            true
        }
    }

    private enum class Command(
        val pendingState: ExecutionState,
        val successState: ExecutionState,
        val allowed: Set<ExecutionState>,
    ) {
        START(ExecutionState.STARTING, ExecutionState.EXECUTING, setOf(ExecutionState.NOT_STARTED, ExecutionState.FAILED)),
        PAUSE(ExecutionState.EXECUTING, ExecutionState.PAUSED, setOf(ExecutionState.EXECUTING)),
        RESUME(ExecutionState.EXECUTING, ExecutionState.EXECUTING, setOf(ExecutionState.PAUSED)),
        STOP(ExecutionState.STOPPING, ExecutionState.FINISHED, setOf(ExecutionState.STARTING, ExecutionState.EXECUTING, ExecutionState.PAUSED));

        fun allowedFrom(state: ExecutionState): Boolean = state in allowed
    }

    private data class ActiveCommand(
        val token: Any,
        val missionRevision: Long,
        val command: Command,
    )

    companion object {
        fun create(
            stateStore: MissionStateStore,
            controlPort: MissionControlPort,
            coordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
        ): MissionExecutor = MissionExecutor(stateStore, controlPort, coordinator, timeoutMillis)
    }
}
