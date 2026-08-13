package com.skycommand.relay.protocol

enum class ProtocolErrorCode {
    FRAME_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    INVALID_FIELD,
    INVALID_MESSAGE_TYPE,
    INVALID_BASE64,
    INVALID_DEVICE_ID,
    INVALID_SESSION_ID,
    INVALID_MESSAGE_ID,
    PROTOCOL_VERSION_UNSUPPORTED,
    INVALID_COMMAND_NAME,
    INVALID_FILE_NAME,
    INVALID_SHA256,
    MISSION_SIZE_OUT_OF_RANGE,
    EMPTY_CHUNK,
    CHUNK_TOO_LARGE,
    INVALID_RESULT_DETAIL,
    INVALID_DIAGNOSTIC_REPORT,
    INVALID_DIAGNOSTIC_ACKNOWLEDGEMENT,
}

@ConsistentCopyVisibility
data class ProtocolError internal constructor(
    val code: ProtocolErrorCode,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Protocol error message must not be blank" }
        require(message.codePointCount(0, message.length) <= ProtocolLimits.maxErrorMessageCodePoints) {
            "Protocol error message is too long"
        }
        require(message.none(Char::isISOControl)) { "Protocol error message contains a control character" }
    }
}

sealed interface ProtocolResult<out T>

data class Accepted<T>(val value: T) : ProtocolResult<T>

data class Rejected(val error: ProtocolError) : ProtocolResult<Nothing>
