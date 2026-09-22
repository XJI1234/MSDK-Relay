package com.skycommand.relay.photo.dji.android

import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.command.PhotoDjiFailure
import com.skycommand.relay.photo.executor.DjiPhotoPort
import com.skycommand.relay.photo.executor.PhotoDjiCompletion
import com.skycommand.relay.photo.executor.PhotoHardwareRequest
import com.skycommand.relay.photo.executor.PhotoLocalFile
import com.skycommand.relay.photo.executor.PhotoReadable
import java.io.File
import java.security.MessageDigest
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
}

class AndroidDjiPhotoPort internal constructor(
    private val cacheDir: File,
    private val platform: DjiPhotoApi,
) : DjiPhotoPort {
    private val lock = Any()
    private var closed = false
    private var active: Active? = null
    private val delivery = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "photo-delivery").apply { isDaemon = true }
    }

    override fun execute(request: PhotoHardwareRequest, completion: PhotoDjiCompletion) {
        val operation = synchronized(lock) {
            if (closed) null else Active(completion).also { active = it }
        }
        if (operation == null) {
            runCatching { completion.fail() }
            return
        }
        when (request) {
            PhotoHardwareRequest.Capture -> platform.capture(object : DjiPhotoCaptureCompletion {
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
                platform.download(request.identity, dest, object : DjiPhotoDownloadCompletion {
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

    override fun abort() {
        runCatching { platform.abort() }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            active = null
        }
        runCatching { platform.abort() }
        delivery.shutdownNow()
    }

    private fun captured(operation: Active, identity: PhotoCaptureIdentity) {
        if (!operation.completeOnce()) return
        val deliver = synchronized(lock) {
            if (active === operation) active = null
            !closed
        }
        if (deliver) runCatching { operation.completion.captured(identity) }
    }

    private fun delivered(operation: Active, identity: PhotoCaptureIdentity, file: File) {
        if (!operation.completeOnce()) return
        val bytes = runCatching { file.readBytes() }.getOrNull()
        runCatching { file.delete() }
        val deliver = synchronized(lock) {
            if (active === operation) active = null
            !closed
        }
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
        if (!operation.completeOnce()) return
        val deliver = synchronized(lock) {
            if (active === operation) active = null
            !closed
        }
        if (deliver) runCatching { operation.completion.fail(failure) }
    }

    private class Active(val completion: PhotoDjiCompletion) {
        private val completed = AtomicBoolean(false)
        fun completeOnce(): Boolean = completed.compareAndSet(false, true)
    }

    companion object {
        fun create(cacheDir: File): DjiPhotoPort = AndroidDjiPhotoPort(cacheDir, MsdkV5PhotoApi())

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
