package com.skycommand.relay.photo.dji.android

import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.executor.PhotoDjiCompletion
import com.skycommand.relay.photo.executor.PhotoHardwareRequest
import com.skycommand.relay.photo.executor.PhotoLocalFile
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDjiPhotoPortContractTest {
    @Test
    fun cameraMediaRecoveryWaitsForThePlatformVideoInputResult() {
        val platform = FakeApi()
        val port = AndroidCameraMediaRecoveryPort { platform }
        var recovered: Boolean? = null

        port.recover { recovered = it }
        assertNull(recovered)

        platform.recovery!!.invoke(true)
        assertEquals(true, recovered)
    }

    @Test
    fun abortingAnOlderAttemptCannotAbortOrDeliverIntoTheNextAttempt() {
        val platforms = mutableListOf<FakeApi>()
        val cache = Files.createTempDirectory("photo-port-test").toFile()
        val port = AndroidDjiPhotoPort(cache) { FakeApi().also { platforms += it } }
        val oldResults = mutableListOf<PhotoCaptureIdentity>()
        val newResults = mutableListOf<PhotoCaptureIdentity>()
        val done = CountDownLatch(1)
        val old = captureCompletion { oldResults += it }
        val current = captureCompletion { newResults += it; done.countDown() }
        try {
            port.execute(PhotoHardwareRequest.Capture, old)
            port.execute(PhotoHardwareRequest.Capture, current)
            port.abort(old)

            assertTrue(platforms[0].aborted)
            assertTrue(!platforms[1].aborted)
            platforms[0].capture!!.succeed(PhotoCaptureIdentity("old.jpg", 1))
            platforms[1].capture!!.succeed(PhotoCaptureIdentity("new.jpg", 2))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(oldResults.isEmpty())
            assertTrue(newResults == listOf(PhotoCaptureIdentity("new.jpg", 2)))

            val delivered = CountDownLatch(1)
            var bytes: ByteArray? = null
            port.execute(PhotoHardwareRequest.Download(PhotoCaptureIdentity("new.jpg", 2)), object : PhotoDjiCompletion {
                override fun captured(identity: PhotoCaptureIdentity) = Unit
                override fun delivered(file: PhotoLocalFile) { bytes = file.readable.readAll(); delivered.countDown() }
                override fun fail() = Unit
            })
            assertTrue(!platforms[2].aborted)
            val original = File(cache, "original.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            platforms[2].download!!.succeed(original)
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertTrue(bytes!!.contentEquals(byteArrayOf(1, 2, 3)))
        } finally {
            port.close()
            cache.deleteRecursively()
        }
    }

    private fun captureCompletion(onCaptured: (PhotoCaptureIdentity) -> Unit) = object : PhotoDjiCompletion {
        override fun captured(identity: PhotoCaptureIdentity) = onCaptured(identity)
        override fun delivered(file: PhotoLocalFile) = Unit
        override fun fail() = Unit
    }

    private class FakeApi : DjiPhotoApi {
        var capture: DjiPhotoCaptureCompletion? = null
        var download: DjiPhotoDownloadCompletion? = null
        var recovery: ((Boolean) -> Unit)? = null
        var aborted = false
        override fun capture(completion: DjiPhotoCaptureCompletion) { capture = completion }
        override fun download(identity: PhotoCaptureIdentity, destFile: File, completion: DjiPhotoDownloadCompletion) {
            download = completion
        }
        override fun abort() { aborted = true }
        override fun recoverVideoInput(completion: (Boolean) -> Unit) { recovery = completion }
    }

    @Test
    fun hopsDjiCaptureAndDownloadCompletionsOffTheCallerThreadBeforeReadingBytesOrPublishing() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/photo/dji/android/AndroidDjiPhotoPort.kt"),
            Path("src/modules/camera-photo/android-dji-photo-adapter/src/main/kotlin/com/skycommand/relay/photo/dji/android/AndroidDjiPhotoPort.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("photo-delivery"))
        assertTrue(source.contains("Executors.newSingleThreadExecutor"))
        val captureCallback = source.substringAfter("platform.capture").substringBefore("is PhotoHardwareRequest.Download")
        assertTrue(captureCallback.contains("delivery.execute { captured("))
        assertTrue(captureCallback.contains("delivery.execute { fail("))
        val downloadCallback = source.substringAfter("platform.download").substringBefore("override fun abort")
        assertTrue(downloadCallback.contains("delivery.execute { delivered("))
        assertTrue(downloadCallback.contains("delivery.execute { fail("))
        assertTrue(source.contains("delivery.shutdownNow"))
        val delivered = source.substringAfter("private fun delivered").substringBefore("private fun fail")
        assertTrue(delivered.contains("file.readBytes()"))
    }
}
