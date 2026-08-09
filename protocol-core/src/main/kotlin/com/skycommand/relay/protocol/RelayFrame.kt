package com.skycommand.relay.protocol

import java.util.Collections

sealed interface JsonValue

data object JsonNull : JsonValue

data class JsonString(val value: String) : JsonValue

data class JsonNumber(val value: String) : JsonValue

data class JsonBoolean(val value: Boolean) : JsonValue

class JsonArray(values: List<JsonValue>) : JsonValue {
    val values: List<JsonValue> = Collections.unmodifiableList(values.toList())

    override fun equals(other: Any?): Boolean = other is JsonArray && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()
}

class JsonObject(fields: Map<String, JsonValue>) : JsonValue {
    val fields: Map<String, JsonValue> = Collections.unmodifiableMap(fields.toMap())

    operator fun get(name: String): JsonValue? = fields[name]

    override fun equals(other: Any?): Boolean = other is JsonObject && fields == other.fields

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = fields.toString()
}

sealed interface RelayFrame

data class HelloFrame(
    val deviceId: String,
    val protocolVersion: String = ProtocolLimits.protocolVersion,
) : RelayFrame

data class PairedFrame(
    val sessionId: String,
    val protocolVersion: String?,
) : RelayFrame

data class TelemetryFrame(
    val payload: JsonObject,
    val capabilities: JsonObject,
) : RelayFrame

data class CommandFrame(
    val id: String,
    val name: String,
    val fields: JsonObject,
) : RelayFrame

data class CommandResultFrame(
    val id: String,
    val ok: Boolean,
    val detail: String,
) : RelayFrame

data class MissionBeginFrame(
    val id: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
) : RelayFrame

class MissionChunkFrame(id: String, bytes: ByteArray) : RelayFrame {
    val id: String = id
    private val storedBytes: ByteArray = bytes.copyOf()

    val bytes: ByteArray
        get() = storedBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is MissionChunkFrame && id == other.id && storedBytes.contentEquals(other.storedBytes)

    override fun hashCode(): Int = 31 * id.hashCode() + storedBytes.contentHashCode()
}

data class MissionCompleteFrame(val id: String) : RelayFrame

data class MissionResultFrame(
    val id: String,
    val ok: Boolean,
    val detail: String,
) : RelayFrame

fun validate(frame: RelayFrame): ProtocolResult<RelayFrame> {
    val result = when (frame) {
        is HelloFrame -> validateHello(frame)
        is PairedFrame -> validatePaired(frame)
        is TelemetryFrame -> Accepted(Unit)
        is CommandFrame -> validateCommand(frame)
        is CommandResultFrame -> validateResult(frame.id, frame.detail)
        is MissionBeginFrame -> validateMissionBegin(frame)
        is MissionChunkFrame -> validateMissionChunk(frame)
        is MissionCompleteFrame -> validateId(frame.id, ProtocolErrorCode.INVALID_MESSAGE_ID, "Mission ID is invalid")
        is MissionResultFrame -> validateResult(frame.id, frame.detail)
    }
    @Suppress("UNCHECKED_CAST")
    return result as ProtocolResult<RelayFrame>
}

private fun validateHello(frame: HelloFrame): ProtocolResult<Unit> {
    return validateId(frame.deviceId, ProtocolErrorCode.INVALID_DEVICE_ID, "Device ID is invalid")
        .then { validateVersion(frame.protocolVersion) }
}

private fun validatePaired(frame: PairedFrame): ProtocolResult<Unit> {
    return validateId(frame.sessionId, ProtocolErrorCode.INVALID_SESSION_ID, "Session ID is invalid")
        .then { frame.protocolVersion?.let(::validateVersion) ?: Accepted(Unit) }
}

