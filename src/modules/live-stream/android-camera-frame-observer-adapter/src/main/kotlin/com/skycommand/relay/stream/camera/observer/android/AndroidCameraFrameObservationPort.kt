package com.skycommand.relay.stream.camera.observer.android

import com.skycommand.relay.stream.camera.observer.CameraFrameCodec
import com.skycommand.relay.stream.camera.observer.CameraFrameObservationPort
import com.skycommand.relay.stream.camera.observer.CameraFrameReceipt
import com.skycommand.relay.stream.camera.observer.CameraFrameReceiptListener
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.IdentityHashMap

internal data class AndroidCameraFrameInfo(
    val codec: CameraFrameCodec,
    val width: Int,
    val height: Int,
    val frameRate: Int,
)

internal fun interface AndroidCameraFrameListener {
    fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: AndroidCameraFrameInfo)
}

internal interface AndroidCameraFrameObservationPlatform {
    fun addReceiveStreamListener(listener: AndroidCameraFrameListener)

    fun removeReceiveStreamListener(listener: AndroidCameraFrameListener)
}

/**
 * MSDK boundary for production frame facts. It drops encoded bytes after validating their
 * declared range, so it cannot become a second media transport or a WebRTC input.
 */
class AndroidCameraFrameObservationPort internal constructor(
    private val platform: AndroidCameraFrameObservationPlatform,
) : CameraFrameObservationPort {
    private val lock = Any()
    private val listeners = IdentityHashMap<CameraFrameReceiptListener, AndroidCameraFrameListener>()

    override fun addReceiveStreamListener(listener: CameraFrameReceiptListener) {
        lateinit var platformListener: AndroidCameraFrameListener
        platformListener = AndroidCameraFrameListener { data, offset, length, info ->
            val active = synchronized(lock) { listeners[listener] === platformListener }
            if (!active || !validRange(data, offset, length)) return@AndroidCameraFrameListener
            listener.onFrame(CameraFrameReceipt(length, info.codec, info.width, info.height, info.frameRate))
        }
        synchronized(lock) {
            if (listeners.containsKey(listener)) return
            listeners[listener] = platformListener
        }
        try {
            platform.addReceiveStreamListener(platformListener)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (listeners[listener] === platformListener) listeners.remove(listener)
            }
            throw failure
        }
    }

    override fun removeReceiveStreamListener(listener: CameraFrameReceiptListener) {
        val platformListener = synchronized(lock) { listeners.remove(listener) } ?: return
        platform.removeReceiveStreamListener(platformListener)
    }

    companion object {
        fun create(): CameraFrameObservationPort = AndroidCameraFrameObservationPort(
            MsdkCameraFrameObservationPlatform(MediaDataCenter.getInstance().cameraStreamManager),
        )

        private fun validRange(data: ByteArray, offset: Int, length: Int): Boolean =
            offset >= 0 && length > 0 && offset <= data.size && length <= data.size - offset
    }
}

private class MsdkCameraFrameObservationPlatform(
    private val manager: ICameraStreamManager,
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
) : AndroidCameraFrameObservationPlatform {
    private val lock = Any()
    private val listeners = IdentityHashMap<AndroidCameraFrameListener, ICameraStreamManager.ReceiveStreamListener>()

    override fun addReceiveStreamListener(listener: AndroidCameraFrameListener) {
        lateinit var sdkListener: ICameraStreamManager.ReceiveStreamListener
        sdkListener = object : ICameraStreamManager.ReceiveStreamListener {
            override fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: StreamInfo) {
                listener.onReceiveStream(
                    data,
                    offset,
                    length,
                    AndroidCameraFrameInfo(
                        codec = info.mimeType.toCameraFrameCodec(),
                        width = info.width,
                        height = info.height,
                        frameRate = info.frameRate,
                    ),
                )
            }
        }
        synchronized(lock) {
            if (listeners.containsKey(listener)) return
            listeners[listener] = sdkListener
        }
        try {
            manager.addReceiveStreamListener(cameraIndex, sdkListener)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (listeners[listener] === sdkListener) listeners.remove(listener)
            }
            throw failure
        }
    }

    override fun removeReceiveStreamListener(listener: AndroidCameraFrameListener) {
        val sdkListener = synchronized(lock) { listeners.remove(listener) } ?: return
        manager.removeReceiveStreamListener(sdkListener)
    }
}

private fun ICameraStreamManager.MimeType?.toCameraFrameCodec(): CameraFrameCodec = when (this) {
    ICameraStreamManager.MimeType.H264 -> CameraFrameCodec.H264
    ICameraStreamManager.MimeType.H265 -> CameraFrameCodec.H265
    null -> CameraFrameCodec.UNKNOWN
    else -> CameraFrameCodec.UNKNOWN
}
