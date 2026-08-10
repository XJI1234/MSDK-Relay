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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface StagedMissionContentReader {
    fun read(metadata: MissionMetadata): ByteArray
}

interface UploadCompletion {
    fun succeed()

    fun fail()
}

interface MissionUploadPort {
    fun upload(
        metadata: MissionMetadata,
        bytes: ByteArray,
        progress: (Int) -> Unit,
        completion: UploadCompletion,
    )
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

    fun start(): UploadStartResult {
        val snapshot = stateStore.snapshot()
        val metadata = snapshot.file ?: return UploadStartResult.Rejected(UploadRejection.NO_MISSION)
        if (snapshot.upload == UploadState.UPLOADED) {
            return UploadStartResult.Rejected(UploadRejection.ALREADY_UPLOADED)
        }

        val token = Any()
        val activeUpload = ActiveUpload(token, snapshot.missionRevision ?: return UploadStartResult.Rejected(UploadRejection.NO_MISSION))
        lock.withLock {
            if (active != null || snapshot.upload is UploadState.Uploading) {
                return UploadStartResult.Rejected(UploadRejection.ALREADY_ACTIVE)
            }
            active = activeUpload
        }

        applyUploadState(activeUpload, UploadState.Uploading(0))
        val bytes = try {
            contentReader.read(metadata)
        } catch (_: Throwable) {
            finishBeforeSubmission(activeUpload, UploadState.FAILED)
            return UploadStartResult.Rejected(UploadRejection.CONTENT_UNAVAILABLE)
        }

        val submission = operationCoordinator.submit(
            action = DjiOperation { operationCompletion ->
                uploadPort.upload(
                    metadata = metadata,
                    bytes = bytes,
                    progress = { value -> recordProgress(activeUpload, value) },
                    completion = object : UploadCompletion {
                        override fun succeed() = operationCompletion.succeed()
                        override fun fail() = operationCompletion.fail()
                    },
                )
            },
            timeoutMillis = timeoutMillis,
            listener = OperationResultListener { outcome -> finish(activeUpload, outcome) },
        )
        val accepted = submission as? SubmissionResult.Accepted
        if (accepted == null) {
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
                state = state,
            ),
        )
    }

    private fun isActive(upload: ActiveUpload): Boolean = lock.withLock { active === upload }

    private fun clearIfActive(upload: ActiveUpload): Boolean = lock.withLock {
        if (active !== upload) false else {
            active = null
            true
        }
    }

    private data class ActiveUpload(
        val token: Any,
        val missionRevision: Long,
    )

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
}
