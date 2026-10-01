package com.skycommand.relay.photo

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.photo.command.PhotoActionCompletion
import com.skycommand.relay.photo.command.PhotoActionResult
import com.skycommand.relay.photo.command.PhotoActionTerminalOutcome
import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.command.PhotoCommandActions
import com.skycommand.relay.photo.command.PhotoCommandHandler
import com.skycommand.relay.photo.command.PhotoCommandRejection
import com.skycommand.relay.photo.command.PhotoCommandResult
import com.skycommand.relay.photo.command.PhotoDjiFailure
import com.skycommand.relay.photo.command.PhotoManifestEntry
import com.skycommand.relay.photo.command.PhotoRequest
import com.skycommand.relay.photo.executor.DjiPhotoPort
import com.skycommand.relay.photo.executor.PhotoExecutionListener
import com.skycommand.relay.photo.executor.PhotoExecutionOutcome
import com.skycommand.relay.photo.executor.PhotoExecutor
import com.skycommand.relay.photo.executor.PhotoHardwareRequest
import com.skycommand.relay.photo.executor.PhotoLocalFile
import com.skycommand.relay.photo.executor.PhotoSubmissionResult
import com.skycommand.relay.photo.media.PhotoMediaCompletion
import com.skycommand.relay.photo.media.PhotoMediaFile
import com.skycommand.relay.photo.media.PhotoMediaOutcome
import com.skycommand.relay.photo.media.PhotoMediaPublisher
import com.skycommand.relay.photo.media.PhotoMediaSubmitResult
import com.skycommand.relay.photo.media.PhotoReadableHandle
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.MediaResultFrame
import java.util.concurrent.atomic.AtomicBoolean

data class CameraPhotoDependencies(
    val djiPort: DjiPhotoPort,
    val operationCoordinator: DjiOperationCoordinator,
    val mediaPublisher: PhotoMediaPublisher,
    val onCameraMediaBusy: () -> Unit = {},
    val onCameraMediaReleased: () -> Unit = {},
    val sentLedger: PhotoSentLedger = PhotoSentLedger.memory(),
)

