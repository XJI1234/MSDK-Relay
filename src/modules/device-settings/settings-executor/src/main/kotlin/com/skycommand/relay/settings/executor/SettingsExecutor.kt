package com.skycommand.relay.settings.executor

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot

interface SettingsDjiCompletion {
    fun succeed(snapshot: SettingsSnapshot)
    fun fail()
}

interface DjiSettingsPort {
    fun execute(request: SettingsRequest, completion: SettingsDjiCompletion)
    fun close() = Unit
}

fun interface SettingsExecutionListener {
    fun onCompleted(outcome: SettingsExecutionOutcome)
}

sealed interface SettingsExecutionOutcome {
    data class Succeeded(val snapshot: SettingsSnapshot) : SettingsExecutionOutcome
    data object Failed : SettingsExecutionOutcome
    data object TimedOut : SettingsExecutionOutcome
    data object Cancelled : SettingsExecutionOutcome
}

sealed interface SettingsSubmissionResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : SettingsSubmissionResult
    data object Rejected : SettingsSubmissionResult
}

class SettingsExecutor private constructor(
    private val port: DjiSettingsPort,
    private val coordinator: DjiOperationCoordinator,
    private val timeoutMillis: Long,
) {
    fun execute(
        request: SettingsRequest,
        listener: SettingsExecutionListener = SettingsExecutionListener { },
    ): SettingsSubmissionResult {
        var snapshot: SettingsSnapshot? = null
        val submission = coordinator.submit(
            DjiOperation { completion ->
                port.execute(request, object : SettingsDjiCompletion {
                    override fun succeed(value: SettingsSnapshot) {
                        if (value.domain == request.domain()) {
                            snapshot = value
                            completion.succeed()
                        } else {
                            completion.fail()
                        }
                    }
                    override fun fail() = completion.fail()
                })
            },
            timeoutMillis,
            OperationResultListener { outcome ->
                val terminal = when (outcome) {
                    OperationOutcome.SUCCEEDED -> snapshot?.let(SettingsExecutionOutcome::Succeeded)
                        ?: SettingsExecutionOutcome.Failed
                    OperationOutcome.FAILED -> SettingsExecutionOutcome.Failed
                    OperationOutcome.TIMED_OUT -> SettingsExecutionOutcome.TimedOut
                    OperationOutcome.CANCELLED -> SettingsExecutionOutcome.Cancelled
                }
                runCatching { listener.onCompleted(terminal) }
            },
        )
        return when (submission) {
            is SubmissionResult.Accepted -> SettingsSubmissionResult.Accepted(submission.cancellation)
            SubmissionResult.Rejected -> SettingsSubmissionResult.Rejected
        }
    }

    private fun SettingsRequest.domain(): SettingsDomain = when (this) {
        is SettingsRequest.Read -> domain
        is SettingsRequest.WriteCamera -> SettingsDomain.CAMERA
        is SettingsRequest.WriteTransmission -> SettingsDomain.TRANSMISSION
    }

    companion object {
        fun create(
            port: DjiSettingsPort,
            coordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
        ): SettingsExecutor = SettingsExecutor(port, coordinator, timeoutMillis)
    }
}
