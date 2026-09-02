package com.skycommand.relay.stream.dji

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.state.StreamMetrics
import com.skycommand.relay.stream.state.StreamStartResult
import com.skycommand.relay.stream.state.StreamStateStore
import com.skycommand.relay.stream.state.StreamStopResult
import com.skycommand.relay.stream.state.StreamUpdateResult
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface StreamDjiCompletion {
    fun succeed()

    fun fail()
}

interface DjiStreamPort {
    fun start(
        config: ValidatedStreamConfig,
        metrics: (StreamMetrics) -> Unit,
        runtimeFailure: () -> Unit,
        completion: StreamDjiCompletion,
    )

    fun stop(completion: StreamDjiCompletion)

    fun close() = Unit
}

fun interface StreamDjiTerminalListener {
    fun onCompleted(outcome: StreamDjiTerminalOutcome)
}

enum class StreamDjiTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface DjiStreamStartResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : DjiStreamStartResult

    data class Rejected(val reason: DjiStreamRejection) : DjiStreamStartResult
}

sealed interface DjiStreamStopResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : DjiStreamStopResult

    data class Rejected(val reason: DjiStreamRejection) : DjiStreamStopResult
}

enum class DjiStreamRejection {
    ALREADY_ACTIVE,
    NO_ACTIVE_STREAM,
    ALREADY_STOPPING,
    OPERATION_REJECTED,
}

class DjiStreamAdapter private constructor(
    private val stateStore: StreamStateStore,
    private val djiPort: DjiStreamPort,
    private val coordinator: DjiOperationCoordinator,
    private val timeoutMillis: Long,
) {
    private val recoveryLock = ReentrantLock()
    private var recoveryQueued = false

    fun start(
        config: ValidatedStreamConfig,
        listener: StreamDjiTerminalListener = StreamDjiTerminalListener { },
    ): DjiStreamStartResult {
        val state = stateStore.requestStart(config)
        val operationId = (state as? StreamStartResult.Accepted)?.operationId
            ?: return DjiStreamStartResult.Rejected(DjiStreamRejection.ALREADY_ACTIVE)
        val submission = coordinator.submit(
            action = object : DjiOperation {
                override fun run(completion: OperationCompletion) {
                    djiPort.start(
                        config = config,
                        metrics = { metrics -> stateStore.updateMetrics(operationId, metrics) },
                        runtimeFailure = {
                            if (stateStore.markFailed(operationId, "Stream runtime failed") is StreamUpdateResult.Applied) {
                                requestRecoveryStop()
                            }
                        },
                        completion = completion.asDjiCompletion(),
                    )
                }

                override fun onLateDjiCompletion(outcome: OperationOutcome) {
                    if (outcome == OperationOutcome.SUCCEEDED) requestRecoveryStop()
                }
            },
            timeoutMillis = timeoutMillis,
            listener = OperationResultListener { outcome ->
                completeStart(operationId, outcome)
                runCatching { listener.onCompleted(outcome.toTerminalOutcome()) }
            },
        )
        val accepted = submission as? SubmissionResult.Accepted
        if (accepted == null) {
            stateStore.markFailed(operationId, "Stream operation rejected")
            return DjiStreamStartResult.Rejected(DjiStreamRejection.OPERATION_REJECTED)
        }
        return DjiStreamStartResult.Accepted(accepted.cancellation)
    }

    fun stop(listener: StreamDjiTerminalListener = StreamDjiTerminalListener { }): DjiStreamStopResult {
        val state = stateStore.requestStop()
        val operationId = (state as? StreamStopResult.Accepted)?.operationId
            ?: return DjiStreamStopResult.Rejected(
                when ((state as StreamStopResult.Rejected).reason) {
                    com.skycommand.relay.stream.state.StreamStopRejection.NO_ACTIVE_STREAM -> DjiStreamRejection.NO_ACTIVE_STREAM
                    com.skycommand.relay.stream.state.StreamStopRejection.ALREADY_STOPPING -> DjiStreamRejection.ALREADY_STOPPING
                },
            )
        val submission = coordinator.submit(
            action = object : DjiOperation {
                override fun run(completion: OperationCompletion) {
                    djiPort.stop(completion.asDjiCompletion())
                }

                override fun onLateDjiCompletion(outcome: OperationOutcome) {
                    if (outcome == OperationOutcome.FAILED) requestRecoveryStop()
                }
            },
            timeoutMillis = timeoutMillis,
            listener = OperationResultListener { outcome ->
                completeStop(operationId, outcome)
                runCatching { listener.onCompleted(outcome.toTerminalOutcome()) }
            },
        )
        val accepted = submission as? SubmissionResult.Accepted
        if (accepted == null) {
            stateStore.markFailed(operationId, "Stream operation rejected")
            return DjiStreamStopResult.Rejected(DjiStreamRejection.OPERATION_REJECTED)
        }
        return DjiStreamStopResult.Accepted(accepted.cancellation)
    }

    private fun completeStart(operationId: Long, outcome: OperationOutcome) {
        if (outcome == OperationOutcome.SUCCEEDED) {
            stateStore.markStarted(operationId)
        } else {
            stateStore.markFailed(operationId, "Stream start failed")
        }
    }

    private fun completeStop(operationId: Long, outcome: OperationOutcome) {
        if (outcome == OperationOutcome.SUCCEEDED) {
            stateStore.markStopped(operationId)
        } else {
            if (stateStore.markFailed(operationId, "Stream stop failed") is StreamUpdateResult.Applied) {
                requestRecoveryStop()
            }
        }
    }

    /** Schedules best-effort stream cleanup through the shared DJI operation queue. */
    fun requestRecoveryStop() {
        val shouldSubmit = recoveryLock.withLock {
            if (recoveryQueued) false else {
                recoveryQueued = true
                true
            }
        }
        if (!shouldSubmit) return
        val submission = coordinator.submit(
            action = DjiOperation { completion -> djiPort.stop(completion.asDjiCompletion()) },
            timeoutMillis = timeoutMillis,
            listener = OperationResultListener {
                recoveryLock.withLock { recoveryQueued = false }
            },
        )
        if (submission !is SubmissionResult.Accepted) {
            recoveryLock.withLock { recoveryQueued = false }
        }
    }

    private fun OperationCompletion.asDjiCompletion(): StreamDjiCompletion = object : StreamDjiCompletion {
        override fun succeed() = this@asDjiCompletion.succeed()

        override fun fail() = this@asDjiCompletion.fail()
    }

    private fun OperationOutcome.toTerminalOutcome(): StreamDjiTerminalOutcome = when (this) {
        OperationOutcome.SUCCEEDED -> StreamDjiTerminalOutcome.SUCCEEDED
        OperationOutcome.FAILED -> StreamDjiTerminalOutcome.FAILED
        OperationOutcome.TIMED_OUT -> StreamDjiTerminalOutcome.TIMED_OUT
        OperationOutcome.CANCELLED -> StreamDjiTerminalOutcome.CANCELLED
    }

    companion object {
        fun create(
            stateStore: StreamStateStore,
            djiPort: DjiStreamPort,
            coordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
        ): DjiStreamAdapter = DjiStreamAdapter(stateStore, djiPort, coordinator, timeoutMillis)
    }
}
