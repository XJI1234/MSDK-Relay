package com.skycommand.relay.wayline.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject

interface WaylineCommandActions {
    fun upload(completion: WaylineActionCompletion): WaylineActionResult
    fun start(completion: WaylineActionCompletion): WaylineActionResult
    fun pause(completion: WaylineActionCompletion): WaylineActionResult
    fun resume(completion: WaylineActionCompletion): WaylineActionResult
    fun stop(completion: WaylineActionCompletion): WaylineActionResult
}

fun interface WaylineActionCompletion {
    fun complete(outcome: WaylineActionTerminalOutcome)
}

enum class WaylineActionTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface WaylineActionResult {
    data object Accepted : WaylineActionResult
    data object Rejected : WaylineActionResult
}

sealed interface WaylineCommandResult {
    data class Succeeded(val detail: String) : WaylineCommandResult
    data class Accepted(val detail: String) : WaylineCommandResult
    data class Rejected(val reason: WaylineCommandRejection) : WaylineCommandResult
}

enum class WaylineCommandRejection {
    UNKNOWN_COMMAND,
    INVALID_FIELDS,
    CONFIRMATION_REQUIRED,
    CAPABILITY_REJECTED,
}

class WaylineCommandHandler private constructor(
    private val actions: WaylineCommandActions,
) {
    fun handle(command: CommandFrame): WaylineCommandResult = handle(command, WaylineActionCompletion { })

    fun handle(command: CommandFrame, completion: WaylineActionCompletion): WaylineCommandResult = when (command.name) {
        "wayline.upload" -> delegate(command.fields) { actions.upload(completion) }
        "wayline.start" -> delegate(command.fields) { actions.start(completion) }
        "wayline.pause" -> delegate(command.fields) { actions.pause(completion) }
        "wayline.resume" -> delegate(command.fields) { actions.resume(completion) }
        "wayline.stop" -> delegate(command.fields) { actions.stop(completion) }
        else -> WaylineCommandResult.Rejected(WaylineCommandRejection.UNKNOWN_COMMAND)
    }

    private fun delegate(fields: JsonObject, action: () -> WaylineActionResult): WaylineCommandResult {
        if (fields.fields.isEmpty()) {
            return WaylineCommandResult.Rejected(WaylineCommandRejection.CONFIRMATION_REQUIRED)
        }
        if (fields.fields.keys != confirmationFields) {
            return WaylineCommandResult.Rejected(WaylineCommandRejection.INVALID_FIELDS)
        }
        if (fields["confirm"] != JsonBoolean(true)) {
            return WaylineCommandResult.Rejected(WaylineCommandRejection.CONFIRMATION_REQUIRED)
        }
        return when (action()) {
            WaylineActionResult.Accepted -> WaylineCommandResult.Accepted("Operation accepted")
            WaylineActionResult.Rejected -> WaylineCommandResult.Rejected(WaylineCommandRejection.CAPABILITY_REJECTED)
        }
    }

    companion object {
        private val confirmationFields = setOf("confirm")

        fun create(actions: WaylineCommandActions): WaylineCommandHandler = WaylineCommandHandler(actions)
    }
}