private fun validateCommand(frame: CommandFrame): ProtocolResult<Unit> {
    return validateId(frame.id, ProtocolErrorCode.INVALID_MESSAGE_ID, "Command ID is invalid")
        .then {
            if (frame.name.isBlank() || frame.name.codePointCount(0, frame.name.length) > ProtocolLimits.maxCommandNameCodePoints) {
                Rejected(ProtocolError(ProtocolErrorCode.INVALID_COMMAND_NAME, "Command name is invalid"))
            } else {
                Accepted(Unit)
            }
        }
}

private fun validateResult(id: String, detail: String): ProtocolResult<Unit> {
    return validateId(id, ProtocolErrorCode.INVALID_MESSAGE_ID, "Message ID is invalid")
        .then {
            if (detail.codePointCount(0, detail.length) > ProtocolLimits.maxResultDetailCodePoints || detail.any(Char::isISOControl)) {
                Rejected(ProtocolError(ProtocolErrorCode.INVALID_RESULT_DETAIL, "Result detail is invalid"))
            } else {
                Accepted(Unit)
            }
        }
}

private fun validateMissionBegin(frame: MissionBeginFrame): ProtocolResult<Unit> {
    return validateId(frame.id, ProtocolErrorCode.INVALID_MESSAGE_ID, "Mission ID is invalid")
        .then {
            if (!isSafeMissionFileName(frame.fileName)) {
                Rejected(ProtocolError(ProtocolErrorCode.INVALID_FILE_NAME, "Mission file name is invalid"))
            } else {
                Accepted(Unit)
            }
        }
        .then {
            if (frame.size !in 1..ProtocolLimits.maxMissionBytes) {
                Rejected(ProtocolError(ProtocolErrorCode.MISSION_TOO_LARGE, "Mission size is outside the allowed range"))
            } else {
                Accepted(Unit)
            }
        }
        .then {
            if (!frame.sha256.matches(Regex("[0-9a-f]{64}"))) {
                Rejected(ProtocolError(ProtocolErrorCode.INVALID_SHA256, "Mission SHA-256 is invalid"))
            } else {
                Accepted(Unit)
            }
        }
}

private fun validateMissionChunk(frame: MissionChunkFrame): ProtocolResult<Unit> {
    return validateId(frame.id, ProtocolErrorCode.INVALID_MESSAGE_ID, "Mission ID is invalid")
        .then {
            when {
                frame.bytes.isEmpty() -> Rejected(ProtocolError(ProtocolErrorCode.EMPTY_CHUNK, "Mission chunk is empty"))
                frame.bytes.size > ProtocolLimits.maxMissionChunkBytes -> Rejected(ProtocolError(ProtocolErrorCode.CHUNK_TOO_LARGE, "Mission chunk is too large"))
                else -> Accepted(Unit)
            }
        }
}

private fun validateId(value: String, code: ProtocolErrorCode, message: String): ProtocolResult<Unit> {
    return if (
        value.isBlank() ||
        value.codePointCount(0, value.length) > ProtocolLimits.maxIdCodePoints ||
        value.any(Char::isISOControl)
    ) {
        Rejected(ProtocolError(code, message))
    } else {
        Accepted(Unit)
    }
}

private fun validateVersion(version: String): ProtocolResult<Unit> {
    return if (version == ProtocolLimits.protocolVersion) {
        Accepted(Unit)
    } else {
        Rejected(ProtocolError(ProtocolErrorCode.INVALID_PROTOCOL_VERSION, "Protocol version is unsupported"))
    }
}

private fun isSafeMissionFileName(fileName: String): Boolean {
    return fileName.isNotBlank() &&
        fileName.codePointCount(0, fileName.length) <= ProtocolLimits.maxFileNameCodePoints &&
        fileName.lowercase().endsWith(".kmz") &&
        !fileName.contains("..") &&
        !fileName.contains('/') &&
        !fileName.contains('\\') &&
        !fileName.any(Char::isISOControl)
}

private inline fun <T> ProtocolResult<T>.then(next: () -> ProtocolResult<Unit>): ProtocolResult<Unit> {
    return when (this) {
        is Accepted -> next()
        is Rejected -> this
    }
}
