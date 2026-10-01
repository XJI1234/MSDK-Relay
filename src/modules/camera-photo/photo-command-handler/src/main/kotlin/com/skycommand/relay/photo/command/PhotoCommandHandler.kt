package com.skycommand.relay.photo.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonArray
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PhotoRequest {
    data object Capture : PhotoRequest
    data class Fetch(val knownPhotos: Set<PhotoManifestEntry> = emptySet()) : PhotoRequest
}

data class PhotoManifestEntry(val fileName: String, val sha256: String)

data class PhotoCaptureIdentity(val fileName: String, val index: Long)

data class PhotoDownload(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val bytes: ByteArray,
    val count: Int = 1,
) {
    override fun equals(other: Any?): Boolean =
        other is PhotoDownload && fileName == other.fileName && size == other.size && sha256 == other.sha256 && count == other.count && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * (31 * fileName.hashCode() + sha256.hashCode()) + count) + bytes.contentHashCode()
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
    data object None : PhotoActionTerminalOutcome
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
        return when (command.name) {
            "camera.photo.capture" -> if (command.fields.fields.isEmpty()) PhotoRequest.Capture else null
            "camera.photo.fetch" -> parseFetch(command.fields)
            else -> null
        }
    }

    private fun parseFetch(fields: JsonObject): PhotoRequest? {
        if (fields.fields.isEmpty()) return PhotoRequest.Fetch()
        if (fields.fields.keys != setOf("knownPhotos")) return null
        val values = (fields["knownPhotos"] as? JsonArray)?.values ?: return null
        if (values.size > MAX_KNOWN_PHOTOS) return null
        val entries = linkedSetOf<PhotoManifestEntry>()
        values.forEach { value ->
            val item = value as? JsonObject ?: return null
            if (item.fields.keys != setOf("fileName", "sha256")) return null
            val fileName = (item["fileName"] as? JsonString)?.value ?: return null
            val sha256 = (item["sha256"] as? JsonString)?.value ?: return null
            if (!safePhotoName(fileName) || !sha256.matches(SHA256)) return null
            entries += PhotoManifestEntry(fileName, sha256)
        }
        return PhotoRequest.Fetch(entries)
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
        private const val MAX_KNOWN_PHOTOS = 256
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun safePhotoName(value: String): Boolean {
            val lower = value.lowercase()
            return value.isNotBlank() && value.codePointCount(0, value.length) <= 128 && !value.contains("..") && !value.contains('/') && !value.contains('\\') && !value.any(Char::isISOControl) && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".dng"))
        }

        fun create(actions: PhotoCommandActions): PhotoCommandHandler = PhotoCommandHandler(actions)
    }
}
