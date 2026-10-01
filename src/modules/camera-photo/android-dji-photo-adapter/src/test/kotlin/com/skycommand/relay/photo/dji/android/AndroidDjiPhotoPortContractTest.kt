package com.skycommand.relay.photo.dji.android

import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.executor.PhotoDjiCompletion
import com.skycommand.relay.photo.executor.PhotoHardwareRequest
import com.skycommand.relay.photo.executor.PhotoLocalFile
import com.skycommand.relay.photo.executor.CameraMediaStateSnapshot
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

        platform.playbackRead!!.invoke(true)
        assertNull(recovered)
        platform.recovery!!.invoke(true)
        assertEquals(true, recovered)
    }

    @Test
    fun cameraMediaRecoveryDoesNotDisableWhenPlaybackIsNotActive() {
        val platform = FakeApi()
        val port = AndroidCameraMediaRecoveryPort { platform }
        var recovered: Boolean? = null

        port.recover { recovered = it }
        platform.playbackRead!!.invoke(false)

        assertEquals(true, recovered)
        assertNull(platform.recovery)
    }

    @Test
    fun zeroFrameRecoveryDirectlyRequestsVideoInputRecovery() {
        val platform = FakeApi()
        val port = AndroidCameraMediaRecoveryPort { platform }
        var recovered: Boolean? = null

        port.recoverAfterZeroFrameStart { recovered = it }

        assertNull(platform.playbackRead)
        assertTrue(platform.recovery != null)
        platform.recovery!!.invoke(true)
        assertEquals(true, recovered)
    }

    @Test
    fun zeroFrameMediaStateInspectionReadsModeAndPlaybackWithoutRecoveringVideoInput() {
        val platform = FakeApi()
        val port = AndroidCameraMediaRecoveryPort { platform }
        var state: CameraMediaStateSnapshot? = null

        port.inspectBeforeZeroFrameRecovery { state = it }

        assertTrue(platform.cameraModeRead != null)
        assertNull(platform.playbackRead)
        assertNull(platform.recovery)
        platform.cameraModeRead!!.invoke("PHOTO_NORMAL")
        assertTrue(platform.playbackRead != null)
        assertNull(platform.recovery)
        platform.playbackRead!!.invoke(false)
        assertEquals(CameraMediaStateSnapshot("PHOTO_NORMAL", false), state)
        assertNull(platform.recovery)
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
                override fun delivered(file: PhotoLocalFile) { file.readable.openStream().use { bytes = it.readBytes() }; file.readable.close(); delivered.countDown() }
                override fun empty() = Unit
                override fun fail() = Unit
            })
            assertTrue(!platforms[2].aborted)
            val original = File(cache, "original.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            platforms[2].download!!.succeed(original, "new.jpg")
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
        override fun empty() = Unit
        override fun fail() = Unit
    }

    private class FakeApi : DjiPhotoApi {
        var capture: DjiPhotoCaptureCompletion? = null
        var download: DjiPhotoDownloadCompletion? = null
        var playbackRead: ((Boolean?) -> Unit)? = null
        var cameraModeRead: ((String?) -> Unit)? = null
        var recovery: ((Boolean) -> Unit)? = null
        var aborted = false
        override fun capture(completion: DjiPhotoCaptureCompletion) { capture = completion }
        override fun download(identity: PhotoCaptureIdentity, destFile: File, completion: DjiPhotoDownloadCompletion) {
            download = completion
        }
        override fun downloadNext(alreadySent: Set<String>, destFile: File, completion: DjiPhotoDownloadCompletion) {
            download = completion
        }
        override fun abort() { aborted = true }
        override fun readPlaybackActive(completion: (Boolean?) -> Unit) { playbackRead = completion }
        override fun readCameraMode(completion: (String?) -> Unit) { cameraModeRead = completion }
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
        assertTrue(delivered.contains("file.inputStream()"))
        assertTrue(delivered.contains("override fun openStream()"))
    }
}
