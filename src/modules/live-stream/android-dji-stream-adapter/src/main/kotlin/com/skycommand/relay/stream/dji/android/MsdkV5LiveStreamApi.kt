package com.skycommand.relay.stream.dji.android

import com.skycommand.relay.stream.dji.StreamDjiFailure
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class MsdkV5LiveStreamApi(
    private val manager: ILiveStreamManager = MediaDataCenter.getInstance().liveStreamManager,
    private val diagnosticSink: LiveStreamDiagnosticSink = LiveStreamDiagnosticSink { },
) : DjiLiveStreamApi {
    private val nextAttempt = AtomicLong()

    override fun start(url: String, listener: DjiLiveStreamListener, completion: DjiLiveStreamCompletion) {
        val attempt = nextAttempt.incrementAndGet()
        val milestones = LiveStreamStatusMilestones(attempt, diagnosticSink)
        val sdkListener = listener.toSdkListener(milestones)
        try {
            manager.setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)
            manager.setLiveStreamSettings(
                LiveStreamSettings.Builder().setLiveStreamType(LiveStreamType.RTMP)
                    .setRtmpSettings(RtmpSettings.Builder().setUrl(url).build()).build(),
            )
            manager.setLiveStreamQuality(StreamQuality.HD)
            manager.setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)
            manager.setLiveVideoBitrate(MINI_4_PRO_HD_BITRATE_BPS)
        } catch (failure: Throwable) {
            record(LiveStreamDiagnosticKind.SETTINGS_FAILED, attempt)
            throw failure
        }
        record(LiveStreamDiagnosticKind.SETTINGS_APPLIED, attempt)
        ListenerRegistry.put(listener, sdkListener, milestones)
        manager.addLiveStreamStatusListener(sdkListener)
        record(LiveStreamDiagnosticKind.START_INVOKED, attempt)
        try {
            manager.startStream(completion.toSdkCompletion(attempt))
        } catch (failure: Throwable) {
            record(LiveStreamDiagnosticKind.START_INVOCATION_FAILED, attempt)
            throw failure
        }
    }

    override fun stop(completion: DjiLiveStreamCompletion) = manager.stopStream(completion.toSdkCompletion())

    override fun removeListener(listener: DjiLiveStreamListener) {
        ListenerRegistry.remove(listener)?.let { (sdkListener, milestones) ->
            milestones.deactivate()
            manager.removeLiveStreamStatusListener(sdkListener)
        }
    }

    private fun DjiLiveStreamListener.toSdkListener(milestones: LiveStreamStatusMilestones) = object : LiveStreamStatusListener {
        override fun onLiveStreamStatusUpdate(status: LiveStreamStatus) {
            milestones.onStatus(status.isStreaming, status.fps, status.vbps)
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
        override fun onError(error: IDJIError) {
            milestones.onError()
            this@toSdkListener.onError(
                StreamDjiFailure.fromDjiError(
                    runCatching { error.errorCode() }.getOrNull(),
                    runCatching { error.description() }.getOrNull(),
                ),
            )
        }
    }

    private fun DjiLiveStreamCompletion.toSdkCompletion(attempt: Long? = null) = object : CommonCallbacks.CompletionCallback {
        private val firstCompletion = AtomicBoolean()

        override fun onSuccess() {
            if (attempt != null && firstCompletion.compareAndSet(false, true)) {
                record(LiveStreamDiagnosticKind.START_CALLBACK_SUCCEEDED, attempt)
            }
            succeed()
        }
        override fun onFailure(error: IDJIError) {
            if (attempt != null && firstCompletion.compareAndSet(false, true)) {
                record(LiveStreamDiagnosticKind.START_CALLBACK_FAILED, attempt)
            }
            fail(
                StreamDjiFailure.fromDjiError(
                    runCatching { error.errorCode() }.getOrNull(),
                    runCatching { error.description() }.getOrNull(),
                ),
            )
        }
    }

    private fun record(kind: LiveStreamDiagnosticKind, attempt: Long, fps: Int? = null, bitrateKbps: Int? = null) {
        runCatching { diagnosticSink.record(LiveStreamDiagnosticEvent(kind, attempt, fps, bitrateKbps)) }
    }

    private object ListenerRegistry {
        private val values = java.util.IdentityHashMap<DjiLiveStreamListener, Pair<LiveStreamStatusListener, LiveStreamStatusMilestones>>()
        @Synchronized fun put(key: DjiLiveStreamListener, value: LiveStreamStatusListener, milestones: LiveStreamStatusMilestones) {
            values[key] = value to milestones
        }
        @Synchronized fun remove(key: DjiLiveStreamListener): Pair<LiveStreamStatusListener, LiveStreamStatusMilestones>? = values.remove(key)
    }

    private companion object {
        const val MINI_4_PRO_HD_BITRATE_BPS: Int = 220 * 1024 * 8
    }
}
