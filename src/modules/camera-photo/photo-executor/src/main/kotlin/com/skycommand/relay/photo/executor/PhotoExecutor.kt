package com.skycommand.relay.photo.executor

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.device.operation.UnconfirmedOutcomeAdmission
import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.command.PhotoDjiFailure
import java.util.concurrent.atomic.AtomicReference

fun interface PhotoReadable {
    fun readAll(): ByteArray
    fun close() = Unit
}

data class PhotoLocalFile(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val readable: PhotoReadable,
)

interface PhotoDjiCompletion {
    fun captured(identity: PhotoCaptureIdentity)
    fun delivered(file: PhotoLocalFile)
    fun fail()

    fun fail(failure: PhotoDjiFailure?) = fail()
}

interface DjiPhotoPort {
    fun execute(request: PhotoHardwareRequest, completion: PhotoDjiCompletion)
    fun abort(completion: PhotoDjiCompletion) = Unit
    fun abort(completion: PhotoDjiCompletion, onHardwareReleased: () -> Unit) {
        abort(completion)
        onHardwareReleased()
    }
    fun close() = Unit
}

sealed interface PhotoHardwareRequest {
    data object Capture : PhotoHardwareRequest
    data class Download(val identity: PhotoCaptureIdentity) : PhotoHardwareRequest
}

fun interface PhotoExecutionListener {
    fun onCompleted(outcome: PhotoExecutionOutcome)

    fun onCompleted(outcome: PhotoExecutionOutcome, failure: PhotoDjiFailure?) = onCompleted(outcome)

    fun onHardwareReleased() = Unit
}

sealed interface PhotoExecutionOutcome {
    data class Captured(val identity: PhotoCaptureIdentity) : PhotoExecutionOutcome
    data class Delivered(val file: PhotoLocalFile) : PhotoExecutionOutcome
    data object Failed : PhotoExecutionOutcome
    data object TimedOut : PhotoExecutionOutcome
    data object Cancelled : PhotoExecutionOutcome
}

sealed interface PhotoSubmissionResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : PhotoSubmissionResult
    data object Rejected : PhotoSubmissionResult
}

class PhotoExecutor private constructor(
    private val port: DjiPhotoPort,
    private val coordinator: DjiOperationCoordinator,
) {
    fun execute(request: PhotoHardwareRequest, listener: PhotoExecutionListener = PhotoExecutionListener { }): PhotoSubmissionResult {
        var captured: PhotoCaptureIdentity? = null
        var delivered: PhotoLocalFile? = null
        var failure: PhotoDjiFailure? = null
        val submission = coordinator.submit(
            object : DjiOperation {
                private val portCompletion = AtomicReference<PhotoDjiCompletion?>(null)

                override fun unconfirmedOutcomeAdmission(): UnconfirmedOutcomeAdmission =
                    UnconfirmedOutcomeAdmission.SUPERSEDE_UNCONFIRMED

                override fun run(completion: OperationCompletion) {
                    val callback = object : PhotoDjiCompletion {
                        override fun captured(identity: PhotoCaptureIdentity) {
                            captured = identity
                            completion.succeed()
                        }

                        override fun delivered(file: PhotoLocalFile) {
                            delivered = file
                            completion.succeed()
                        }

                        override fun fail() = completion.fail()

                        override fun fail(value: PhotoDjiFailure?) {
                            failure = value
                            completion.fail()
                        }
                    }
                    portCompletion.set(callback)
                    port.execute(request, callback)
                }

                override fun onHardwareOutcomeUnconfirmed(outcome: OperationOutcome) {
                    portCompletion.get()?.let { callback ->
                        runCatching { port.abort(callback) { listener.onHardwareReleased() } }
                    } ?: listener.onHardwareReleased()
                }
            },
            timeoutMillis(request),
            OperationResultListener { outcome ->
                val terminal = when (outcome) {
                    OperationOutcome.SUCCEEDED -> when (request) {
                        PhotoHardwareRequest.Capture -> captured?.let(PhotoExecutionOutcome::Captured)
                            ?: PhotoExecutionOutcome.Failed
                        is PhotoHardwareRequest.Download -> delivered?.let(PhotoExecutionOutcome::Delivered)
                            ?: PhotoExecutionOutcome.Failed
                    }
                    OperationOutcome.FAILED -> PhotoExecutionOutcome.Failed
                    OperationOutcome.TIMED_OUT -> PhotoExecutionOutcome.TimedOut
                    OperationOutcome.CANCELLED -> PhotoExecutionOutcome.Cancelled
                }
                runCatching { listener.onCompleted(terminal, failure) }
            },
        )
        return when (submission) {
            is SubmissionResult.Accepted -> PhotoSubmissionResult.Accepted(submission.cancellation)
            SubmissionResult.Rejected -> PhotoSubmissionResult.Rejected
        }
    }

    companion object {
        const val CAPTURE_TIMEOUT_MILLIS = 15_000L
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L

        fun create(port: DjiPhotoPort, coordinator: DjiOperationCoordinator): PhotoExecutor =
            PhotoExecutor(port, coordinator)

        private fun timeoutMillis(request: PhotoHardwareRequest): Long = when (request) {
            PhotoHardwareRequest.Capture -> CAPTURE_TIMEOUT_MILLIS
            is PhotoHardwareRequest.Download -> DOWNLOAD_TIMEOUT_MILLIS
        }
    }
}
