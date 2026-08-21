package com.skycommand.relay.stream.camera.android

import com.skycommand.relay.stream.camera.CameraStreamApi
import com.skycommand.relay.stream.camera.CameraStreamCodec
import com.skycommand.relay.stream.camera.CameraStreamInfo
import com.skycommand.relay.stream.camera.CameraStreamListener
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.IdentityHashMap

internal data class AndroidCameraStreamInfo(
    val codec: CameraStreamCodec,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val presentationTimeMs: Long,
    val isKeyFrame: Boolean,
)

internal fun interface AndroidCameraStreamListener {
    fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: AndroidCameraStreamInfo)
}

internal interface AndroidCameraStreamPlatform {
    fun addReceiveStreamListener(listener: AndroidCameraStreamListener)

    fun removeReceiveStreamListener(listener: AndroidCameraStreamListener)
}

class AndroidCameraStreamApi internal constructor(
    private val platform: AndroidCameraStreamPlatform,
) : CameraStreamApi {
    private val lock = Any()
    private val listeners = IdentityHashMap<CameraStreamListener, AndroidCameraStreamListener>()

    override fun addReceiveStreamListener(listener: CameraStreamListener) {
        lateinit var platformListener: AndroidCameraStreamListener
        platformListener = AndroidCameraStreamListener { data, offset, length, info ->
            val active = synchronized(lock) { listeners[listener] === platformListener }
            if (!active) return@AndroidCameraStreamListener
            listener.onReceiveStream(
                data,
                offset,
                length,
                CameraStreamInfo(
                    codec = info.codec,
                    width = info.width,
                    height = info.height,
                    frameRate = info.frameRate,
                    presentationTimeMs = info.presentationTimeMs,
                    isKeyFrame = info.isKeyFrame,
                ),
            )
        }

        synchronized(lock) {
            if (listeners.containsKey(listener)) return
            listeners[listener] = platformListener
        }
        try {
            platform.addReceiveStreamListener(platformListener)
        } catch (throwable: Throwable) {
            synchronized(lock) { if (listeners[listener] === platformListener) listeners.remove(listener) }
            throw throwable
        }
    }

    override fun removeReceiveStreamListener(listener: CameraStreamListener) {
        val platformListener = synchronized(lock) { listeners.remove(listener) } ?: return
        platform.removeReceiveStreamListener(platformListener)
    }

    companion object {
        fun create(): CameraStreamApi = AndroidCameraStreamApi(
            MsdkCameraStreamPlatform(MediaDataCenter.getInstance().cameraStreamManager),
        )
    }
}

private class MsdkCameraStreamPlatform(
    private val manager: ICameraStreamManager,
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
) : AndroidCameraStreamPlatform {
    private val lock = Any()
    private val listeners = IdentityHashMap<AndroidCameraStreamListener, ICameraStreamManager.ReceiveStreamListener>()

    override fun addReceiveStreamListener(listener: AndroidCameraStreamListener) {
        lateinit var sdkListener: ICameraStreamManager.ReceiveStreamListener
        sdkListener = object : ICameraStreamManager.ReceiveStreamListener {
            override fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: StreamInfo) {
                listener.onReceiveStream(
                    data,
                    offset,
                    length,
                    AndroidCameraStreamInfo(
                        codec = info.mimeType.toCameraStreamCodec(),
                        width = info.width,
                        height = info.height,
                        frameRate = info.frameRate,
                        presentationTimeMs = info.presentationTimeMs,
                        isKeyFrame = info.isKeyFrame,
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
        } catch (throwable: Throwable) {
            synchronized(lock) { if (listeners[listener] === sdkListener) listeners.remove(listener) }
            throw throwable
        }
    }

    override fun removeReceiveStreamListener(listener: AndroidCameraStreamListener) {
        val sdkListener = synchronized(lock) { listeners.remove(listener) } ?: return
        manager.removeReceiveStreamListener(sdkListener)
    }
}

private fun ICameraStreamManager.MimeType?.toCameraStreamCodec(): CameraStreamCodec = when (this) {
    ICameraStreamManager.MimeType.H264 -> CameraStreamCodec.H264
    ICameraStreamManager.MimeType.H265 -> CameraStreamCodec.H265
    null -> CameraStreamCodec.UNKNOWN
    else -> CameraStreamCodec.UNKNOWN
}
