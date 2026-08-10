package com.skycommand.relay.gateway.mission

import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.ActiveSession
import com.skycommand.relay.gateway.session.MissionSessionCleanup
import com.skycommand.relay.gateway.session.SessionEndReason
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.protocol.MissionBeginFrame
import com.skycommand.relay.protocol.MissionChunkFrame
import com.skycommand.relay.protocol.MissionCompleteFrame
import com.skycommand.relay.protocol.MissionResultFrame
import com.skycommand.relay.protocol.RelayFrame
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class MissionMetadata(
    val transferId: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
)

fun interface MissionReadable {
    fun openStream(): InputStream
}

data class StagedMission(
    val transferId: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val readableByMissionModule: MissionReadable,
)

enum class MissionAbortReason {
    SUPERSEDED,
    SESSION_ENDED,
    TRANSFER_FAILED,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
}

sealed interface MissionSinkResult {
    data object Accepted : MissionSinkResult

    data object Rejected : MissionSinkResult
}

sealed interface MissionSinkCompletionResult {
    data class Accepted(val mission: StagedMission) : MissionSinkCompletionResult

    data object Rejected : MissionSinkCompletionResult
}

interface MissionSink {
    fun begin(metadata: MissionMetadata): MissionSinkResult

    fun append(bytes: ByteArray): MissionSinkResult

    fun complete(): MissionSinkCompletionResult

    fun abort(reason: MissionAbortReason)
}

fun interface MissionResultPublisher {
    fun publish(activeSession: ActiveSession, frame: MissionResultFrame): PublishResult
}

sealed interface MissionTransferResult {
    data object Accepted : MissionTransferResult

    data class Completed(val mission: StagedMission) : MissionTransferResult

    data class Rejected(val kind: TransferRejectionKind) : MissionTransferResult

    data object UnsupportedFrame : MissionTransferResult
}

enum class TransferRejectionKind {
    TRANSFER_NOT_ACTIVE,
    TRANSFER_ALREADY_ACTIVE,
    TRANSFER_SUPERSEDED,
    TRANSFER_SIZE_MISMATCH,
    TRANSFER_CHECKSUM_MISMATCH,
    TRANSFER_FAILED,
}

