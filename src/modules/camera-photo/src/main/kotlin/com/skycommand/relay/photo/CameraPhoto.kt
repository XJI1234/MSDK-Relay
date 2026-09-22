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
            val hardware = synchronized(lock) {
                if (requestInFlight) return PhotoActionResult.Rejected
                when (request) {
                    PhotoRequest.Capture -> PhotoHardwareRequest.Capture
                    PhotoRequest.Fetch -> {
                        val identity = lastIdentity ?: return PhotoActionResult.Rejected
                        PhotoHardwareRequest.Download(identity)
                    }
                }.also { requestInFlight = true }
            }
            var cancellation: OperationCancellationHandle? = null
            val completed = AtomicBoolean(false)
            val result = executor.execute(hardware, object : PhotoExecutionListener {
                override fun onCompleted(outcome: PhotoExecutionOutcome) = onCompleted(outcome, null)

                override fun onCompleted(outcome: PhotoExecutionOutcome, failure: PhotoDjiFailure?) {
                    when (outcome) {
                        is PhotoExecutionOutcome.Delivered -> publish(outcome.file, completion, cancellation, completed)
                        else -> {
                            finishRequest(cancellation, completed)
                            completion.complete(
                                when (outcome) {
                                    is PhotoExecutionOutcome.Captured -> {
                                        synchronized(lock) { lastIdentity = outcome.identity }
                                        PhotoActionTerminalOutcome.Captured(outcome.identity)
                                    }
                                    PhotoExecutionOutcome.Failed -> PhotoActionTerminalOutcome.Failed
                                    PhotoExecutionOutcome.TimedOut -> PhotoActionTerminalOutcome.TimedOut
                                    PhotoExecutionOutcome.Cancelled -> PhotoActionTerminalOutcome.Cancelled
                                    is PhotoExecutionOutcome.Delivered -> error("delivered is published separately")
                                },
                                failure,
                            )
                        }
                    }
                }
            })
            return when (result) {
                is PhotoSubmissionResult.Accepted -> {
                    cancellation = result.cancellation
                    synchronized(lock) { if (!completed.get()) active += result.cancellation }
                    PhotoActionResult.Accepted
                }
                PhotoSubmissionResult.Rejected -> {
                    synchronized(lock) { requestInFlight = false }
                    PhotoActionResult.Rejected
                }
            }
        }
    }

    private fun publish(
        file: PhotoLocalFile,
        completion: PhotoActionCompletion,
        cancellation: OperationCancellationHandle?,
        completed: AtomicBoolean,
    ) {
        val submitted = dependencies.mediaPublisher.publish(
            PhotoMediaFile(
                file.fileName,
                file.size,
                file.sha256,
                object : PhotoReadableHandle {
                    override fun readAll() = file.readable.readAll()
                    override fun close() = file.readable.close()
                },
            ),
            PhotoMediaCompletion { outcome ->
                finishRequest(cancellation, completed)
                when (outcome) {
                    is PhotoMediaOutcome.Delivered -> {
                        synchronized(lock) { lastIdentity = null }
                        completion.complete(
                            PhotoActionTerminalOutcome.Delivered(
                                com.skycommand.relay.photo.command.PhotoDownload(
                                    outcome.fileName,
                                    outcome.size,
                                    outcome.sha256,
                                    ByteArray(0),
                                ),
                            ),
                        )
                    }
                    PhotoMediaOutcome.Failed -> completion.complete(PhotoActionTerminalOutcome.TransferFailed)
                }
            },
        )
        if (submitted is PhotoMediaSubmitResult.Rejected) {
            finishRequest(cancellation, completed)
            completion.complete(PhotoActionTerminalOutcome.TransferFailed)
        }
    }

    private fun finishRequest(cancellation: OperationCancellationHandle?, completed: AtomicBoolean) {
        completed.set(true)
        synchronized(lock) {
            requestInFlight = false
            cancellation?.let(active::remove)
        }
    }

    private fun terminalResult(outcome: String, failure: PhotoDjiFailure? = null): JsonObject = JsonObject(
        buildMap {
            put("domain", JsonString("photo"))
            put("outcome", JsonString(outcome))
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
