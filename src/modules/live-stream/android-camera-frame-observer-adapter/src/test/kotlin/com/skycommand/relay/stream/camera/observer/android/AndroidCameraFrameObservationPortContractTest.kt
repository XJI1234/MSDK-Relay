package com.skycommand.relay.stream.camera.observer.android

import com.skycommand.relay.stream.camera.observer.CameraFrameCodec
import com.skycommand.relay.stream.camera.observer.CameraFrameReceipt
import com.skycommand.relay.stream.camera.observer.CameraFrameReceiptListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidCameraFrameObservationPortContractTest {
    @Test
    fun preservesOnlyValidFrameSliceMetadataAndExactListenerIdentity() {
        val platform = Probe()
        val port = AndroidCameraFrameObservationPort(platform)
        var receipt: CameraFrameReceipt? = null
        val listener = CameraFrameReceiptListener { receipt = it }

        port.addReceiveStreamListener(listener)
        val data = byteArrayOf(9, 8, 7, 6)
        platform.listener!!.onReceiveStream(data, 1, 2, AndroidCameraFrameInfo(CameraFrameCodec.H264, 1920, 1080, 30))

        assertEquals(CameraFrameReceipt(2, CameraFrameCodec.H264, 1920, 1080, 30), receipt)
        port.removeReceiveStreamListener(listener)
        assertSame(platform.listener, platform.removed)
    }

    @Test
    fun ignoresMalformedSlicesAndDelayedCallbacksAfterRemoval() {
        val platform = Probe()
        val port = AndroidCameraFrameObservationPort(platform)
        var received = 0
        val listener = CameraFrameReceiptListener { received += 1 }

        port.addReceiveStreamListener(listener)
        val registered = requireNotNull(platform.listener)
        registered.onReceiveStream(byteArrayOf(1, 2), -1, 1, AndroidCameraFrameInfo(CameraFrameCodec.H264, 1, 1, 1))
        registered.onReceiveStream(byteArrayOf(1, 2), 1, 2, AndroidCameraFrameInfo(CameraFrameCodec.H264, 1, 1, 1))
        assertEquals(0, received)

        port.removeReceiveStreamListener(listener)
        registered.onReceiveStream(byteArrayOf(1, 2), 0, 1, AndroidCameraFrameInfo(CameraFrameCodec.H264, 1, 1, 1))
        assertEquals(0, received)
        assertNull(platform.listenerAfterRemoval)
    }

    private class Probe : AndroidCameraFrameObservationPlatform {
        var listener: AndroidCameraFrameListener? = null
        var removed: AndroidCameraFrameListener? = null
        val listenerAfterRemoval: AndroidCameraFrameListener? get() = listener?.takeIf { it !== removed }

        override fun addReceiveStreamListener(listener: AndroidCameraFrameListener) {
            this.listener = listener
        }

        override fun removeReceiveStreamListener(listener: AndroidCameraFrameListener) {
            removed = listener
        }
    }
}
