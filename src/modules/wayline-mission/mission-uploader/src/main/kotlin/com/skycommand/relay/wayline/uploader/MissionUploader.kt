package com.skycommand.relay.wayline.uploader

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.state.MissionStateEvent
import com.skycommand.relay.wayline.state.MissionStateStore
import com.skycommand.relay.wayline.state.UploadState
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface StagedMissionContentReader {
    fun open(metadata: MissionMetadata): InputStream
}

interface UploadCompletion {
    fun succeed()

    fun fail()

    fun fail(failure: MissionUploadFailure?) = fail()
}

interface PreparedMissionUpload {
    fun start(
        progress: (Int) -> Unit,
        completion: UploadCompletion,
    )

    fun discard()
}

sealed interface MissionUploadPreparation {
    data class Prepared(val upload: PreparedMissionUpload) : MissionUploadPreparation

    data object Rejected : MissionUploadPreparation
}

interface MissionUploadPort {
    fun prepare(
        metadata: MissionMetadata,
        content: InputStream,
    ): MissionUploadPreparation
}

class MissionUploadFailure private constructor(
    val errorCode: String,
    val errorDescription: String,
) {
    override fun equals(other: Any?): Boolean = other is MissionUploadFailure &&
        errorCode == other.errorCode && errorDescription == other.errorDescription

    override fun hashCode(): Int = 31 * errorCode.hashCode() + errorDescription.hashCode()

    override fun toString(): String = "MissionUploadFailure(errorCode=$errorCode, errorDescription=$errorDescription)"

    companion object {
        fun fromDjiError(errorCode: String?, errorDescription: String?): MissionUploadFailure = MissionUploadFailure(
            normalize(errorCode, maxCodePoints = 128, fallback = "UNKNOWN_DJI_ERROR"),
            normalize(errorDescription, maxCodePoints = 512, fallback = "DJI did not provide an error description"),
        )

        private fun normalize(value: String?, maxCodePoints: Int, fallback: String): String {
            if (value == null) return fallback
            val result = StringBuilder()
            var offset = 0
            var count = 0
            while (offset < value.length && count < maxCodePoints) {
                val codePoint = value.codePointAt(offset)
                if (!Character.isISOControl(codePoint)) {
                    result.appendCodePoint(codePoint)
                    count += 1
                }
                offset += Character.charCount(codePoint)
            }
            return result.toString().trim().ifBlank { fallback }
        }
    }
}

fun interface UploadTerminalListener {
    fun onCompleted(outcome: UploadTerminalOutcome)

    fun onCompleted(outcome: UploadTerminalOutcome, failure: MissionUploadFailure?) = onCompleted(outcome)
}

enum class UploadTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface UploadStartResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : UploadStartResult
    data class Rejected(val reason: UploadRejection) : UploadStartResult
}

enum class UploadRejection {
    NO_MISSION,
    ALREADY_ACTIVE,
    ALREADY_UPLOADED,
    CONTENT_UNAVAILABLE,
    OPERATION_REJECTED,
}

