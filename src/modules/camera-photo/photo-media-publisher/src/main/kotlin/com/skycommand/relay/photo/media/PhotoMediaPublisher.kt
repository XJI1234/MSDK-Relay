package com.skycommand.relay.photo.media

import com.skycommand.relay.protocol.Accepted
import com.skycommand.relay.protocol.MediaBeginFrame
import com.skycommand.relay.protocol.MediaChunkFrame
import com.skycommand.relay.protocol.MediaCompleteFrame
import com.skycommand.relay.protocol.MediaResultFrame
import com.skycommand.relay.protocol.ProtocolLimits
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.validate
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

fun interface PhotoMediaWriter {
    fun write(frame: RelayFrame): Boolean
}

fun interface PhotoMediaClock {
    fun schedule(delayMillis: Long, callback: () -> Unit): PhotoMediaCancellation
}

fun interface PhotoMediaCancellation {
    fun cancel()
}

interface PhotoReadableHandle {
    fun openStream(): InputStream
    fun close()
}

data class PhotoMediaFile(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val readable: PhotoReadableHandle,
)

sealed interface PhotoMediaSubmitResult {
    data object Accepted : PhotoMediaSubmitResult
    enum class Reason { BUSY, INVALID_FILE }
    data class Rejected(val reason: Reason) : PhotoMediaSubmitResult
}

sealed interface PhotoMediaOutcome {
    data class Delivered(val fileName: String, val size: Long, val sha256: String) : PhotoMediaOutcome
    data object Failed : PhotoMediaOutcome
}

fun interface PhotoMediaCompletion {
    fun complete(outcome: PhotoMediaOutcome)
}

class PhotoMediaPublisher private constructor(
    private val writer: PhotoMediaWriter,
    private val clock: PhotoMediaClock,
    private val timeoutMillis: Long,
) {
    private val lock = Any()
    private val nextId = AtomicLong(1)
    private var active: Active? = null

    fun publish(file: PhotoMediaFile, completion: PhotoMediaCompletion): PhotoMediaSubmitResult {
        val transferId = "photo-${nextId.get()}"
        if (!validateFile(file, transferId)) {
            runCatching { file.readable.close() }
            return PhotoMediaSubmitResult.Rejected(PhotoMediaSubmitResult.Reason.INVALID_FILE)
        }
        synchronized(lock) {
            if (active != null) {
                runCatching { file.readable.close() }
                return PhotoMediaSubmitResult.Rejected(PhotoMediaSubmitResult.Reason.BUSY)
            }
            nextId.incrementAndGet()
            active = Active(transferId, file.fileName, file.size, file.sha256, completion)
        }
        val timeout = clock.schedule(timeoutMillis) { timeout(transferId) }
        synchronized(lock) {
            val current = active
            if (current == null || current.id != transferId) {
                runCatching { timeout.cancel() }
                return PhotoMediaSubmitResult.Accepted
            }
            current.timeout = timeout
        }
        val sent = try {
            send(transferId, file.fileName, file.size, file.sha256, file.readable)
        } finally {
            runCatching { file.readable.close() }
        }
        if (!sent) finish(transferId, PhotoMediaOutcome.Failed)
        return PhotoMediaSubmitResult.Accepted
    }

    fun acceptResult(frame: MediaResultFrame) {
        val current = synchronized(lock) { active }
        if (current == null || current.id != frame.id) return
        finish(frame.id, if (frame.ok) PhotoMediaOutcome.Delivered(current.fileName, current.size, current.sha256) else PhotoMediaOutcome.Failed)
    }

    fun abort() {
        val id = synchronized(lock) { active?.id } ?: return
        finish(id, PhotoMediaOutcome.Failed)
    }

    private fun timeout(id: String) = finish(id, PhotoMediaOutcome.Failed)

    private fun validateFile(file: PhotoMediaFile, transferId: String): Boolean {
        if (validate(MediaBeginFrame(transferId, file.fileName, file.size, file.sha256)) !is Accepted) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.readable.openStream().use { input ->
                val buffer = ByteArray(ProtocolLimits.maxMissionChunkBytes)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    size += count
                }
            }
            size == file.size && digest.digest().toHex() == file.sha256
        }.getOrDefault(false)
    }

    private fun send(id: String, fileName: String, size: Long, sha256: String, readable: PhotoReadableHandle): Boolean {
        if (!write(id, MediaBeginFrame(id, fileName, size, sha256))) return false
        readable.openStream().use { input ->
            val buffer = ByteArray(ProtocolLimits.maxMissionChunkBytes)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (!write(id, MediaChunkFrame(id, buffer.copyOf(count)))) return false
            }
        }
        return write(id, MediaCompleteFrame(id))
    }

    private fun write(id: String, frame: RelayFrame): Boolean {
        if (synchronized(lock) { active?.id != id }) return false
        return runCatching { writer.write(frame) }.getOrDefault(false)
    }

    private fun finish(id: String, outcome: PhotoMediaOutcome) {
        val current = synchronized(lock) {
            val active = this.active ?: return
            if (active.id != id || active.completed) return
            active.completed = true
            this.active = null
            active
        }
        runCatching { current.timeout?.cancel() }
        runCatching { current.completion.complete(outcome) }
    }

    private class Active(
        val id: String,
        val fileName: String,
        val size: Long,
        val sha256: String,
        val completion: PhotoMediaCompletion,
        var timeout: PhotoMediaCancellation? = null,
        var completed: Boolean = false,
    )

    companion object {
        fun create(
            writer: PhotoMediaWriter,
            clock: PhotoMediaClock,
            timeoutMillis: Long = 120_000,
        ): PhotoMediaPublisher = PhotoMediaPublisher(writer, clock, timeoutMillis)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
