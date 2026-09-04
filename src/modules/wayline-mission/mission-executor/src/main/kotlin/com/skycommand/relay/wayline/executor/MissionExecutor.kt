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
    fun fail(failure: MissionControlFailure?) = fail()
}

class MissionControlFailure private constructor(
    val errorCode: String,
    val errorDescription: String,
) {
    override fun equals(other: Any?): Boolean = other is MissionControlFailure &&
        errorCode == other.errorCode && errorDescription == other.errorDescription

    override fun hashCode(): Int = 31 * errorCode.hashCode() + errorDescription.hashCode()

    override fun toString(): String = "MissionControlFailure(errorCode=$errorCode, errorDescription=$errorDescription)"

    companion object {
        fun fromDjiError(errorCode: String?, errorDescription: String?): MissionControlFailure = MissionControlFailure(
            normalize(errorCode, maxCodePoints = 128, fallback = "UNKNOWN_DJI_ERROR"),
            normalize(errorDescription, maxCodePoints = 512, fallback = "DJI did not provide an error description"),
        )

        private fun normalize(value: String?, maxCodePoints: Int, fallback: String): String {
            if (value == null) return fallback
            val result = StringBuilder()
            var offset = 0
            var count = 0
            while (offset < value.length && count < maxCodePoints) {
                val codePoint = value.codePointAt(offset)
                if (!Character.isISOControl(codePoint)) {
                    result.appendCodePoint(codePoint)
                    count += 1
                }
                offset += Character.charCount(codePoint)
            }
            return result.toString().trim().ifBlank { fallback }
        }
    }
}

interface MissionControlPort {
    fun start(completion: ControlCompletion)
    fun pause(completion: ControlCompletion)
    fun resume(completion: ControlCompletion)
    fun stop(completion: ControlCompletion)
}

/** The composition root supplies the current physical safety facts before startMission. */
fun interface MissionStartSafetyGate {
    fun allowsStart(): Boolean
}

fun interface ExecutionTerminalListener {
    fun onCompleted(outcome: ExecutionTerminalOutcome)
    fun onCompleted(outcome: ExecutionTerminalOutcome, failure: MissionControlFailure?) = onCompleted(outcome)
}

enum class ExecutionTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
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
    OPERATION_UNCONFIRMED,
    SAFETY_CHECK_FAILED,
    OPERATION_REJECTED,
}

