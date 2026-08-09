package com.skycommand.relay.protocol

enum class ProtocolErrorCode {
    INVALID_UTF8,
    INVALID_JSON,
    INVALID_FIELD,
    INVALID_BASE64,
    INVALID_SESSION_STATE,
    HANDSHAKE_REQUIRED,
    DUPLICATE_HANDSHAKE,
    FRAME_NOT_ALLOWED,
    TRANSFER_NOT_ACTIVE,
    TRANSFER_SUPERSEDED,
    TRANSFER_SIZE_MISMATCH,
    TRANSFER_CHECKSUM_MISMATCH,
    INVALID_DEVICE_ID,
    INVALID_SESSION_ID,
    INVALID_MESSAGE_ID,
    INVALID_PROTOCOL_VERSION,
    INVALID_COMMAND_NAME,
    INVALID_FILE_NAME,
    INVALID_SHA256,
    MISSION_TOO_LARGE,
    EMPTY_CHUNK,
    CHUNK_TOO_LARGE,
    INVALID_RESULT_DETAIL,
}

data class ProtocolError(
    val code: ProtocolErrorCode,
    val message: String,
)

sealed interface ProtocolResult<out T>

data class Accepted<T>(val value: T) : ProtocolResult<T>

data class Rejected(val error: ProtocolError) : ProtocolResult<Nothing>
