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
        // 试验档：固定 Full HD + 手动码率，便于实机对比清晰度、延迟和稳定性。
        manager.setLiveStreamQuality(StreamQuality.FULL_HD)
        manager.setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)
        manager.setLiveVideoBitrate(FULL_HD_BITRATE_BPS)
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
            onStatus(DjiLiveStreamFact(status.isStreaming, resolution.width, resolution.height, status.fps, status.vbps, status.rtt))
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
        /** 约 4 Mbit/s，作为 Full HD 图传的保守实机试验档。 */
        const val FULL_HD_BITRATE_BPS: Int = 500 * 1024 * 8
    }
}
