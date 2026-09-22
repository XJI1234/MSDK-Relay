package com.skycommand.relay.photo.command

import com.skycommand.relay.protocol.CommandFrame
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PhotoRequest {
    data object Capture : PhotoRequest
    data object Fetch : PhotoRequest
}

data class PhotoCaptureIdentity(val fileName: String, val index: Long)

data class PhotoDownload(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PhotoDownload && fileName == other.fileName && size == other.size && sha256 == other.sha256 && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * fileName.hashCode() + sha256.hashCode()) + bytes.contentHashCode()
}

class PhotoDjiFailure private constructor(
    val errorCode: String,
    val errorDescription: String,
) {
    override fun equals(other: Any?): Boolean =
        other is PhotoDjiFailure && errorCode == other.errorCode && errorDescription == other.errorDescription

    override fun hashCode(): Int = 31 * errorCode.hashCode() + errorDescription.hashCode()

    companion object {
        fun fromDjiError(errorCode: String?, errorDescription: String?): PhotoDjiFailure = PhotoDjiFailure(
            normalize(errorCode, 128, "UNKNOWN_DJI_ERROR"),
            normalize(errorDescription, 512, "DJI did not provide an error description"),
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

fun interface PhotoActionCompletion {
    fun complete(outcome: PhotoActionTerminalOutcome)

    fun complete(outcome: PhotoActionTerminalOutcome, failure: PhotoDjiFailure?) = complete(outcome)
}

sealed interface PhotoActionTerminalOutcome {
    data class Captured(val identity: PhotoCaptureIdentity) : PhotoActionTerminalOutcome
    data class Delivered(val download: PhotoDownload) : PhotoActionTerminalOutcome
    data object Failed : PhotoActionTerminalOutcome
    data object TransferFailed : PhotoActionTerminalOutcome
    data object TimedOut : PhotoActionTerminalOutcome
    data object Cancelled : PhotoActionTerminalOutcome
}

sealed interface PhotoActionResult {
    data object Accepted : PhotoActionResult
    data object Rejected : PhotoActionResult
}

fun interface PhotoCommandActions {
    fun execute(request: PhotoRequest, completion: PhotoActionCompletion): PhotoActionResult
}

sealed interface PhotoCommandResult {
    data object Accepted : PhotoCommandResult
    data class Rejected(val reason: PhotoCommandRejection) : PhotoCommandResult
}

enum class PhotoCommandRejection {
    UNKNOWN_COMMAND,
    INVALID_FIELDS,
    OPERATION_REJECTED,
}

class PhotoCommandHandler private constructor(
    private val actions: PhotoCommandActions,
) {
    fun handle(command: CommandFrame): PhotoCommandResult = handle(command, PhotoActionCompletion { })

    fun handle(command: CommandFrame, completion: PhotoActionCompletion): PhotoCommandResult {
        val request = parse(command) ?: return PhotoCommandResult.Rejected(rejectionFor(command))
        return when (actions.execute(request, OnceCompletion(completion))) {
            PhotoActionResult.Accepted -> PhotoCommandResult.Accepted
            PhotoActionResult.Rejected -> PhotoCommandResult.Rejected(PhotoCommandRejection.OPERATION_REJECTED)
        }
    }

    private fun parse(command: CommandFrame): PhotoRequest? {
        if (command.fields.fields.isNotEmpty()) return null
        return when (command.name) {
            "camera.photo.capture" -> PhotoRequest.Capture
            "camera.photo.fetch" -> PhotoRequest.Fetch
            else -> null
        }
    }

    private fun rejectionFor(command: CommandFrame): PhotoCommandRejection =
        if (command.name == "camera.photo.capture" || command.name == "camera.photo.fetch") {
            PhotoCommandRejection.INVALID_FIELDS
        } else {
            PhotoCommandRejection.UNKNOWN_COMMAND
        }

    private class OnceCompletion(private val delegate: PhotoActionCompletion) : PhotoActionCompletion {
        private val completed = AtomicBoolean(false)
        override fun complete(outcome: PhotoActionTerminalOutcome) = complete(outcome, null)

        override fun complete(outcome: PhotoActionTerminalOutcome, failure: PhotoDjiFailure?) {
            if (completed.compareAndSet(false, true)) delegate.complete(outcome, failure)
        }
    }

    companion object {
        fun create(actions: PhotoCommandActions): PhotoCommandHandler = PhotoCommandHandler(actions)
    }
}