class CameraPhoto private constructor(
    private val dependencies: CameraPhotoDependencies,
) {
    private val lock = Any()
    private val active = mutableSetOf<OperationCancellationHandle>()
    private var requestInFlight = false
    private var lastIdentity: PhotoCaptureIdentity? = null
    private val executor = PhotoExecutor.create(dependencies.djiPort, dependencies.operationCoordinator)
    private val commands = PhotoCommandHandler.create(Actions())

    fun commandHandler(): CommandHandler = CommandHandler(::handle)

    fun acceptMediaResult(frame: MediaResultFrame) = dependencies.mediaPublisher.acceptResult(frame)

    fun abortTransfer() {
        dependencies.mediaPublisher.abort()
    }

    fun markDeviceUnavailable() {
        val pending = synchronized(lock) {
            requestInFlight = false
            lastIdentity = null
            active.toList().also { active.clear() }
        }
        pending.forEach { it.cancel() }
        dependencies.mediaPublisher.abort()
    }

    fun close() {
        markDeviceUnavailable()
        runCatching { dependencies.djiPort.close() }
    }

    private fun handle(command: CommandFrame, completion: CommandCompletion) {
        when (val result = commands.handle(command, object : PhotoActionCompletion {
            override fun complete(outcome: PhotoActionTerminalOutcome) = complete(outcome, null)

            override fun complete(outcome: PhotoActionTerminalOutcome, failure: PhotoDjiFailure?) {
                when (outcome) {
                    is PhotoActionTerminalOutcome.Captured -> completion.succeed(
                        "Photo captured",
                        JsonObject(
                            mapOf(
                                "domain" to JsonString("photo"),
                                "outcome" to JsonString("CAPTURED"),
                                "fileName" to JsonString(outcome.identity.fileName),
                                "index" to JsonNumber(outcome.identity.index.toString()),
                            ),
                        ),
                    )
                    is PhotoActionTerminalOutcome.Delivered -> completion.succeed(
                        "Photo delivered",
                        JsonObject(
                            mapOf(
                                "domain" to JsonString("photo"),
                                "outcome" to JsonString("DELIVERED"),
                                "fileName" to JsonString(outcome.download.fileName),
                                "size" to JsonNumber(outcome.download.size.toString()),
                                "sha256" to JsonString(outcome.download.sha256),
                                "count" to JsonNumber(outcome.download.count.toString()),
                            ),
                        ),
                    )
                    PhotoActionTerminalOutcome.Failed -> if (failure == null) {
                        completion.reject("Photo operation failed before DJI reported a result", terminalResult("INVOCATION_FAILED"))
                    } else {
                        completion.reject("Photo action was rejected", terminalResult("ACTION_REJECTED", failure))
                    }
                    PhotoActionTerminalOutcome.TransferFailed -> completion.reject(
                        "Photo transfer failed",
                        terminalResult("TRANSFER_FAILED"),
                    )
                    PhotoActionTerminalOutcome.TimedOut,
                    PhotoActionTerminalOutcome.Cancelled -> completion.reject(
                        "Photo operation result was not confirmed",
                        terminalResult("RESULT_UNCONFIRMED"),
                    )
                    PhotoActionTerminalOutcome.None -> completion.succeed(
                        "No unsent photos",
                        terminalResult("NONE", count = 0),
                    )
                }
            }
        })) {
            PhotoCommandResult.Accepted -> Unit
            is PhotoCommandResult.Rejected -> completion.reject(
                when (result.reason) {
                    PhotoCommandRejection.UNKNOWN_COMMAND -> "Photo command is not available"
                    PhotoCommandRejection.INVALID_FIELDS -> "Photo command fields are invalid"
                    PhotoCommandRejection.OPERATION_REJECTED -> "Photo operation was rejected"
                },
            )
        }
    }

    private inner class Actions : PhotoCommandActions {
        override fun execute(request: PhotoRequest, completion: PhotoActionCompletion): PhotoActionResult {
            synchronized(lock) {
                if (requestInFlight) return PhotoActionResult.Rejected
                requestInFlight = true
            }
            dependencies.onCameraMediaBusy()
            val fetch = request as? PhotoRequest.Fetch
            val excluded = (fetch?.knownPhotos?.mapTo(mutableSetOf()) { it.fileName } ?: mutableSetOf())
            var deliveredCount = 0
            var lastDownload: PhotoLocalFile? = null
            val mediaReleased = AtomicBoolean(false)
            val releaseMedia = { if (mediaReleased.compareAndSet(false, true)) dependencies.onCameraMediaReleased() }
            lateinit var submitNext: () -> PhotoActionResult
            submitNext = {
                val hardware = if (fetch === null) PhotoHardwareRequest.Capture else PhotoHardwareRequest.DownloadUnsent(excluded.toSet())
                var cancellation: OperationCancellationHandle? = null
                val operationCompleted = AtomicBoolean(false)
                val result = executor.execute(hardware, object : PhotoExecutionListener {
                    override fun onCompleted(outcome: PhotoExecutionOutcome) = onCompleted(outcome, null)

                    override fun onCompleted(outcome: PhotoExecutionOutcome, failure: PhotoDjiFailure?) {
                        retireOperation(cancellation, operationCompleted)
                        when (outcome) {
                            is PhotoExecutionOutcome.Delivered -> publish(
                                outcome.file,
                                PhotoMediaCompletion { mediaOutcome ->
                                    when (mediaOutcome) {
                                        is PhotoMediaOutcome.Delivered -> {
                                            excluded += mediaOutcome.fileName
                                            deliveredCount += 1
                                            lastDownload = outcome.file
                                            if (submitNext() is PhotoActionResult.Rejected) {
                                                completion.complete(PhotoActionTerminalOutcome.Failed)
                                            }
                                        }
                                        PhotoMediaOutcome.Failed -> {
                                            releaseMedia()
                                            finishRequest(null, operationCompleted)
                                            completion.complete(PhotoActionTerminalOutcome.TransferFailed)
                                        }
                                    }
                                },
                            )
                            PhotoExecutionOutcome.None -> {
                                releaseMedia()
                                finishRequest(null, operationCompleted)
                                if (deliveredCount == 0) {
                                    completion.complete(PhotoActionTerminalOutcome.None)
                                } else {
                                    val last = checkNotNull(lastDownload)
                                    completion.complete(PhotoActionTerminalOutcome.Delivered(last.toDownload(deliveredCount)))
                                }
                            }
                            else -> {
                                val terminal = when (outcome) {
                                    is PhotoExecutionOutcome.Captured -> {
                                        synchronized(lock) { lastIdentity = outcome.identity }
                                        PhotoActionTerminalOutcome.Captured(outcome.identity)
                                    }
                                    PhotoExecutionOutcome.Failed -> PhotoActionTerminalOutcome.Failed
                                    PhotoExecutionOutcome.TimedOut -> PhotoActionTerminalOutcome.TimedOut
                                    PhotoExecutionOutcome.Cancelled -> PhotoActionTerminalOutcome.Cancelled
                                    is PhotoExecutionOutcome.Delivered -> error("delivered is handled above")
                                    PhotoExecutionOutcome.None -> error("none is handled above")
                                }
                                if (outcome !is PhotoExecutionOutcome.TimedOut && outcome !is PhotoExecutionOutcome.Cancelled) {
                                    releaseMedia()
                                }
                                finishRequest(null, operationCompleted)
                                completion.complete(terminal, failure)
                            }
                        }
                    }

                    override fun onHardwareReleased() = releaseMedia()
                })
                when (result) {
                    is PhotoSubmissionResult.Accepted -> {
                        cancellation = result.cancellation
                        synchronized(lock) { if (!operationCompleted.get()) active += result.cancellation }
                        PhotoActionResult.Accepted
                    }
                    PhotoSubmissionResult.Rejected -> {
                        releaseMedia()
                        finishRequest(null, operationCompleted)
                        PhotoActionResult.Rejected
                    }
                }
            }
            return submitNext()
        }
    }

    private fun publish(
        file: PhotoLocalFile,
        completion: PhotoMediaCompletion,
    ) {
        val submitted = dependencies.mediaPublisher.publish(
            PhotoMediaFile(
                file.fileName,
                file.size,
                file.sha256,
                object : PhotoReadableHandle {
                    override fun openStream() = file.readable.openStream()
                    override fun close() = file.readable.close()
                },
            ),
            completion,
        )
        if (submitted is PhotoMediaSubmitResult.Rejected) completion.complete(PhotoMediaOutcome.Failed)
    }

    private fun retireOperation(cancellation: OperationCancellationHandle?, completed: AtomicBoolean) {
        completed.set(true)
        synchronized(lock) {
            cancellation?.let(active::remove)
        }
    }

    private fun finishRequest(cancellation: OperationCancellationHandle?, completed: AtomicBoolean) {
        retireOperation(cancellation, completed)
        synchronized(lock) { requestInFlight = false }
    }

    private fun PhotoLocalFile.toDownload(count: Int) = com.skycommand.relay.photo.command.PhotoDownload(fileName, size, sha256, ByteArray(0), count)

    private fun terminalResult(outcome: String, failure: PhotoDjiFailure? = null, count: Int? = null): JsonObject = JsonObject(
        buildMap {
            put("domain", JsonString("photo"))
            put("outcome", JsonString(outcome))
            count?.let { put("count", JsonNumber(it.toString())) }
            failure?.let {
                put("errorCode", JsonString(it.errorCode))
                put("errorDescription", JsonString(it.errorDescription))
            }
        },
    )

    companion object {
        fun create(dependencies: CameraPhotoDependencies): CameraPhoto = CameraPhoto(dependencies)
    }
}
