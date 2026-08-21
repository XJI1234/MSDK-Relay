package com.skycommand.relay.stream.whip.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import com.skycommand.relay.stream.whip.config.WhipStreamConfigValidator
import com.skycommand.relay.stream.whip.config.WhipConfigValidationResult
import java.util.concurrent.atomic.AtomicBoolean

interface WhipCommandActions {
    fun start(config: ValidatedWhipStreamConfig, completion: WhipActionCompletion): WhipActionResult

    fun stop(completion: WhipActionCompletion): WhipActionResult
}

fun interface WhipActionCompletion {
    fun complete(outcome: WhipActionTerminalOutcome)
}

enum class WhipActionTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface WhipActionResult {
    data object Accepted : WhipActionResult

    data object Rejected : WhipActionResult
}

sealed interface WhipCommandResult {
    data object Accepted : WhipCommandResult

    data class Rejected(val reason: WhipCommandRejection) : WhipCommandResult
}

enum class WhipCommandRejection {
    UNKNOWN_COMMAND,
    INVALID_FIELDS,
    INVALID_CONFIGURATION,
    CAPABILITY_REJECTED,
}

class WhipCommandHandler private constructor(
    private val actions: WhipCommandActions,
) {
    fun handle(command: CommandFrame): WhipCommandResult = handle(command, WhipActionCompletion { })

    fun handle(command: CommandFrame, completion: WhipActionCompletion): WhipCommandResult = when (command.name) {
        "live-stream-webrtc.start" -> start(command.fields, completion)
        "live-stream-webrtc.stop" -> stop(command.fields, completion)
        else -> WhipCommandResult.Rejected(WhipCommandRejection.UNKNOWN_COMMAND)
    }

    private fun start(fields: JsonObject, completion: WhipActionCompletion): WhipCommandResult {
        if (fields.fields.keys != setOf("whipUrl")) return rejected(WhipCommandRejection.INVALID_FIELDS)
        val url = (fields["whipUrl"] as? JsonString)?.value
            ?: return rejected(WhipCommandRejection.INVALID_FIELDS)
        val config = when (val result = WhipStreamConfigValidator.validate(url)) {
            is WhipConfigValidationResult.Valid -> result.config
            is WhipConfigValidationResult.Invalid -> return rejected(WhipCommandRejection.INVALID_CONFIGURATION)
        }
        return delegate(completion) { actions.start(config, it) }
    }

    private fun stop(fields: JsonObject, completion: WhipActionCompletion): WhipCommandResult {
        if (fields.fields.isNotEmpty()) return rejected(WhipCommandRejection.INVALID_FIELDS)
        return delegate(completion) { actions.stop(it) }
    }

    private fun delegate(
        completion: WhipActionCompletion,
        action: (WhipActionCompletion) -> WhipActionResult,
    ): WhipCommandResult {
        val gated = GatedCompletion(completion)
        val result = try {
            action(gated)
        } catch (_: Throwable) {
            gated.reject()
            return rejected(WhipCommandRejection.CAPABILITY_REJECTED)
        }
        return when (result) {
            WhipActionResult.Accepted -> {
                gated.accept()
                WhipCommandResult.Accepted
            }

            WhipActionResult.Rejected -> {
                gated.reject()
                rejected(WhipCommandRejection.CAPABILITY_REJECTED)
            }
        }
    }

    private fun rejected(reason: WhipCommandRejection): WhipCommandResult =
        WhipCommandResult.Rejected(reason)

    private class GatedCompletion(
        private val delegate: WhipActionCompletion,
    ) : WhipActionCompletion {
        private val lock = Any()
        private var accepted = false
        private var rejected = false
        private var delivered = false
        private var pending: WhipActionTerminalOutcome? = null

        override fun complete(outcome: WhipActionTerminalOutcome) {
            val deliver = synchronized(lock) {
                when {
                    rejected || delivered -> null
                    !accepted -> {
                        if (pending == null) pending = outcome
                        null
                    }

                    else -> {
                        delivered = true
                        outcome
                    }
                }
            }
            deliver?.let { runCatching { delegate.complete(it) } }
        }

        fun accept() {
            val deliver = synchronized(lock) {
                if (rejected || accepted) return@synchronized null
                accepted = true
                pending?.also {
                    pending = null
                    delivered = true
                }
            }
            deliver?.let { runCatching { delegate.complete(it) } }
        }

        fun reject() {
            synchronized(lock) {
                rejected = true
                pending = null
            }
        }
    }

    companion object {
        fun create(actions: WhipCommandActions): WhipCommandHandler = WhipCommandHandler(actions)
    }
}