class MissionUploader private constructor(
    private val stateStore: MissionStateStore,
    private val contentReader: StagedMissionContentReader,
    private val uploadPort: MissionUploadPort,
    private val operationCoordinator: DjiOperationCoordinator,
    private val timeoutMillis: Long,
) {
    private val lock = ReentrantLock()
    private val sourceRevision = AtomicLong(0)
    private var active: ActiveUpload? = null

    fun start(listener: UploadTerminalListener = UploadTerminalListener { }): UploadStartResult {
        val snapshot = stateStore.snapshot()
        val metadata = snapshot.file ?: return UploadStartResult.Rejected(UploadRejection.NO_MISSION)
        if (snapshot.upload == UploadState.UPLOADED) {
            return UploadStartResult.Rejected(UploadRejection.ALREADY_UPLOADED)
        }

        val token = Any()
        val activeUpload = ActiveUpload(
            token = token,
            missionRevision = snapshot.missionRevision ?: return UploadStartResult.Rejected(UploadRejection.NO_MISSION),
            deviceGeneration = snapshot.deviceGeneration,
            listener = listener,
        )
        lock.withLock {
            if (active != null || snapshot.upload is UploadState.Uploading) {
                return UploadStartResult.Rejected(UploadRejection.ALREADY_ACTIVE)
            }
            active = activeUpload
        }

        val prepared = try {
            contentReader.open(metadata).use { source ->
                val verifiedSource = VerifiedMissionInput(source)
                when (val preparation = uploadPort.prepare(metadata, verifiedSource)) {
                    is MissionUploadPreparation.Prepared -> {
                        if (verifiedSource.matches(metadata)) {
                            preparation.upload
                        } else {
                            runCatching { preparation.upload.discard() }
                            null
                        }
                    }

                    MissionUploadPreparation.Rejected -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
        if (prepared == null) {
            finishBeforeSubmission(activeUpload, UploadState.FAILED)
            return UploadStartResult.Rejected(UploadRejection.CONTENT_UNAVAILABLE)
        }

        applyUploadState(activeUpload, UploadState.Uploading(0))

        val submission = operationCoordinator.submit(
            action = DjiOperation { operationCompletion ->
                prepared.start(
                    progress = { value -> recordProgress(activeUpload, value) },
                    completion = object : UploadCompletion {
                        override fun succeed() = operationCompletion.succeed()
                        override fun fail() = operationCompletion.fail()
                        override fun fail(failure: MissionUploadFailure?) {
                            activeUpload.installFailure(failure)
                            operationCompletion.fail()
                        }
                    },
                )
            },
            timeoutMillis = timeoutMillis,
            listener = OperationResultListener { outcome -> finish(activeUpload, outcome) },
        )
        val accepted = submission as? SubmissionResult.Accepted
        if (accepted == null) {
            runCatching { prepared.discard() }
            finishBeforeSubmission(activeUpload, UploadState.FAILED)
            return UploadStartResult.Rejected(UploadRejection.OPERATION_REJECTED)
        }
        return UploadStartResult.Accepted(accepted.cancellation)
    }

    private fun recordProgress(upload: ActiveUpload, value: Int) {
        if (value !in 0..100 || !isActive(upload)) return
        runCatching { applyUploadState(upload, UploadState.Uploading(value)) }
    }

    private fun finish(upload: ActiveUpload, outcome: OperationOutcome) {
        if (!clearIfActive(upload)) return
        val state = if (outcome == OperationOutcome.SUCCEEDED) UploadState.UPLOADED else UploadState.FAILED
        runCatching { applyUploadState(upload, state) }
        runCatching { upload.listener.onCompleted(outcome.toTerminalOutcome(), upload.failure) }
    }

    private fun finishBeforeSubmission(upload: ActiveUpload, state: UploadState) {
        if (!clearIfActive(upload)) return
        runCatching { applyUploadState(upload, state) }
    }

    private fun applyUploadState(upload: ActiveUpload, state: UploadState) {
        stateStore.apply(
            MissionStateEvent.UploadChanged(
                sourceRevision = sourceRevision.incrementAndGet(),
                missionRevision = upload.missionRevision,
                deviceGeneration = upload.deviceGeneration,
                state = state,
            ),
        )
    }

    private fun isActive(upload: ActiveUpload): Boolean = lock.withLock { active === upload }

    private class VerifiedMissionInput(source: InputStream) : FilterInputStream(source) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                digest.update(value.toByte())
                count += 1
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                digest.update(buffer, offset, read)
                count += read.toLong()
            }
            return read
        }

        fun matches(metadata: MissionMetadata): Boolean =
            count == metadata.expectedSize &&
                digest.digest()
                .joinToString("") { "%02x".format(it) }
                .equals(metadata.sha256, ignoreCase = true)
    }

    private fun clearIfActive(upload: ActiveUpload): Boolean = lock.withLock {
        if (active !== upload) false else {
            active = null
            true
        }
    }

    private data class ActiveUpload(
        val token: Any,
        val missionRevision: Long,
        val deviceGeneration: Long,
        val listener: UploadTerminalListener,
        var failure: MissionUploadFailure? = null,
    )

    private fun ActiveUpload.installFailure(value: MissionUploadFailure?) {
        failure = value
    }

    companion object {
        fun create(
            stateStore: MissionStateStore,
            contentReader: StagedMissionContentReader,
            uploadPort: MissionUploadPort,
            operationCoordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
        ): MissionUploader = MissionUploader(
            stateStore,
            contentReader,
            uploadPort,
            operationCoordinator,
            timeoutMillis,
        )
    }

    private fun OperationOutcome.toTerminalOutcome(): UploadTerminalOutcome = when (this) {
        OperationOutcome.SUCCEEDED -> UploadTerminalOutcome.SUCCEEDED
        OperationOutcome.FAILED -> UploadTerminalOutcome.FAILED
        OperationOutcome.TIMED_OUT -> UploadTerminalOutcome.TIMED_OUT
        OperationOutcome.CANCELLED -> UploadTerminalOutcome.CANCELLED
    }
}
