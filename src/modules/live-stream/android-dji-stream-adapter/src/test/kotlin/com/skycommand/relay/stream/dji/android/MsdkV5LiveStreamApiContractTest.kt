package com.skycommand.relay.stream.dji.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MsdkV5LiveStreamApiContractTest {
    @Test
    fun configuresRtmpThroughLiveStreamManagerWithoutOwningPreviewOrCameraMode() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()
        val start = source.substringAfter("override fun start").substringBefore("override fun stop")
        val stop = source.substringAfter("override fun stop")
        assertTrue(start.contains("ICameraStreamManager.ScaleType.FIX_XY"))
        assertTrue(start.contains("setLiveStreamScaleType"))
        assertTrue(start.contains("StreamQuality.FULL_HD"))
        assertTrue(start.contains("LiveVideoBitrateMode.AUTO"))
        assertFalse(start.contains("StreamQuality.HD\n") || start.contains("StreamQuality.HD)"))
        assertFalse(start.contains("StreamQuality.SD"))
        assertFalse(start.contains("StreamQuality.ORIGINAL"))
        assertFalse(start.contains("LiveVideoBitrateMode.MANUAL"))
        assertFalse(start.contains("setLiveVideoBitrate("))
        assertFalse(start.contains("cameraStreamManager"))
        assertFalse(start.contains("setKeepAliveDecoding"))
        assertFalse(start.contains("enableStream("))
        assertFalse(start.contains("putCameraStreamSurface"))
        assertFalse(start.contains("mediaManager"))
        assertFalse(start.contains("KeyManager"))
        assertFalse(start.contains("CameraMode"))
        assertFalse(start.contains("LiveDecodeSurface"))
        assertFalse(stop.contains("removeCameraStreamSurface"))
    }

    @Test
    fun invokesLiveStreamManagerWithoutAnUncoordinatedUiLooperHop() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()
        val start = source.substringAfter("override fun start").substringBefore("override fun stop")
        assertTrue(start.contains("setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)"))
        assertTrue(start.indexOf("setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)") < start.indexOf("startStream"))
        assertFalse(start.contains("Handler("))
        assertFalse(start.contains("Looper.getMainLooper()"))
    }

    @Test
    fun doesNotExposePhotoOrPreviewLifecycle() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()
        assertFalse(source.contains("LiveCameraDecodeHold"))
        assertFalse(source.contains("pauseForPhoto"))
        assertFalse(source.contains("resumeAfterPhoto"))
        assertFalse(source.contains("replaceDecodeSurface"))
    }
}
