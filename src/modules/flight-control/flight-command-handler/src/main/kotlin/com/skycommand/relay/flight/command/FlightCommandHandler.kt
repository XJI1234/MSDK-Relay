package com.skycommand.relay.flight.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

enum class FlightAction {
    TAKEOFF,
    LAND,
    CONFIRM_LANDING,
    RETURN_HOME,
    STOP_TAKEOFF,
    STOP_AUTO_LANDING,
}

fun interface FlightActionCompletion {
    fun complete(outcome: FlightActionTerminalOutcome)
}

enum class FlightActionTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface FlightActionResult {
    data object Accepted : FlightActionResult
    data object Rejected : FlightActionResult
}

fun interface FlightCommandActions {
    fun execute(action: FlightAction, completion: FlightActionCompletion): FlightActionResult
}

sealed interface FlightCommandResult {
    data object Accepted : FlightCommandResult
    data class Rejected(val reason: FlightCommandRejection) : FlightCommandResult
}

enum class FlightCommandRejection {
    UNKNOWN_COMMAND,
    INVALID_FIELDS,
    CONFIRMATION_REQUIRED,
    OPERATION_REJECTED,
}

class FlightCommandHandler private constructor(
    private val actions: FlightCommandActions,
) {
    fun handle(command: CommandFrame): FlightCommandResult = handle(command, FlightActionCompletion { })

    fun handle(command: CommandFrame, completion: FlightActionCompletion): FlightCommandResult {
        val action = actionFor(command.name)
            ?: return FlightCommandResult.Rejected(FlightCommandRejection.UNKNOWN_COMMAND)
        return when (val validation = validate(command.fields)) {
            FlightCommandRejection.CONFIRMATION_REQUIRED -> FlightCommandResult.Rejected(validation)
            FlightCommandRejection.INVALID_FIELDS -> FlightCommandResult.Rejected(validation)
            null -> when (actions.execute(action, OnceCompletion(completion))) {
                FlightActionResult.Accepted -> FlightCommandResult.Accepted
                FlightActionResult.Rejected -> FlightCommandResult.Rejected(FlightCommandRejection.OPERATION_REJECTED)
            }
            else -> error("Unexpected flight command validation result")
        }
    }

    private fun actionFor(name: String): FlightAction? = when (name) {
        "flight.takeoff" -> FlightAction.TAKEOFF
        "flight.land" -> FlightAction.LAND
        "flight.confirm-landing" -> FlightAction.CONFIRM_LANDING
        "flight.return-home" -> FlightAction.RETURN_HOME
        "flight.stop-takeoff" -> FlightAction.STOP_TAKEOFF
        "flight.stop-auto-landing" -> FlightAction.STOP_AUTO_LANDING
        else -> null
    }

    private fun validate(fields: JsonObject): FlightCommandRejection? {
        if (fields["confirm"] == null) {
            return if (fields.fields.isEmpty()) {
                FlightCommandRejection.CONFIRMATION_REQUIRED
            } else {
                FlightCommandRejection.INVALID_FIELDS
            }
        }
        if (fields.fields.keys != setOf("confirm")) return FlightCommandRejection.INVALID_FIELDS
        return when (val confirm = fields["confirm"]) {
            is JsonBoolean -> if (confirm.value) null else FlightCommandRejection.CONFIRMATION_REQUIRED
            else -> FlightCommandRejection.INVALID_FIELDS
        }
    }

    private class OnceCompletion(private val delegate: FlightActionCompletion) : FlightActionCompletion {
        private val completed = AtomicBoolean(false)

        override fun complete(outcome: FlightActionTerminalOutcome) {
            if (completed.compareAndSet(false, true)) delegate.complete(outcome)
        }
    }

    companion object {
        fun create(actions: FlightCommandActions): FlightCommandHandler = FlightCommandHandler(actions)
    }
}
