package com.skycommand.relay.stream.camera.android

import com.skycommand.relay.stream.camera.CameraStreamCodec
import com.skycommand.relay.stream.camera.CameraStreamInfo
import com.skycommand.relay.stream.camera.CameraStreamListener
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidCameraStreamApiContractTest {
    @Test
    fun defersResolvingTheCameraStreamManagerUntilTheRawStreamIsStarted() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/camera/android/AndroidCameraStreamApi.kt"),
            Path("src/modules/live-stream/android-camera-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/camera/android/AndroidCameraStreamApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("MsdkCameraStreamPlatform(managerProvider = { MediaDataCenter.getInstance().cameraStreamManager })"))
        assertTrue(source.contains("private val managerProvider: () -> ICameraStreamManager"))
        assertTrue(source.contains("private val manager by lazy(managerProvider)"))
    }

    @Test
    fun usesReceiveStreamListenerAndPreservesSdkMetadataAndArrayIdentity() {
        val probe = Probe()
        val api = AndroidCameraStreamApi(probe)
        var received: Received? = null
        val listener = CameraStreamListener { data, offset, length, info ->
            received = Received(data, offset, length, info)
        }

        api.addReceiveStreamListener(listener)
        val data = byteArrayOf(4, 3, 2, 1)
        val info = AndroidCameraStreamInfo(CameraStreamCodec.H264, 1920, 1080, 30, 77, true)
        probe.listener!!.onReceiveStream(data, 1, 2, info)

        val frame = requireNotNull(received)
        assertSame(data, frame.data)
        assertEquals(1, frame.offset)
        assertEquals(2, frame.length)
        assertEquals(CameraStreamInfo(CameraStreamCodec.H264, 1920, 1080, 30, 77, true), frame.info)

        api.removeReceiveStreamListener(listener)
        assertSame(probe.listener, probe.removedListener)
    }

    @Test
    fun mapsH265WithoutDecodingOrChangingTheCodecFact() {
        val probe = Probe()
        val api = AndroidCameraStreamApi(probe)
        var codec: CameraStreamCodec? = null
        api.addReceiveStreamListener(CameraStreamListener { _, _, _, info -> codec = info.codec })
        val info = AndroidCameraStreamInfo(CameraStreamCodec.H265, 1, 1, 1, 0, false)
        probe.listener!!.onReceiveStream(byteArrayOf(1), 0, 1, info)

        assertEquals(CameraStreamCodec.H265, codec)
    }

    private data class Received(
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val info: CameraStreamInfo,
    )

    private class Probe : AndroidCameraStreamPlatform {
        var listener: AndroidCameraStreamListener? = null
        var removedListener: AndroidCameraStreamListener? = null

        override fun addReceiveStreamListener(listener: AndroidCameraStreamListener) {
            this.listener = listener
        }

        override fun removeReceiveStreamListener(listener: AndroidCameraStreamListener) {
            removedListener = listener
        }
    }
}
