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

interface StreamDjiCompletion {
    fun succeed()

    fun fail()
}

interface DjiStreamPort {
    fun start(
        config: ValidatedStreamConfig,
        metrics: (StreamMetrics) -> Unit,
        completion: StreamDjiCompletion,
    )

    fun stop(completion: StreamDjiCompletion)
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
    fun start(
        config: ValidatedStreamConfig,
        listener: StreamDjiTerminalListener = StreamDjiTerminalListener { },
    ): DjiStreamStartResult {
        val state = stateStore.requestStart(config)
        val operationId = (state as? StreamStartResult.Accepted)?.operationId
            ?: return DjiStreamStartResult.Rejected(DjiStreamRejection.ALREADY_ACTIVE)
        val submission = coordinator.submit(
            action = DjiOperation { completion ->
                djiPort.start(
                    config = config,
                    metrics = { metrics -> stateStore.updateMetrics(operationId, metrics) },
                    completion = completion.asDjiCompletion(),
                )
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
            action = DjiOperation { completion -> djiPort.stop(completion.asDjiCompletion()) },
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
            stateStore.markFailed(operationId, "Stream stop failed")
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
