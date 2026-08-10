package com.skycommand.relay.wayline.staging

import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class MissionMetadata(
    val fileName: String,
    val expectedSize: Long,
    val sha256: String,
)

interface StagingStorage {
    fun beginTemporary(metadata: MissionMetadata)
    fun append(bytes: ByteArray)
    fun flush()
    fun replaceCurrent()
    fun deleteTemporary()
}

sealed interface StagingRequestResult {
    data object Accepted : StagingRequestResult

    data class Rejected(val reason: StagingRejection) : StagingRequestResult
}

enum class StagingRejection {
    INVALID_METADATA,
    ACTIVE_TRANSFER,
    NO_ACTIVE_TRANSFER,
    SIZE_EXCEEDED,
    STORAGE_FAILURE,
}

sealed interface StagingCompleteResult {
    data class Staged(val metadata: MissionMetadata) : StagingCompleteResult

    data class Rejected(val reason: StagingRejection) : StagingCompleteResult
}

sealed interface StagingCancelResult {
    data object Cancelled : StagingCancelResult

    data object AlreadyFinished : StagingCancelResult
}

class MissionStaging private constructor(
    private val storage: StagingStorage,
) {
    private val lock = ReentrantLock()
    private var activeMetadata: MissionMetadata? = null
    private var activeBytes = 0L
    private var digest: MessageDigest? = null
    private var currentMetadata: MissionMetadata? = null

    fun begin(metadata: MissionMetadata): StagingRequestResult = lock.withLock {
        if (activeMetadata != null) return StagingRequestResult.Rejected(StagingRejection.ACTIVE_TRANSFER)
        if (!valid(metadata)) return StagingRequestResult.Rejected(StagingRejection.INVALID_METADATA)
        try {
            storage.beginTemporary(metadata)
        } catch (_: Throwable) {
            return StagingRequestResult.Rejected(StagingRejection.STORAGE_FAILURE)
        }
        activeMetadata = metadata
        activeBytes = 0
        digest = MessageDigest.getInstance("SHA-256")
        StagingRequestResult.Accepted
    }

    fun write(bytes: ByteArray): StagingRequestResult = lock.withLock {
        val metadata = activeMetadata ?: return StagingRequestResult.Rejected(StagingRejection.NO_ACTIVE_TRANSFER)
        if (bytes.size.toLong() > metadata.expectedSize - activeBytes) {
            abortLocked()
            return StagingRequestResult.Rejected(StagingRejection.SIZE_EXCEEDED)
        }
        try {
            storage.append(bytes)
            digest?.update(bytes)
            activeBytes += bytes.size
            StagingRequestResult.Accepted
        } catch (_: Throwable) {
            abortLocked()
            StagingRequestResult.Rejected(StagingRejection.STORAGE_FAILURE)
        }
    }

    fun complete(): StagingCompleteResult = lock.withLock {
        val metadata = activeMetadata ?: return StagingCompleteResult.Rejected(StagingRejection.NO_ACTIVE_TRANSFER)
        val actualDigest = digest?.digest()?.toHex()
        if (activeBytes != metadata.expectedSize || actualDigest != metadata.sha256.lowercase()) {
            abortLocked()
            return StagingCompleteResult.Rejected(StagingRejection.INVALID_METADATA)
        }
        try {
            storage.flush()
            storage.replaceCurrent()
        } catch (_: Throwable) {
            abortLocked()
            return StagingCompleteResult.Rejected(StagingRejection.STORAGE_FAILURE)
        }
        activeMetadata = null
        activeBytes = 0
        digest = null
        currentMetadata = metadata
        StagingCompleteResult.Staged(metadata)
    }

    fun cancel(): StagingCancelResult = lock.withLock {
        if (activeMetadata == null) return StagingCancelResult.AlreadyFinished
        abortLocked()
        StagingCancelResult.Cancelled
    }

    fun current(): MissionMetadata? = lock.withLock { currentMetadata }

    private fun abortLocked() {
        runCatching { storage.deleteTemporary() }
        activeMetadata = null
        activeBytes = 0
        digest = null
    }

    private fun valid(metadata: MissionMetadata): Boolean =
        metadata.fileName.isNotBlank() &&
            metadata.fileName.endsWith(".kmz", ignoreCase = true) &&
            metadata.fileName.none { it == '/' || it == '\\' || it.isISOControl() } &&
            metadata.fileName.length <= 255 &&
            metadata.expectedSize in 1..MAX_FILE_SIZE &&
            metadata.sha256.length == 64 && metadata.sha256.all { it in "0123456789abcdefABCDEF" }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_FILE_SIZE = 512L * 1024 * 1024

        fun create(storage: StagingStorage): MissionStaging = MissionStaging(storage)
    }
}
