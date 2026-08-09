package com.skycommand.relay.protocol

import java.security.MessageDigest

data class MissionTransferStarted(
    val id: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val supersededId: String?,
)

data class CompletedMission(
    val id: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
)

class MissionTransferState {
    private var active: ActiveTransfer? = null

    fun begin(frame: MissionBeginFrame): ProtocolResult<MissionTransferStarted> {
        val validation = validate(frame)
        if (validation is Rejected) {
            return validation
        }
        val previous = active
        if (previous?.id == frame.id) {
            return Rejected(ProtocolError(ProtocolErrorCode.TRANSFER_SUPERSEDED, "Mission transfer ID is already active"))
        }
        active = ActiveTransfer(frame)
        return Accepted(
            MissionTransferStarted(
                id = frame.id,
                fileName = frame.fileName,
                size = frame.size,
                sha256 = frame.sha256,
                supersededId = previous?.id,
            )
        )
    }

    fun append(frame: MissionChunkFrame): ProtocolResult<Unit> {
        val validation = validate(frame)
        if (validation is Rejected) {
            return validation
        }
        val current = active ?: return Rejected(
            ProtocolError(ProtocolErrorCode.TRANSFER_NOT_ACTIVE, "No mission transfer is active")
        )
        if (current.id != frame.id) {
            return Rejected(ProtocolError(ProtocolErrorCode.TRANSFER_NOT_ACTIVE, "Mission transfer ID does not match"))
        }
        val bytes = frame.bytes
        if (current.received + bytes.size > current.expectedSize) {
            return Rejected(ProtocolError(ProtocolErrorCode.TRANSFER_SIZE_MISMATCH, "Mission exceeds declared size"))
        }
        current.digest.update(bytes)
        current.received += bytes.size
        return Accepted(Unit)
    }

    fun complete(frame: MissionCompleteFrame): ProtocolResult<CompletedMission> {
        val validation = validate(frame)
        if (validation is Rejected) {
            return validation
        }
        val current = active ?: return Rejected(
            ProtocolError(ProtocolErrorCode.TRANSFER_NOT_ACTIVE, "No mission transfer is active")
        )
        if (current.id != frame.id) {
            return Rejected(ProtocolError(ProtocolErrorCode.TRANSFER_NOT_ACTIVE, "Mission transfer ID does not match"))
        }
        if (current.received != current.expectedSize) {
            active = null
            return Rejected(ProtocolError(ProtocolErrorCode.TRANSFER_SIZE_MISMATCH, "Mission size is incomplete"))
        }
        val actualSha256 = current.digest.digest().toHex()
        active = null
        if (actualSha256 != current.expectedSha256) {
            return Rejected(ProtocolError(ProtocolErrorCode.TRANSFER_CHECKSUM_MISMATCH, "Mission checksum does not match"))
        }
        return Accepted(
            CompletedMission(
                id = current.id,
                fileName = current.fileName,
                size = current.expectedSize,
                sha256 = actualSha256,
            )
        )
    }

    fun reset() {
        active = null
    }

    private class ActiveTransfer(frame: MissionBeginFrame) {
        val id = frame.id
        val fileName = frame.fileName
        val expectedSize = frame.size
        val expectedSha256 = frame.sha256
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
