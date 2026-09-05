package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import java.util.concurrent.atomic.AtomicBoolean

/** Observes command lifecycles without changing their results or DJI behavior. */
internal class CommandDiagnosticRecorder(
    private val journal: DiagnosticJournal,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun wrap(handler: CommandHandler): CommandHandler = CommandHandler { command, completion ->
        val startedAt = elapsedMillis()
        writeRequested(command)
        val observedCompletion = ObservedCompletion(command, completion, startedAt)
        try {
            handler.handle(command, observedCompletion)
        } catch (_: Throwable) {
            observedCompletion.handlerFailed()
        }
    }

    private fun writeRequested(command: CommandFrame) {
        if (command.name == "telemetry.read") return
        recordSafely {
            journal.record(
                DiagnosticLevel.INFO,
                commandModule(command.name),
                commandEventCode(command.name, "REQUESTED"),
                command.id,
                "${command.name} phase=requested",
            )
        }
    }

    private fun writeTerminal(
        command: CommandFrame,
        succeeded: Boolean,
        detail: String,
        result: JsonObject?,
        startedAt: Long,
    ) {
        if (succeeded && command.name == "telemetry.read") return
        recordSafely {
            journal.record(
                if (succeeded) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
                commandModule(command.name),
                commandEventCode(command.name, if (succeeded) "OK" else "REJECTED"),
                command.id,
                buildString {
                    append(command.name)
                    append(" phase=terminal terminal=")
                    append(if (succeeded) "succeeded" else "rejected")
                    append(" elapsedMs=")
                    append((elapsedMillis() - startedAt).coerceAtLeast(0L))
                    append(" completionDetail=")
                    append(sanitizeDiagnosticValue(detail))
                    append(resultDiagnosticDetail(result))
                },
            )
        }
    }

    private fun writeHandlerFailure(command: CommandFrame, startedAt: Long) {
        recordSafely {
            journal.record(
                DiagnosticLevel.ERROR,
                commandModule(command.name),
                commandEventCode(command.name, "HANDLER_FAILURE"),
                command.id,
                "${command.name} phase=terminal terminal=handler-failure elapsedMs=${(elapsedMillis() - startedAt).coerceAtLeast(0L)}",
            )
        }
    }

    private fun writeDuplicate(command: CommandFrame, attemptedTerminal: String) {
        recordSafely {
            journal.record(
                DiagnosticLevel.WARN,
                commandModule(command.name),
                commandEventCode(command.name, "DUPLICATE_RESULT"),
                command.id,
                "${command.name} phase=terminal terminal=ignored ignored=duplicate-terminal-callback attempted=$attemptedTerminal",
            )
        }
    }

    private fun recordSafely(write: () -> Unit) {
        runCatching(write)
    }

    private fun resultDiagnosticDetail(result: JsonObject?): String {
        if (result == null) return " result=absent"
        val domain = result.string("domain")
        val outcome = result.string("outcome")
        return buildString {
            append(" result=present")
            domain?.let { append(" resultDomain=").append(sanitizeDiagnosticValue(it)) }
            outcome?.let { append(" resultOutcome=").append(sanitizeDiagnosticValue(it)) }
            if (outcome == "ACTION_REJECTED") {
                result.string("errorCode")?.let { append(" djiErrorCode=").append(sanitizeDiagnosticValue(it)) }
                result.string("errorDescription")?.let {
                    append(" djiErrorDescription=").append(sanitizeDiagnosticValue(it))
                }
            }
        }
    }

    private fun sanitizeDiagnosticValue(value: String): String = STREAM_ENDPOINT.replace(value, "[REDACTED_STREAM_ENDPOINT]")

    private inner class ObservedCompletion(
        private val command: CommandFrame,
        private val delegate: CommandCompletion,
        private val startedAt: Long,
    ) : CommandCompletion {
        private val completed = AtomicBoolean(false)

        override fun succeed(detail: String) = complete(succeeded = true, detail, null)

        override fun succeed(detail: String, result: JsonObject?) = complete(succeeded = true, detail, result)

        override fun reject(detail: String) = complete(succeeded = false, detail, null)

        override fun reject(detail: String, result: JsonObject?) = complete(succeeded = false, detail, result)

        fun handlerFailed() {
            if (!completed.compareAndSet(false, true)) {
                writeDuplicate(command, "handler-failure")
                return
            }
            writeHandlerFailure(command, startedAt)
            delegate.reject("Command failed")
        }

        private fun complete(succeeded: Boolean, detail: String, result: JsonObject?) {
            if (!completed.compareAndSet(false, true)) {
                writeDuplicate(command, if (succeeded) "succeeded" else "rejected")
                return
            }
            writeTerminal(command, succeeded, detail, result, startedAt)
            if (succeeded) delegate.succeed(detail, result) else delegate.reject(detail, result)
        }
    }

    private companion object {
        val STREAM_ENDPOINT = Regex("(?i)\\b(?:rtmp|rtmps|whip)://[^\\s,;]+")
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonString)?.value

private fun commandModule(name: String): String = when {
    name.startsWith("wayline.") -> "wayline-mission"
    name.startsWith("live-stream-webrtc.") -> "live-stream-webrtc"
    name.startsWith("live-stream.") -> "live-stream"
    name.startsWith("flight.") -> "flight-control"
    name.startsWith("device.settings.") -> "device-settings"
    name.startsWith("pairing.") -> "device-connection"
    name.startsWith("telemetry.") -> "telemetry"
    else -> "relay-gateway"
}

private fun commandEventCode(name: String, suffix: String): String =
    "${name.uppercase().replace('.', '_').replace('-', '_')}_$suffix"
