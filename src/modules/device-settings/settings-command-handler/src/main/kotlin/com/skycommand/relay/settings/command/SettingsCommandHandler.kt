package com.skycommand.relay.settings.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import java.util.concurrent.atomic.AtomicBoolean

enum class SettingsDomain { CAMERA, TRANSMISSION }

data class CameraSettings(
    val autoExposureLockEnabled: Boolean,
    val focusMode: String,
    val cameraIndex: String,
)

data class TransmissionSettings(
    val frequencyBand: String,
    val channelSelectionMode: String,
    val bandwidth: String,
    val dynamicDataRateMbps: Double?,
)

data class CameraSettingsPatch(
    val autoExposureLockEnabled: Boolean? = null,
    val focusMode: String? = null,
)

data class TransmissionSettingsPatch(
    val frequencyBand: String? = null,
    val channelSelectionMode: String? = null,
    val bandwidth: String? = null,
)

sealed interface SettingsRequest {
    data class Read(val domain: SettingsDomain) : SettingsRequest
    data class WriteCamera(val patch: CameraSettingsPatch) : SettingsRequest
    data class WriteTransmission(val patch: TransmissionSettingsPatch) : SettingsRequest
}

sealed interface SettingsSnapshot {
    val domain: SettingsDomain
    data class Camera(val value: CameraSettings) : SettingsSnapshot { override val domain = SettingsDomain.CAMERA }
    data class Transmission(val value: TransmissionSettings) : SettingsSnapshot { override val domain = SettingsDomain.TRANSMISSION }
}

fun interface SettingsActionCompletion {
    fun complete(outcome: SettingsActionTerminalOutcome)
}

sealed interface SettingsActionTerminalOutcome {
    data class Succeeded(val snapshot: SettingsSnapshot) : SettingsActionTerminalOutcome
    data object Failed : SettingsActionTerminalOutcome
    data object TimedOut : SettingsActionTerminalOutcome
    data object Cancelled : SettingsActionTerminalOutcome
}

sealed interface SettingsActionResult {
    data object Accepted : SettingsActionResult
    data object Rejected : SettingsActionResult
}

fun interface SettingsCommandActions {
    fun execute(request: SettingsRequest, completion: SettingsActionCompletion): SettingsActionResult
}

sealed interface SettingsCommandResult {
    data object Accepted : SettingsCommandResult
    data class Rejected(val reason: SettingsCommandRejection) : SettingsCommandResult
}

enum class SettingsCommandRejection {
    UNKNOWN_COMMAND,
    INVALID_FIELDS,
    INVALID_VALUE,
    OPERATION_REJECTED,
}

class SettingsCommandHandler private constructor(
    private val actions: SettingsCommandActions,
) {
    fun handle(command: CommandFrame): SettingsCommandResult = handle(command, SettingsActionCompletion { })

    fun handle(command: CommandFrame, completion: SettingsActionCompletion): SettingsCommandResult {
        val request = parse(command) ?: return SettingsCommandResult.Rejected(rejectionFor(command))
        return when (actions.execute(request, OnceCompletion(completion))) {
            SettingsActionResult.Accepted -> SettingsCommandResult.Accepted
            SettingsActionResult.Rejected -> SettingsCommandResult.Rejected(SettingsCommandRejection.OPERATION_REJECTED)
        }
    }

    private fun parse(command: CommandFrame): SettingsRequest? = when (command.name) {
        "device.settings.camera.read" -> command.fields.takeIf { it.fields.isEmpty() }?.let { SettingsRequest.Read(SettingsDomain.CAMERA) }
        "device.settings.transmission.read" -> command.fields.takeIf { it.fields.isEmpty() }?.let { SettingsRequest.Read(SettingsDomain.TRANSMISSION) }
        "device.settings.camera.write" -> parseCameraPatch(command.fields)?.let(SettingsRequest::WriteCamera)
        "device.settings.transmission.write" -> parseTransmissionPatch(command.fields)?.let(SettingsRequest::WriteTransmission)
        else -> null
    }

    private fun rejectionFor(command: CommandFrame): SettingsCommandRejection = when (command.name) {
        "device.settings.camera.read", "device.settings.transmission.read" -> SettingsCommandRejection.INVALID_FIELDS
        "device.settings.camera.write", "device.settings.transmission.write" -> {
            if (containsOnlyAllowedFields(command)) SettingsCommandRejection.INVALID_VALUE else SettingsCommandRejection.INVALID_FIELDS
        }
        else -> SettingsCommandRejection.UNKNOWN_COMMAND
    }

    private fun parseCameraPatch(fields: JsonObject): CameraSettingsPatch? {
        if (fields.fields.isEmpty() || fields.fields.keys.any { it !in cameraWritableFields }) return null
        val lock = fields["autoExposureLockEnabled"]
        val focus = fields["focusMode"]
        if (lock != null && lock !is JsonBoolean) return null
        if (focus != null && (focus !is JsonString || !isToken(focus.value))) return null
        return CameraSettingsPatch((lock as? JsonBoolean)?.value, (focus as? JsonString)?.value)
    }

    private fun parseTransmissionPatch(fields: JsonObject): TransmissionSettingsPatch? {
        if (fields.fields.isEmpty() || fields.fields.keys.any { it !in transmissionWritableFields }) return null
        val values = transmissionWritableFields.associateWith { field -> (fields[field] as? JsonString)?.value }
        if (fields.fields.keys.any { values[it]?.let(::isToken) != true }) return null
        return TransmissionSettingsPatch(values["frequencyBand"], values["channelSelectionMode"], values["bandwidth"])
    }

    private fun containsOnlyAllowedFields(command: CommandFrame): Boolean {
        val allowed = when (command.name) {
            "device.settings.camera.write" -> cameraWritableFields
            "device.settings.transmission.write" -> transmissionWritableFields
            else -> emptySet()
        }
        return command.fields.fields.isNotEmpty() && command.fields.fields.keys.all { it in allowed }
    }

    private class OnceCompletion(private val delegate: SettingsActionCompletion) : SettingsActionCompletion {
        private val completed = AtomicBoolean(false)
        override fun complete(outcome: SettingsActionTerminalOutcome) {
            if (completed.compareAndSet(false, true)) delegate.complete(outcome)
        }
    }

    companion object {
        private val cameraWritableFields = setOf("autoExposureLockEnabled", "focusMode")
        private val transmissionWritableFields = setOf("frequencyBand", "channelSelectionMode", "bandwidth")
        private val tokenPattern = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun create(actions: SettingsCommandActions): SettingsCommandHandler = SettingsCommandHandler(actions)
        fun isToken(value: String): Boolean = value.matches(tokenPattern)
    }
}
