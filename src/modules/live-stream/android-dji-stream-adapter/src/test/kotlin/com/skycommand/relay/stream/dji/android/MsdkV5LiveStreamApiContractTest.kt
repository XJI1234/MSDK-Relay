package com.skycommand.relay.stream.dji.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MsdkV5LiveStreamApiContractTest {
    @Test
    fun defersAndThenReusesTheLiveStreamManagerForTheStreamLifetime() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()
        val start = source.substringAfter("override fun start").substringBefore("override fun stop")
        val stop = source.substringAfter("override fun stop")

        assertTrue(source.contains("private val managerProvider: () -> ILiveStreamManager"))
        assertTrue(source.contains("private val manager by lazy(managerProvider)"))
        assertFalse(start.contains("managerProvider()"))
        assertFalse(stop.contains("managerProvider()"))
    }

    @Test
    fun recordsSettingsAndInvocationSeparatelyWithoutChangingSdkOrdering() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()
        val start = source.substringAfter("override fun start").substringBefore("override fun stop")
        assertTrue(start.indexOf("setLiveVideoBitrateMode") < start.indexOf("LiveStreamDiagnosticKind.SETTINGS_APPLIED"))
        assertTrue(start.indexOf("LiveStreamDiagnosticKind.SETTINGS_APPLIED") < start.indexOf("manager.startStream"))
        assertTrue(start.contains("LiveStreamDiagnosticKind.START_INVOKED"))
        assertFalse(start.contains("record(url"))
    }

    @Test
    fun recordsReadOnlyCameraInputFactsAtTheThreeStreamingBoundaries() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("CameraInputCheckpoint.BEFORE_START"))
        assertTrue(source.contains("CameraInputCheckpoint.AFTER_START_INVOKED"))
        assertTrue(source.contains("CameraInputCheckpoint.FIRST_STATUS"))
        assertTrue(source.contains("LiveStreamDiagnosticKind.CAMERA_INPUT_SNAPSHOT"))
        assertFalse(source.contains("enableStream("))
        assertFalse(source.contains("setValue(modeKey"))
        assertFalse(source.contains("setValue(playbackKey"))
    }

    @Test
    fun logsOnlyTheFirstStartCompletionButPreservesEverySdkCallbackForThePort() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()
        val completion = source.substringAfter("private fun DjiLiveStreamCompletion.toSdkCompletion")
            .substringBefore("private fun record(")
        assertTrue(completion.contains("firstCompletion.compareAndSet(false, true)"))
        assertTrue(completion.contains("succeed()"))
        assertTrue(completion.contains("fail("))
    }

    @Test
    fun restoresTheHistoricalFullHdManualRtmpProfileWithoutOwningPreviewOrCameraMode() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
            Path("src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt"),
        ).first { it.exists() }.readText()
        val start = source.substringAfter("override fun start").substringBefore("override fun stop")
        val stop = source.substringAfter("override fun stop")
        assertTrue(start.contains("StreamQuality.FULL_HD"))
        assertTrue(start.contains("LiveVideoBitrateMode.MANUAL"))
        assertTrue(start.contains("setLiveVideoBitrate(MINI_4_PRO_FULL_HD_BITRATE_BPS)"))
        assertTrue(source.contains("const val MINI_4_PRO_FULL_HD_BITRATE_BPS: Int = 500 * 1024 * 8"))
        assertTrue(start.indexOf("setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)") < start.indexOf("setLiveStreamSettings("))
        assertTrue(start.indexOf("setLiveStreamSettings(") < start.indexOf("setLiveStreamQuality(StreamQuality.FULL_HD)"))
        assertTrue(start.indexOf("setLiveStreamQuality(StreamQuality.FULL_HD)") < start.indexOf("setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)"))
        assertTrue(start.indexOf("setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)") < start.indexOf("setLiveVideoBitrate(MINI_4_PRO_FULL_HD_BITRATE_BPS)"))
        assertTrue(start.indexOf("setLiveVideoBitrate(MINI_4_PRO_FULL_HD_BITRATE_BPS)") < start.indexOf("manager.addLiveStreamStatusListener(sdkListener)"))
        assertTrue(start.indexOf("manager.addLiveStreamStatusListener(sdkListener)") < start.indexOf("manager.startStream("))
        assertFalse(start.contains("setLiveStreamScaleType"))
        assertFalse(start.contains("StreamQuality.HD"))
        assertFalse(start.contains("StreamQuality.SD"))
        assertFalse(start.contains("StreamQuality.ORIGINAL"))
        assertFalse(start.contains("LiveVideoBitrateMode.AUTO"))
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