class MissionExecutor private constructor(
    private val stateStore: MissionStateStore,
    private val controlPort: MissionControlPort,
    private val coordinator: DjiOperationCoordinator,
    private val timeoutMillis: Long,
    private val sourceRevision: AtomicLong,
    private val startSafetyGate: MissionStartSafetyGate,
) {
    private val lock = ReentrantLock()
    private var active: ActiveCommand? = null
    private var unconfirmedControl: ActiveCommand? = null

    fun start(listener: ExecutionTerminalListener = ExecutionTerminalListener { }): ExecutionRequestResult = request(Command.START, listener)
    fun pause(listener: ExecutionTerminalListener = ExecutionTerminalListener { }): ExecutionRequestResult = request(Command.PAUSE, listener)
    fun resume(listener: ExecutionTerminalListener = ExecutionTerminalListener { }): ExecutionRequestResult = request(Command.RESUME, listener)
    fun stop(listener: ExecutionTerminalListener = ExecutionTerminalListener { }): ExecutionRequestResult = request(Command.STOP, listener)

    /** A matching DJI state observation resolves a pause or resume whose command receipt was lost. */
    fun observeExecutionState(
        state: ExecutionState,
        missionRevision: Long,
        deviceGeneration: Long,
    ) {
        val confirmation = lock.withLock {
            val unresolved = unconfirmedControl ?: return
            if (
                unresolved.missionRevision == missionRevision &&
                unresolved.deviceGeneration == deviceGeneration &&
                unresolved.command.observedState == state
            ) {
                unresolved.hardwareConfirmation
            } else null
        } ?: return
        if (!confirmation()) return
        lock.withLock {
            val unresolved = unconfirmedControl ?: return
            if (
                unresolved.missionRevision == missionRevision &&
                unresolved.deviceGeneration == deviceGeneration &&
                unresolved.command.observedState == state
            ) unconfirmedControl = null
        }
    }

    private fun request(command: Command, listener: ExecutionTerminalListener): ExecutionRequestResult {
        val snapshot = stateStore.snapshot()
        val missionRevision = snapshot.missionRevision
            ?: return ExecutionRequestResult.Rejected(ExecutionRejection.NO_MISSION)
        if (snapshot.upload != UploadState.UPLOADED) {
            return ExecutionRequestResult.Rejected(ExecutionRejection.NOT_UPLOADED)
        }
        val rejection = lock.withLock {
            val unresolved = unconfirmedControl
            if (
                unresolved != null &&
                (unresolved.missionRevision != missionRevision || unresolved.deviceGeneration != snapshot.deviceGeneration)
            ) {
                unconfirmedControl = null
            }
            when {
                active != null -> ExecutionRejection.ALREADY_ACTIVE
                command != Command.STOP && unconfirmedControl != null -> ExecutionRejection.OPERATION_UNCONFIRMED
                else -> null
            }
        }
        if (rejection != null) {
            return ExecutionRequestResult.Rejected(rejection)
        }
        if (!command.allowedFrom(snapshot.execution)) {
            return ExecutionRequestResult.Rejected(ExecutionRejection.INVALID_STATE)
        }
        if (command == Command.START && !allowsStartSafely()) {
            return ExecutionRequestResult.Rejected(ExecutionRejection.SAFETY_CHECK_FAILED)
        }

        val operation = ActiveCommand(Any(), missionRevision, snapshot.deviceGeneration, snapshot.execution, command, listener)
        lock.withLock {
            if (active != null) return ExecutionRequestResult.Rejected(ExecutionRejection.ALREADY_ACTIVE)
            active = operation
        }
        applyState(operation, command.pendingState)

        val submission = coordinator.submit(
            action = DjiOperation { operationCompletion ->
                operation.installHardwareConfirmation(operationCompletion::confirmHardwareSettled)
                try {
                    val completion = object : ControlCompletion {
                        override fun succeed() = operationCompletion.succeed()
                        override fun fail() = operationCompletion.fail()
                        override fun fail(failure: MissionControlFailure?) {
                            operation.installFailure(failure)
                            operationCompletion.fail()
                        }
                    }
                    when (command) {
                        Command.START -> {
                            operation.markInvocationStarted(stateStore.snapshot().revision)
                            controlPort.start(completion)
                        }
                        Command.PAUSE -> {
                            operation.markInvocationStarted(stateStore.snapshot().revision)
                            controlPort.pause(completion)
                        }
                        Command.RESUME -> {
                            operation.markInvocationStarted(stateStore.snapshot().revision)
                            controlPort.resume(completion)
                        }
                        Command.STOP -> {
                            operation.markInvocationStarted(stateStore.snapshot().revision)
                            controlPort.stop(completion)
                        }
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
        var hardwareConfirmation: (() -> Boolean)? = null
        val completed = lock.withLock {
            if (active !== operation) false else {
                active = null
                if (
                    (outcome == OperationOutcome.TIMED_OUT || outcome == OperationOutcome.CANCELLED) &&
                    operation.command.requiresReceiptConfirmation
                ) {
                    if (matchingExecutionStateAlreadyObserved(operation)) {
                        if (outcome == OperationOutcome.TIMED_OUT || outcome == OperationOutcome.CANCELLED) {
                            hardwareConfirmation = operation.hardwareConfirmation
                        }
                    } else {
                        unconfirmedControl = operation
                    }
                }
                true
            }
        }
        if (!completed) return
        val confirmedByObservation = hardwareConfirmation?.invoke()
        if (hardwareConfirmation != null && confirmedByObservation != true) {
            lock.withLock {
                if (unconfirmedControl == null) unconfirmedControl = operation
            }
        }
        nextState(operation, outcome)?.let { applyState(operation, it) }
        runCatching { operation.listener.onCompleted(outcome.toTerminalOutcome(), operation.failure) }
    }

    private fun matchingExecutionStateAlreadyObserved(operation: ActiveCommand): Boolean {
        val observedState = operation.command.observedState ?: return false
        val snapshot = stateStore.snapshot()
        return operation.invocationRevision != null &&
            snapshot.revision > requireNotNull(operation.invocationRevision) &&
            snapshot.missionRevision == operation.missionRevision &&
            snapshot.deviceGeneration == operation.deviceGeneration &&
            snapshot.execution == observedState
    }

    private fun finishBeforeSubmission(operation: ActiveCommand) {
        if (!clearIfActive(operation)) return
        applyState(operation, if (operation.command == Command.START) ExecutionState.FAILED else operation.previousState)
    }

    private fun nextState(operation: ActiveCommand, outcome: OperationOutcome): ExecutionState? = when {
        outcome == OperationOutcome.SUCCEEDED -> operation.command.successState
        operation.command == Command.START && operation.failure != null -> operation.previousState
        operation.command == Command.START -> null
        outcome == OperationOutcome.TIMED_OUT || outcome == OperationOutcome.CANCELLED -> null
        else -> operation.previousState
    }

    private fun allowsStartSafely(): Boolean = runCatching { startSafetyGate.allowsStart() }.getOrDefault(false)

    private fun applyState(operation: ActiveCommand, state: ExecutionState) {
        runCatching {
            stateStore.apply(
                MissionStateEvent.ExecutionChanged(
                    sourceRevision = sourceRevision.incrementAndGet(),
                    missionRevision = operation.missionRevision,
                    deviceGeneration = operation.deviceGeneration,
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
        val observedState: ExecutionState? = null,
    ) {
        START(ExecutionState.STARTING, ExecutionState.STARTING, setOf(ExecutionState.NOT_STARTED, ExecutionState.FAILED)),
        PAUSE(ExecutionState.EXECUTING, ExecutionState.PAUSED, setOf(ExecutionState.EXECUTING), ExecutionState.PAUSED),
        RESUME(ExecutionState.PAUSED, ExecutionState.EXECUTING, setOf(ExecutionState.PAUSED), ExecutionState.EXECUTING),
        STOP(ExecutionState.STOPPING, ExecutionState.FINISHED, setOf(ExecutionState.STARTING, ExecutionState.EXECUTING, ExecutionState.PAUSED));

        val requiresReceiptConfirmation: Boolean get() = observedState != null

        fun allowedFrom(state: ExecutionState): Boolean = state in allowed
    }

    private data class ActiveCommand(
        val token: Any,
        val missionRevision: Long,
        val deviceGeneration: Long,
        val previousState: ExecutionState,
        val command: Command,
        val listener: ExecutionTerminalListener,
        var hardwareConfirmation: (() -> Boolean)? = null,
        var invocationRevision: Long? = null,
        var failure: MissionControlFailure? = null,
    ) {
        fun installHardwareConfirmation(confirmation: () -> Boolean) {
            hardwareConfirmation = confirmation
        }

        fun markInvocationStarted(revision: Long) {
            if (invocationRevision == null) invocationRevision = revision
        }

        fun installFailure(value: MissionControlFailure?) {
            failure = value
        }
    }

    companion object {
        fun create(
            stateStore: MissionStateStore,
            controlPort: MissionControlPort,
            coordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
            executionSourceRevision: AtomicLong = AtomicLong(0),
            startSafetyGate: MissionStartSafetyGate,
        ): MissionExecutor = MissionExecutor(stateStore, controlPort, coordinator, timeoutMillis, executionSourceRevision, startSafetyGate)
    }

    private fun OperationOutcome.toTerminalOutcome(): ExecutionTerminalOutcome = when (this) {
        OperationOutcome.SUCCEEDED -> ExecutionTerminalOutcome.SUCCEEDED
        OperationOutcome.FAILED -> ExecutionTerminalOutcome.FAILED
        OperationOutcome.TIMED_OUT -> ExecutionTerminalOutcome.TIMED_OUT
        OperationOutcome.CANCELLED -> ExecutionTerminalOutcome.CANCELLED
    }
}
