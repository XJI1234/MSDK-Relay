package com.skycommand.relay.flight

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.flight.command.FlightActionCompletion
import com.skycommand.relay.flight.command.FlightActionResult
import com.skycommand.relay.flight.command.FlightActionTerminalOutcome
import com.skycommand.relay.flight.command.FlightCommandActions
import com.skycommand.relay.flight.command.FlightCommandHandler
import com.skycommand.relay.flight.command.FlightCommandRejection
import com.skycommand.relay.flight.command.FlightCommandResult
import com.skycommand.relay.flight.dji.DjiFlightAdapter
import com.skycommand.relay.flight.dji.DjiFlightPort
import com.skycommand.relay.flight.dji.FlightDjiTerminalOutcome
import com.skycommand.relay.flight.dji.FlightSubmissionResult
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class FlightControlDependencies(
    val djiPort: DjiFlightPort,
    val operationCoordinator: DjiOperationCoordinator,
    val timeoutMillis: Long = 30_000,
)

class FlightControl private constructor(
    private val dependencies: FlightControlDependencies,
) {
    private val lifecycleLock = ReentrantLock()
    private val activeOperations = mutableSetOf<TrackedOperation>()
    private val adapter = DjiFlightAdapter.create(
        dependencies.djiPort,
        dependencies.operationCoordinator,
        dependencies.timeoutMillis,
    )
    private val commands = FlightCommandHandler.create(Actions())

    fun commandHandler(): CommandHandler = CommandHandler(::handleCommand)

    fun markDeviceUnavailable() {
        lifecycleLock.withLock {
            activeOperations.toList().also { activeOperations.clear() }.forEach { it.cancellation.cancel() }
        }
    }

    fun close() {
        markDeviceUnavailable()
        runCatching { dependencies.djiPort.close() }
    }

    private fun handleCommand(command: CommandFrame, completion: CommandCompletion) {
        val terminal = RelayCompletion(command.name, completion)
        when (val result = commands.handle(command, terminal)) {
            FlightCommandResult.Accepted -> Unit
            is FlightCommandResult.Rejected -> terminal.reject(detailFor(result.reason))
        }
    }

    private fun detailFor(rejection: FlightCommandRejection): String = when (rejection) {
        FlightCommandRejection.UNKNOWN_COMMAND -> "Flight command is not available"
        FlightCommandRejection.INVALID_FIELDS -> "Flight command fields are invalid"
        FlightCommandRejection.CONFIRMATION_REQUIRED -> "Flight command requires explicit confirmation"
        FlightCommandRejection.OPERATION_REJECTED -> "Flight operation was rejected"
    }

    private inner class Actions : FlightCommandActions {
        override fun execute(action: FlightAction, completion: FlightActionCompletion): FlightActionResult = lifecycleLock.withLock {
            val tracked = TrackedOperation()
            when (val result = adapter.execute(action) { outcome ->
                completeTrackedOperation(tracked)
                completion.complete(outcome.toActionOutcome())
            }) {
                is FlightSubmissionResult.Accepted -> {
                    tracked.install(result.cancellation)
                    if (!tracked.completed.get()) activeOperations += tracked
                    FlightActionResult.Accepted
                }
                FlightSubmissionResult.Rejected -> FlightActionResult.Rejected
            }
        }
    }

    private fun completeTrackedOperation(tracked: TrackedOperation) {
        tracked.completed.set(true)
        lifecycleLock.withLock { activeOperations.remove(tracked) }
    }

    private class TrackedOperation {
        val completed = AtomicBoolean(false)
        lateinit var cancellation: OperationCancellationHandle
        fun install(cancellation: OperationCancellationHandle) { this.cancellation = cancellation }
    }

    private class RelayCompletion(
        private val commandName: String,
        private val completion: CommandCompletion,
    ) : FlightActionCompletion {
        private val finished = AtomicBoolean(false)

        override fun complete(outcome: FlightActionTerminalOutcome) {
            if (!finished.compareAndSet(false, true)) return
            if (outcome == FlightActionTerminalOutcome.SUCCEEDED) {
                completion.succeed(successDetail(commandName))
            } else {
                completion.reject("Flight command failed")
            }
        }

        fun reject(detail: String) {
            if (finished.compareAndSet(false, true)) completion.reject(detail)
        }

        private fun successDetail(commandName: String): String = when (commandName) {
            "flight.takeoff" -> "Takeoff command completed"
            "flight.land" -> "Landing command completed"
            "flight.return-home" -> "Return-home command completed"
            else -> "Flight command completed"
        }
    }

    private fun FlightDjiTerminalOutcome.toActionOutcome(): FlightActionTerminalOutcome = when (this) {
        FlightDjiTerminalOutcome.SUCCEEDED -> FlightActionTerminalOutcome.SUCCEEDED
        FlightDjiTerminalOutcome.FAILED -> FlightActionTerminalOutcome.FAILED
        FlightDjiTerminalOutcome.TIMED_OUT -> FlightActionTerminalOutcome.TIMED_OUT
        FlightDjiTerminalOutcome.CANCELLED -> FlightActionTerminalOutcome.CANCELLED
    }

    companion object {
        fun create(dependencies: FlightControlDependencies): FlightControl = FlightControl(dependencies)
    }
}
