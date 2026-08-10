package com.skycommand.relay.stream.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.stream.config.StreamConfigValidator
import com.skycommand.relay.stream.config.StreamValidationResult
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import java.util.concurrent.atomic.AtomicBoolean

interface StreamCommandActions {
    fun start(config: ValidatedStreamConfig, completion: StreamActionCompletion): StreamActionResult

    fun stop(completion: StreamActionCompletion): StreamActionResult
}

fun interface StreamActionCompletion {
    fun complete(outcome: StreamActionTerminalOutcome)
}

enum class StreamActionTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface StreamActionResult {
    data object Accepted : StreamActionResult

    data object Rejected : StreamActionResult
}

sealed interface StreamCommandResult {
    data object Succeeded : StreamCommandResult

    data object Accepted : StreamCommandResult

    data class Rejected(val reason: StreamCommandRejection) : StreamCommandResult
}

enum class StreamCommandRejection {
    UNKNOWN_COMMAND,
    INVALID_FIELDS,
    INVALID_CONFIGURATION,
    CAPABILITY_REJECTED,
}

class StreamCommandHandler private constructor(
    private val actions: StreamCommandActions,
) {
    fun handle(command: CommandFrame): StreamCommandResult = handle(command, StreamActionCompletion { })

    fun handle(command: CommandFrame, completion: StreamActionCompletion): StreamCommandResult = when (command.name) {
        "live-stream.start" -> start(command.fields, completion)
        "live-stream.stop" -> stop(command.fields, completion)
        else -> StreamCommandResult.Rejected(StreamCommandRejection.UNKNOWN_COMMAND)
    }

    private fun start(fields: JsonObject, completion: StreamActionCompletion): StreamCommandResult {
        if (fields.fields.keys != setOf("rtmpUrl")) return StreamCommandResult.Rejected(StreamCommandRejection.INVALID_FIELDS)
        val url = (fields["rtmpUrl"] as? JsonString)?.value
            ?: return StreamCommandResult.Rejected(StreamCommandRejection.INVALID_FIELDS)
        val config = (StreamConfigValidator.validate(url) as? StreamValidationResult.Valid)?.config
            ?: return StreamCommandResult.Rejected(StreamCommandRejection.INVALID_CONFIGURATION)
        return delegate(completion) { actions.start(config, it) }
    }

    private fun stop(fields: JsonObject, completion: StreamActionCompletion): StreamCommandResult {
        if (fields.fields.isNotEmpty()) return StreamCommandResult.Rejected(StreamCommandRejection.INVALID_FIELDS)
        return delegate(completion) { actions.stop(it) }
    }

    private fun delegate(
        completion: StreamActionCompletion,
        action: (StreamActionCompletion) -> StreamActionResult,
    ): StreamCommandResult {
        val once = OnceCompletion(completion)
        return when (action(once)) {
            StreamActionResult.Accepted -> StreamCommandResult.Accepted
            StreamActionResult.Rejected -> StreamCommandResult.Rejected(StreamCommandRejection.CAPABILITY_REJECTED)
        }
    }

    private class OnceCompletion(private val delegate: StreamActionCompletion) : StreamActionCompletion {
        private val completed = AtomicBoolean(false)

        override fun complete(outcome: StreamActionTerminalOutcome) {
            if (completed.compareAndSet(false, true)) delegate.complete(outcome)
        }
    }

    companion object {
        fun create(actions: StreamCommandActions): StreamCommandHandler = StreamCommandHandler(actions)
    }
}
