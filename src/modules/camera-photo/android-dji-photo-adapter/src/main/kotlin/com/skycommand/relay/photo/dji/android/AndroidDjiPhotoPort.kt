package com.skycommand.relay.photo.dji.android

import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.command.PhotoDjiFailure
import com.skycommand.relay.photo.executor.CameraMediaRecoveryPort
import com.skycommand.relay.photo.executor.DjiPhotoPort
import com.skycommand.relay.photo.executor.PhotoDjiCompletion
import com.skycommand.relay.photo.executor.PhotoHardwareRequest
import com.skycommand.relay.photo.executor.PhotoLocalFile
import com.skycommand.relay.photo.executor.PhotoReadable
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal interface DjiPhotoCaptureCompletion {
    fun succeed(identity: PhotoCaptureIdentity)
    fun fail()
    fun fail(failure: PhotoDjiFailure?) = fail()
}

internal interface DjiPhotoDownloadCompletion {
    fun succeed(file: File)
    fun fail()
    fun fail(failure: PhotoDjiFailure?) = fail()
}

internal interface DjiPhotoApi {
    fun capture(completion: DjiPhotoCaptureCompletion)
    fun download(identity: PhotoCaptureIdentity, destFile: File, completion: DjiPhotoDownloadCompletion)
    fun abort()
    fun abort(onHardwareReleased: () -> Unit) {
        abort()
        onHardwareReleased()
    }
    fun readPlaybackActive(completion: (Boolean?) -> Unit) = completion(null)
    fun recoverVideoInput(completion: (Boolean) -> Unit) {
        abort { completion(true) }
    }
    fun close() = Unit
}

class AndroidCameraMediaRecoveryPort internal constructor(
    private val platformFactory: () -> DjiPhotoApi,
) : CameraMediaRecoveryPort {
    override fun recover(completion: (Boolean) -> Unit) {
        val platform = platformFactory()
        val completed = AtomicBoolean(false)
        val finish = { recovered: Boolean ->
            if (completed.compareAndSet(false, true)) {
                runCatching { platform.close() }
                completion(recovered)
            }
        }
        runCatching {
            platform.readPlaybackActive { playingBack ->
                if (playingBack != true) {
                    finish(true)
                    return@readPlaybackActive
                }
                runCatching {
                    platform.recoverVideoInput(finish)
                }.onFailure {
                    finish(false)
                }
            }
        }.onFailure {
            finish(false)
        }
    }

    companion object {
        fun create(): CameraMediaRecoveryPort = AndroidCameraMediaRecoveryPort { MsdkV5PhotoApi() }
    }
}

class AndroidDjiPhotoPort internal constructor(
    private val cacheDir: File,
    private val platformFactory: () -> DjiPhotoApi,
) : DjiPhotoPort {
    private val lock = Any()
    private var closed = false
    private var active: Active? = null
    private val operations = IdentityHashMap<PhotoDjiCompletion, Active>()
    private val delivery = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "photo-delivery").apply { isDaemon = true }
    }

    override fun execute(request: PhotoHardwareRequest, completion: PhotoDjiCompletion) {
        synchronized(lock) {
            if (closed) {
                runCatching { completion.fail() }
                return
            }
            active?.let(::stop)
            val operation = Active(completion, platformFactory())
            active = operation
            operations[completion] = operation
            when (request) {
                PhotoHardwareRequest.Capture -> operation.platform.capture(object : DjiPhotoCaptureCompletion {
                    override fun succeed(identity: PhotoCaptureIdentity) {
                        runCatching { delivery.execute { captured(operation, identity) } }
                    }
                    override fun fail() {
                        runCatching { delivery.execute { fail(operation) } }
                    }
                    override fun fail(failure: PhotoDjiFailure?) {
                        runCatching { delivery.execute { fail(operation, failure) } }
                    }
                })
                is PhotoHardwareRequest.Download -> {
                    val dest = File(cacheDir, "sky-command-${System.nanoTime()}.bin")
                    operation.platform.download(request.identity, dest, object : DjiPhotoDownloadCompletion {
                        override fun succeed(file: File) {
                            runCatching { delivery.execute { delivered(operation, request.identity, file) } }
                        }
                        override fun fail() {
                            runCatching { delivery.execute { fail(operation) } }
                        }
                        override fun fail(failure: PhotoDjiFailure?) {
                            runCatching { delivery.execute { fail(operation, failure) } }
                        }
                    })
                }
            }
        }
    }

    override fun abort(completion: PhotoDjiCompletion) {
        synchronized(lock) { operations[completion]?.let(::stop) }
    }

    override fun abort(completion: PhotoDjiCompletion, onHardwareReleased: () -> Unit) {
        synchronized(lock) { operations[completion]?.let { stop(it, onHardwareReleased) } ?: onHardwareReleased() }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            operations.values.toList().forEach(::stop)
        }
        delivery.shutdownNow()
    }

    private fun captured(operation: Active, identity: PhotoCaptureIdentity) {
        val deliver = finish(operation)
        if (deliver) runCatching { operation.completion.captured(identity) }
    }

    private fun delivered(operation: Active, identity: PhotoCaptureIdentity, file: File) {
        val deliver = finish(operation)
        val bytes = if (deliver) runCatching { file.readBytes() }.getOrNull() else null
        runCatching { file.delete() }
        if (!deliver) return
        if (bytes == null || bytes.isEmpty()) {
            runCatching { operation.completion.fail() }
            return
        }
        runCatching {
            operation.completion.delivered(
                PhotoLocalFile(
                    identity.fileName,
                    bytes.size.toLong(),
                    sha256Hex(bytes),
                    PhotoReadable { bytes },
                ),
            )
        }
    }

    private fun fail(operation: Active, failure: PhotoDjiFailure? = null) {
        val deliver = finish(operation)
        if (deliver) runCatching { operation.completion.fail(failure) }
    }

    private fun finish(operation: Active): Boolean = synchronized(lock) {
        if (!operation.completeOnce()) return@synchronized false
        operations.remove(operation.completion)
        val deliver = active === operation && !closed
        if (active === operation) active = null
        runCatching { operation.platform.close() }
        deliver
    }

    private fun stop(operation: Active) {
        stop(operation) { }
    }

    private fun stop(operation: Active, onHardwareReleased: () -> Unit) {
        if (!operation.completeOnce()) return
        operations.remove(operation.completion)
        if (active === operation) active = null
        runCatching { operation.platform.abort(onHardwareReleased) }
            .onFailure { onHardwareReleased() }
        runCatching { operation.platform.close() }
    }

    private class Active(val completion: PhotoDjiCompletion, val platform: DjiPhotoApi) {
        private var completed = false
        fun completeOnce(): Boolean {
            if (completed) return false
            completed = true
            return true
        }
    }

    companion object {
        fun create(cacheDir: File): DjiPhotoPort = AndroidDjiPhotoPort(cacheDir) { MsdkV5PhotoApi() }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
