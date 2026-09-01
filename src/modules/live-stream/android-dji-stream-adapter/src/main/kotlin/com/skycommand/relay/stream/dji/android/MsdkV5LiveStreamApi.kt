package com.skycommand.relay.stream.dji.android

import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import dji.v5.manager.interfaces.ILiveStreamManager

internal class MsdkV5LiveStreamApi(
    private val manager: ILiveStreamManager = MediaDataCenter.getInstance().liveStreamManager,
) : DjiLiveStreamApi {
    override fun start(url: String, listener: DjiLiveStreamListener, completion: DjiLiveStreamCompletion) {
        val sdkListener = listener.toSdkListener()
        manager.setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)
        manager.setLiveStreamSettings(
            LiveStreamSettings.Builder().setLiveStreamType(LiveStreamType.RTMP)
                .setRtmpSettings(RtmpSettings.Builder().setUrl(url).build()).build(),
        )
        // FULL_HD 在热点上易卡；固定 HD(720p)+手动码率，优先流畅，清晰度仍明显高于 SD/AUTO。
        manager.setLiveStreamQuality(StreamQuality.HD)
        manager.setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)
        manager.setLiveVideoBitrate(HD_BITRATE_BPS)
        ListenerRegistry.put(listener, sdkListener)
        manager.addLiveStreamStatusListener(sdkListener)
        manager.startStream(completion.toSdkCompletion())
    }

    override fun stop(completion: DjiLiveStreamCompletion) = manager.stopStream(completion.toSdkCompletion())

    override fun removeListener(listener: DjiLiveStreamListener) {
        ListenerRegistry.remove(listener)?.let(manager::removeLiveStreamStatusListener)
    }

    private fun DjiLiveStreamListener.toSdkListener() = object : LiveStreamStatusListener {
        override fun onLiveStreamStatusUpdate(status: LiveStreamStatus) {
            val resolution = status.resolution
            onStatus(
                DjiLiveStreamFact(
                    streaming = status.isStreaming,
                    width = resolution.width,
                    height = resolution.height,
                    fps = status.fps,
                    bitrateKbps = status.vbps,
                    rttMillis = status.rtt,
                    packetLoss = status.packetLoss,
                    packetCacheLength = status.packetCacheLen,
                ),
            )
        }
        override fun onError(error: IDJIError) = this@toSdkListener.onError()
    }

    private fun DjiLiveStreamCompletion.toSdkCompletion() = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() = succeed()
        override fun onFailure(error: IDJIError) = fail()
    }

    private object ListenerRegistry {
        private val values = java.util.IdentityHashMap<DjiLiveStreamListener, LiveStreamStatusListener>()
        @Synchronized fun put(key:DjiLiveStreamListener,value:LiveStreamStatusListener){values[key]=value}
        @Synchronized fun remove(key:DjiLiveStreamListener):LiveStreamStatusListener?=values.remove(key)
    }

    private companion object {
        /** DJI StreamQuality.HD 文档约 168 KByte/s；略抬到 220 保证细节，仍远低于 FULL_HD 峰值。 */
        const val HD_BITRATE_BPS: Int = 220 * 1024 * 8
    }
}
