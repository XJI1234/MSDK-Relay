package com.skycommand.relay.flight.dji

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.flight.command.FlightAction
import java.util.concurrent.atomic.AtomicBoolean

interface FlightDjiCompletion {
    fun succeed()
    fun fail()
}

interface DjiFlightPort {
    fun execute(action: FlightAction, completion: FlightDjiCompletion)
    fun close() = Unit
}

fun interface FlightDjiTerminalListener {
    fun onCompleted(outcome: FlightDjiTerminalOutcome)
}

enum class FlightDjiTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface FlightSubmissionResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : FlightSubmissionResult
    data object Rejected : FlightSubmissionResult
}

class DjiFlightAdapter private constructor(
    private val port: DjiFlightPort,
    private val coordinator: DjiOperationCoordinator,
    private val timeoutMillis: Long,
) {
    fun execute(
        action: FlightAction,
        listener: FlightDjiTerminalListener = FlightDjiTerminalListener { },
    ): FlightSubmissionResult {
        val terminal = OnceTerminal(listener)
        val submission = coordinator.submit(
            DjiOperation { completion ->
                port.execute(action, completion.asFlightCompletion())
            },
            timeoutMillis,
            OperationResultListener { outcome -> terminal.complete(outcome.toTerminalOutcome()) },
        )
        return when (submission) {
            is SubmissionResult.Accepted -> FlightSubmissionResult.Accepted(submission.cancellation)
            SubmissionResult.Rejected -> FlightSubmissionResult.Rejected
        }
    }

    private fun OperationCompletion.asFlightCompletion(): FlightDjiCompletion = object : FlightDjiCompletion {
        override fun succeed() = this@asFlightCompletion.succeed()
        override fun fail() = this@asFlightCompletion.fail()
    }

    private fun OperationOutcome.toTerminalOutcome(): FlightDjiTerminalOutcome = when (this) {
        OperationOutcome.SUCCEEDED -> FlightDjiTerminalOutcome.SUCCEEDED
        OperationOutcome.FAILED -> FlightDjiTerminalOutcome.FAILED
        OperationOutcome.TIMED_OUT -> FlightDjiTerminalOutcome.TIMED_OUT
        OperationOutcome.CANCELLED -> FlightDjiTerminalOutcome.CANCELLED
    }

    private class OnceTerminal(private val delegate: FlightDjiTerminalListener) {
        private val completed = AtomicBoolean(false)

        fun complete(outcome: FlightDjiTerminalOutcome) {
            if (completed.compareAndSet(false, true)) runCatching { delegate.onCompleted(outcome) }
        }
    }

    companion object {
        fun create(
            port: DjiFlightPort,
            coordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
        ): DjiFlightAdapter = DjiFlightAdapter(port, coordinator, timeoutMillis)
    }
}
