package com.skycommand.relay.photo.dji.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidDjiPhotoPortContractTest {
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
