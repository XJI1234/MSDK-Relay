package com.skycommand.relay.device.pairing

import com.skycommand.relay.device.capability.DeviceCapabilityReader
import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.PairingState

interface PairingPort {
    fun startPairing(): DjiOperation

    fun stopPairing(): DjiOperation
}

enum class PairingRejection {
    NOT_READY,
    NOT_RUNNING,
    INVALID_TIMEOUT,
    DEPENDENCY_FAILURE,
}

sealed interface PairingRequestResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : PairingRequestResult

    data class Rejected(val reason: PairingRejection) : PairingRequestResult
}

enum class PairingOperationResult {
    RequestAccepted,
    RequestFailed,
    RequestTimedOut,
    RequestCancelled,
}

class PairingController private constructor(
    private val store: DeviceStateStore,
    private val coordinator: DjiOperationCoordinator,
    private val port: PairingPort,
) {
    fun start(
        timeoutMillis: Long,
        listener: (PairingOperationResult) -> Unit,
    ): PairingRequestResult = submit(
        timeoutMillis = timeoutMillis,
        allowed = { DeviceCapabilityReader.read(it).canStartPairing },
        rejected = PairingRejection.NOT_READY,
        targetState = PairingState.PAIRING,
        operation = { port.startPairing() },
        listener = listener,
    )

    fun stop(
        timeoutMillis: Long,
        listener: (PairingOperationResult) -> Unit,
    ): PairingRequestResult = submit(
        timeoutMillis = timeoutMillis,
        allowed = { it.pairing in setOf(PairingState.PAIRING, PairingState.PAIRED, PairingState.STOPPING) },
        rejected = PairingRejection.NOT_RUNNING,
        targetState = PairingState.STOPPING,
        operation = { port.stopPairing() },
        listener = listener,
    )

    fun state(): PairingState = store.snapshot().pairing

    private fun submit(
        timeoutMillis: Long,
        allowed: (DeviceSnapshot) -> Boolean,
        rejected: PairingRejection,
        targetState: PairingState,
        operation: () -> DjiOperation,
        listener: (PairingOperationResult) -> Unit,
    ): PairingRequestResult {
        if (timeoutMillis !in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            return PairingRequestResult.Rejected(PairingRejection.INVALID_TIMEOUT)
        }
        if (!allowed(store.snapshot())) {
            return PairingRequestResult.Rejected(rejected)
        }

        val action = try {
            operation()
        } catch (_: Throwable) {
            store.applyPairing(PairingState.FAILED)
            return PairingRequestResult.Rejected(PairingRejection.DEPENDENCY_FAILURE)
        }
        store.applyPairing(targetState)
        val submission = coordinator.submit(action, timeoutMillis, OperationResultListener { outcome ->
            when (outcome) {
                OperationOutcome.SUCCEEDED -> listener(PairingOperationResult.RequestAccepted)
                OperationOutcome.FAILED -> fail(listener, PairingOperationResult.RequestFailed)
                OperationOutcome.TIMED_OUT -> fail(listener, PairingOperationResult.RequestTimedOut)
                OperationOutcome.CANCELLED -> fail(listener, PairingOperationResult.RequestCancelled)
            }
        })
        return when (submission) {
            is SubmissionResult.Accepted -> PairingRequestResult.Accepted(submission.cancellation)
            SubmissionResult.Rejected -> {
                store.applyPairing(PairingState.FAILED)
                PairingRequestResult.Rejected(PairingRejection.INVALID_TIMEOUT)
            }
        }
    }

    private fun fail(listener: (PairingOperationResult) -> Unit, result: PairingOperationResult) {
        store.applyPairing(PairingState.FAILED)
        listener(result)
    }

    companion object {
        private const val MIN_TIMEOUT_MILLIS = 1_000L
        private const val MAX_TIMEOUT_MILLIS = 60_000L

        fun create(
            store: DeviceStateStore,
            coordinator: DjiOperationCoordinator,
            port: PairingPort,
        ): PairingController = PairingController(store, coordinator, port)
    }
}
