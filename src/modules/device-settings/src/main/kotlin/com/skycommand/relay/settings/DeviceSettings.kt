package com.skycommand.relay.settings

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonNull
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.settings.command.*
import com.skycommand.relay.settings.executor.*
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceSettingsDependencies(val djiPort: DjiSettingsPort, val operationCoordinator: DjiOperationCoordinator, val timeoutMillis: Long = 30_000)

class DeviceSettings private constructor(private val dependencies: DeviceSettingsDependencies) {
    private val active = mutableSetOf<OperationCancellationHandle>()
    private val lock = Any()
    private var requestInFlight = false
    private val executor = SettingsExecutor.create(dependencies.djiPort, dependencies.operationCoordinator, dependencies.timeoutMillis)
    private val commands = SettingsCommandHandler.create(Actions())
    fun commandHandler(): CommandHandler = CommandHandler(::handle)
    fun markDeviceUnavailable() {
        val pending = synchronized(lock) {
            requestInFlight = false
            active.toList().also { active.clear() }
        }
        pending.forEach { it.cancel() }
    }
    fun close() { markDeviceUnavailable(); runCatching { dependencies.djiPort.close() } }

    private fun handle(command: CommandFrame, completion: CommandCompletion) {
        when (val result = commands.handle(command, object : SettingsActionCompletion {
            override fun complete(outcome: SettingsActionTerminalOutcome) = complete(outcome, null)

            override fun complete(outcome: SettingsActionTerminalOutcome, failure: SettingsDjiFailure?) {
                when (outcome) {
                    is SettingsActionTerminalOutcome.Succeeded -> completion.succeed("Settings confirmed", outcome.snapshot.toResult())
                    SettingsActionTerminalOutcome.Failed -> if (failure == null) {
                        completion.reject("Settings operation failed before DJI reported a result", terminalResult("INVOCATION_FAILED"))
                    } else {
                        completion.reject("Settings action was rejected", terminalResult("ACTION_REJECTED", failure))
                    }
                    SettingsActionTerminalOutcome.TimedOut,
                    SettingsActionTerminalOutcome.Cancelled -> completion.reject(
                        "Settings operation result was not confirmed",
                        terminalResult("RESULT_UNCONFIRMED"),
                    )
                }
            }
        })) {
            SettingsCommandResult.Accepted -> Unit
            is SettingsCommandResult.Rejected -> completion.reject(when (result.reason) {
                SettingsCommandRejection.UNKNOWN_COMMAND -> "Settings command is not available"
                SettingsCommandRejection.INVALID_FIELDS -> "Settings command fields are invalid"
                SettingsCommandRejection.INVALID_VALUE -> "Settings command values are invalid"
                SettingsCommandRejection.OPERATION_REJECTED -> "Settings operation was rejected"
            })
        }
    }
    private inner class Actions : SettingsCommandActions {
        override fun execute(request: SettingsRequest, completion: SettingsActionCompletion): SettingsActionResult {
            if (!synchronized(lock) {
                    if (requestInFlight) false else {
                        requestInFlight = true
                        true
                    }
                }) {
                return SettingsActionResult.Rejected
            }
            var cancellation: OperationCancellationHandle? = null
            val completed = AtomicBoolean(false)
            val result = executor.execute(request, object : SettingsExecutionListener {
                override fun onCompleted(outcome: SettingsExecutionOutcome) = onCompleted(outcome, null)

                override fun onCompleted(outcome: SettingsExecutionOutcome, failure: SettingsDjiFailure?) {
                    completed.set(true)
                    synchronized(lock) {
                        requestInFlight = false
                        cancellation?.let(active::remove)
                    }
                    completion.complete(when (outcome) {
                        is SettingsExecutionOutcome.Succeeded -> SettingsActionTerminalOutcome.Succeeded(outcome.snapshot)
                        SettingsExecutionOutcome.Failed -> SettingsActionTerminalOutcome.Failed
                        SettingsExecutionOutcome.TimedOut -> SettingsActionTerminalOutcome.TimedOut
                        SettingsExecutionOutcome.Cancelled -> SettingsActionTerminalOutcome.Cancelled
                    }, failure)
                }
            })
            return when (result) {
                is SettingsSubmissionResult.Accepted -> {
                    cancellation = result.cancellation
                    synchronized(lock) { if (!completed.get()) active += result.cancellation }
                    SettingsActionResult.Accepted
                }
                SettingsSubmissionResult.Rejected -> {
                    synchronized(lock) { requestInFlight = false }
                    SettingsActionResult.Rejected
                }
            }
        }
    }
    private fun SettingsSnapshot.toResult(): JsonObject = when (this) {
        is SettingsSnapshot.Camera -> JsonObject(mapOf(
            "domain" to JsonString("camera"),
            "settings" to JsonObject(mapOf(
                "autoExposureLockEnabled" to JsonBoolean(value.autoExposureLockEnabled),
                "focusMode" to JsonString(value.focusMode),
                "cameraIndex" to JsonString(value.cameraIndex),
            )),
        ))
        is SettingsSnapshot.Transmission -> JsonObject(mapOf(
            "domain" to JsonString("transmission"),
            "settings" to JsonObject(mapOf(
                "frequencyBand" to JsonString(value.frequencyBand),
                "channelSelectionMode" to JsonString(value.channelSelectionMode),
                "bandwidth" to JsonString(value.bandwidth),
                "dynamicDataRateMbps" to (value.dynamicDataRateMbps?.let { JsonNumber(it.toString()) } ?: JsonNull),
            )),
        ))
    }

    private fun terminalResult(outcome: String, failure: SettingsDjiFailure? = null): JsonObject =
        JsonObject(
            buildMap {
                put("domain", JsonString("settings"))
                put("outcome", JsonString(outcome))
                failure?.let {
                    put("errorCode", JsonString(it.errorCode))
                    put("errorDescription", JsonString(it.errorDescription))
                }
            },
        )

    companion object { fun create(dependencies: DeviceSettingsDependencies) = DeviceSettings(dependencies) }
}