class MissionTransfer(
    private val sink: MissionSink,
    private val resultPublisher: MissionResultPublisher,
) : MissionSessionCleanup {
    private val lock = ReentrantLock()
    private val activeTransfers = mutableMapOf<SessionGeneration, ActiveTransfer>()

    fun accept(activeSession: ActiveSession, frame: RelayFrame): MissionTransferResult = lock.withLock {
        when (frame) {
            is MissionBeginFrame -> begin(activeSession, frame)
            is MissionChunkFrame -> append(activeSession, frame)
            is MissionCompleteFrame -> complete(activeSession, frame)
            else -> MissionTransferResult.UnsupportedFrame
        }
    }

    override fun abort(generation: SessionGeneration, reason: SessionEndReason) {
        lock.withLock {
            activeTransfers.remove(generation)?.let { transfer ->
                abortSink(transfer, MissionAbortReason.SESSION_ENDED)
            }
        }
    }

    private fun begin(activeSession: ActiveSession, frame: MissionBeginFrame): MissionTransferResult {
        val generation = activeSession.generation
        val current = activeTransfers[generation]
        if (current != null && current.metadata.transferId == frame.id) {
            publishFailure(activeSession, frame.id, TransferRejectionKind.TRANSFER_ALREADY_ACTIVE)
            return MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_ALREADY_ACTIVE)
        }

        if (current != null) {
            activeTransfers.remove(generation)
            abortSink(current, MissionAbortReason.SUPERSEDED)
            publishFailure(activeSession, current.metadata.transferId, TransferRejectionKind.TRANSFER_SUPERSEDED)
        }

        val metadata = MissionMetadata(frame.id, frame.fileName, frame.size, frame.sha256)
        val accepted = runCatching { sink.begin(metadata) }.getOrNull() == MissionSinkResult.Accepted
        if (!accepted) {
            runCatching { sink.abort(MissionAbortReason.TRANSFER_FAILED) }
            publishFailure(activeSession, frame.id, TransferRejectionKind.TRANSFER_FAILED)
            return MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED)
        }

        activeTransfers[generation] = ActiveTransfer(metadata)
        return MissionTransferResult.Accepted
    }

    private fun append(activeSession: ActiveSession, frame: MissionChunkFrame): MissionTransferResult {
        val current = activeTransfers[activeSession.generation]
            ?: return reject(activeSession, frame.id, TransferRejectionKind.TRANSFER_NOT_ACTIVE)
        if (current.metadata.transferId != frame.id) {
            return reject(activeSession, frame.id, TransferRejectionKind.TRANSFER_NOT_ACTIVE)
        }

        val bytes = frame.bytes
        val nextSize = current.receivedBytes + bytes.size.toLong()
        if (nextSize > current.metadata.size) {
            return fail(activeSession, current, TransferRejectionKind.TRANSFER_SIZE_MISMATCH, MissionAbortReason.SIZE_MISMATCH)
        }

        val accepted = runCatching { sink.append(bytes.copyOf()) }.getOrNull() == MissionSinkResult.Accepted
        if (!accepted) {
            return fail(activeSession, current, TransferRejectionKind.TRANSFER_FAILED, MissionAbortReason.TRANSFER_FAILED)
        }
        current.receivedBytes = nextSize
        current.digest.update(bytes)
        return MissionTransferResult.Accepted
    }

    private fun complete(activeSession: ActiveSession, frame: MissionCompleteFrame): MissionTransferResult {
        val current = activeTransfers[activeSession.generation]
            ?: return reject(activeSession, frame.id, TransferRejectionKind.TRANSFER_NOT_ACTIVE)
        if (current.metadata.transferId != frame.id) {
            return reject(activeSession, frame.id, TransferRejectionKind.TRANSFER_NOT_ACTIVE)
        }
        if (current.receivedBytes != current.metadata.size) {
            return fail(activeSession, current, TransferRejectionKind.TRANSFER_SIZE_MISMATCH, MissionAbortReason.SIZE_MISMATCH)
        }
        if (!current.digest.digest().toHex().equals(current.metadata.sha256, ignoreCase = false)) {
            return fail(activeSession, current, TransferRejectionKind.TRANSFER_CHECKSUM_MISMATCH, MissionAbortReason.CHECKSUM_MISMATCH)
        }

        val completion = runCatching { sink.complete() }.getOrNull()
        val mission = (completion as? MissionSinkCompletionResult.Accepted)?.mission
        if (mission == null || !mission.matches(current.metadata)) {
            return fail(activeSession, current, TransferRejectionKind.TRANSFER_FAILED, MissionAbortReason.TRANSFER_FAILED)
        }

        activeTransfers.remove(activeSession.generation)
        publish(activeSession, MissionResultFrame(frame.id, true, "Mission staged"))
        return MissionTransferResult.Completed(mission)
    }

    private fun fail(
        activeSession: ActiveSession,
        current: ActiveTransfer,
        kind: TransferRejectionKind,
        abortReason: MissionAbortReason,
    ): MissionTransferResult {
        activeTransfers.remove(activeSession.generation)
        abortSink(current, abortReason)
        publishFailure(activeSession, current.metadata.transferId, kind)
        return MissionTransferResult.Rejected(kind)
    }

    private fun reject(
        activeSession: ActiveSession,
        transferId: String,
        kind: TransferRejectionKind,
    ): MissionTransferResult {
        publishFailure(activeSession, transferId, kind)
        return MissionTransferResult.Rejected(kind)
    }

    private fun abortSink(transfer: ActiveTransfer, reason: MissionAbortReason) {
        runCatching { sink.abort(reason) }
    }

    private fun publishFailure(activeSession: ActiveSession, transferId: String, kind: TransferRejectionKind) {
        publish(activeSession, MissionResultFrame(transferId, false, kind.detail()))
    }

    private fun publish(activeSession: ActiveSession, frame: MissionResultFrame) {
        runCatching { resultPublisher.publish(activeSession, frame) }
    }

    private data class ActiveTransfer(
        val metadata: MissionMetadata,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
        var receivedBytes: Long = 0,
    )

    private companion object {
        fun TransferRejectionKind.detail(): String = when (this) {
            TransferRejectionKind.TRANSFER_NOT_ACTIVE -> "Mission transfer is not active"
            TransferRejectionKind.TRANSFER_ALREADY_ACTIVE -> "Mission transfer is already active"
            TransferRejectionKind.TRANSFER_SUPERSEDED -> "Mission transfer was superseded"
            TransferRejectionKind.TRANSFER_SIZE_MISMATCH -> "Mission transfer size does not match"
            TransferRejectionKind.TRANSFER_CHECKSUM_MISMATCH -> "Mission transfer checksum does not match"
            TransferRejectionKind.TRANSFER_FAILED -> "Mission transfer failed"
        }

        fun StagedMission.matches(metadata: MissionMetadata): Boolean =
            transferId == metadata.transferId &&
                fileName == metadata.fileName &&
                size == metadata.size &&
                sha256 == metadata.sha256

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
