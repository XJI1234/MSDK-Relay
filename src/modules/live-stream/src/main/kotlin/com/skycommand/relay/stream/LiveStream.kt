package com.skycommand.relay.stream

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.stream.command.StreamActionCompletion
import com.skycommand.relay.stream.command.StreamActionResult
import com.skycommand.relay.stream.command.StreamActionTerminalOutcome
import com.skycommand.relay.stream.command.StreamCommandActions
import com.skycommand.relay.stream.command.StreamCommandHandler
import com.skycommand.relay.stream.command.StreamCommandRejection
import com.skycommand.relay.stream.command.StreamCommandResult
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.dji.DjiStreamAdapter
import com.skycommand.relay.stream.dji.DjiStreamPort
import com.skycommand.relay.stream.dji.DjiStreamStartResult
import com.skycommand.relay.stream.dji.DjiStreamStopResult
import com.skycommand.relay.stream.dji.StreamDjiTerminalListener
import com.skycommand.relay.stream.dji.StreamDjiTerminalOutcome
import com.skycommand.relay.stream.state.Registration
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.stream.state.StreamStateDiagnosticSink
import com.skycommand.relay.stream.state.StreamStateListener
import com.skycommand.relay.stream.state.StreamStateStore
import java.util.concurrent.atomic.AtomicBoolean

data class LiveStreamDependencies(
    val djiPort: DjiStreamPort,
    val operationCoordinator: DjiOperationCoordinator,
    val timeoutMillis: Long = 30_000,
    val diagnosticSink: StreamStateDiagnosticSink = StreamStateDiagnosticSink { },
)

class LiveStream private constructor(dependencies: LiveStreamDependencies) {
    private val state = StreamStateStore.create(dependencies.diagnosticSink)
    private val adapter = DjiStreamAdapter.create(
        stateStore = state,
        djiPort = dependencies.djiPort,
        coordinator = dependencies.operationCoordinator,
        timeoutMillis = dependencies.timeoutMillis,
    )
    private val commands = StreamCommandHandler.create(Actions())

    fun commandHandler(): CommandHandler = CommandHandler(::handleCommand)

    fun snapshot(): StreamSnapshot = state.snapshot()

    fun onChanged(listener: StreamStateListener): Registration = state.onChanged(listener)

    private fun handleCommand(command: CommandFrame, completion: CommandCompletion) {
        val terminal = RelayCompletion(command.name, completion)
        when (val result = commands.handle(command, terminal)) {
            StreamCommandResult.Accepted -> Unit
            StreamCommandResult.Succeeded -> terminal.succeed("Stream operation completed")
            is StreamCommandResult.Rejected -> terminal.reject(detailFor(result.reason))
        }
    }

    private fun detailFor(reason: StreamCommandRejection): String = when (reason) {
        StreamCommandRejection.UNKNOWN_COMMAND -> "Stream command is not available"
        StreamCommandRejection.INVALID_FIELDS -> "Stream command fields are invalid"
        StreamCommandRejection.INVALID_CONFIGURATION -> "RTMP configuration is invalid"
        StreamCommandRejection.CAPABILITY_REJECTED -> "Stream operation was rejected"
    }

    private inner class Actions : StreamCommandActions {
        override fun start(config: ValidatedStreamConfig, completion: StreamActionCompletion): StreamActionResult =
            adapter.start(config, StreamDjiTerminalListener { completion.complete(it.toActionOutcome()) }).toActionResult()

        override fun stop(completion: StreamActionCompletion): StreamActionResult =
            adapter.stop(StreamDjiTerminalListener { completion.complete(it.toActionOutcome()) }).toActionResult()
    }

    private class RelayCompletion(
        private val commandName: String,
        private val completion: CommandCompletion,
    ) : StreamActionCompletion {
        private val finished = AtomicBoolean(false)

        override fun complete(outcome: StreamActionTerminalOutcome) {
            if (!finished.compareAndSet(false, true)) return
            if (outcome == StreamActionTerminalOutcome.SUCCEEDED) {
                completion.succeed(successDetail(commandName))
            } else {
                completion.reject("Stream operation failed")
            }
        }

        fun succeed(detail: String) {
            if (finished.compareAndSet(false, true)) completion.succeed(detail)
        }

        fun reject(detail: String) {
            if (finished.compareAndSet(false, true)) completion.reject(detail)
        }

        private fun successDetail(commandName: String): String = when (commandName) {
            "live-stream.start" -> "Stream started"
            "live-stream.stop" -> "Stream stopped"
            else -> "Stream operation completed"
        }
    }

    private fun DjiStreamStartResult.toActionResult(): StreamActionResult = when (this) {
        is DjiStreamStartResult.Accepted -> StreamActionResult.Accepted
        is DjiStreamStartResult.Rejected -> StreamActionResult.Rejected
    }

    private fun DjiStreamStopResult.toActionResult(): StreamActionResult = when (this) {
        is DjiStreamStopResult.Accepted -> StreamActionResult.Accepted
        is DjiStreamStopResult.Rejected -> StreamActionResult.Rejected
    }

    private fun StreamDjiTerminalOutcome.toActionOutcome(): StreamActionTerminalOutcome = when (this) {
        StreamDjiTerminalOutcome.SUCCEEDED -> StreamActionTerminalOutcome.SUCCEEDED
        StreamDjiTerminalOutcome.FAILED -> StreamActionTerminalOutcome.FAILED
        StreamDjiTerminalOutcome.TIMED_OUT -> StreamActionTerminalOutcome.TIMED_OUT
        StreamDjiTerminalOutcome.CANCELLED -> StreamActionTerminalOutcome.CANCELLED
    }

    companion object {
        fun create(dependencies: LiveStreamDependencies): LiveStream = LiveStream(dependencies)
    }
}
