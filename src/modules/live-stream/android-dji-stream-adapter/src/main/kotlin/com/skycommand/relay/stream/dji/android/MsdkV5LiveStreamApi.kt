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
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.ILiveStreamManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class MsdkV5LiveStreamApi(
    private val diagnosticSink: LiveStreamDiagnosticSink = LiveStreamDiagnosticSink { },
) : DjiLiveStreamApi {
    private val manager: ILiveStreamManager = MediaDataCenter.getInstance().liveStreamManager
    private val cameraStreamManager: ICameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
    private val nextAttempt = AtomicLong()
    private val cameraInput = CameraInputDiagnostics(cameraStreamManager, diagnosticSink)

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
            manager.setLiveStreamQuality(StreamQuality.FULL_HD)
            manager.setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)
            manager.setLiveVideoBitrate(MINI_4_PRO_FULL_HD_BITRATE_BPS)
        } catch (failure: Throwable) {
            record(LiveStreamDiagnosticKind.SETTINGS_FAILED, attempt)
            throw failure
        }
        record(LiveStreamDiagnosticKind.SETTINGS_APPLIED, attempt)
        cameraInput.recordSnapshot(attempt, CameraInputCheckpoint.BEFORE_START)
        ListenerRegistry.put(listener, sdkListener, milestones)
        manager.addLiveStreamStatusListener(sdkListener)
        record(LiveStreamDiagnosticKind.START_INVOKED, attempt)
        cameraInput.recordSnapshot(attempt, CameraInputCheckpoint.AFTER_START_INVOKED)
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
            if (milestones.onStatus(status.isStreaming, status.fps, status.vbps)) {
                cameraInput.recordSnapshot(milestones.attempt, CameraInputCheckpoint.FIRST_STATUS)
            }
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

    private fun record(
        kind: LiveStreamDiagnosticKind,
        attempt: Long,
        fps: Int? = null,
        bitrateKbps: Int? = null,
        detail: String? = null,
    ) {
        runCatching { diagnosticSink.record(LiveStreamDiagnosticEvent(kind, attempt, fps, bitrateKbps, detail)) }
    }

    private object ListenerRegistry {
        private val values = java.util.IdentityHashMap<DjiLiveStreamListener, Pair<LiveStreamStatusListener, LiveStreamStatusMilestones>>()
        @Synchronized fun put(key: DjiLiveStreamListener, value: LiveStreamStatusListener, milestones: LiveStreamStatusMilestones) {
            values[key] = value to milestones
        }
        @Synchronized fun remove(key: DjiLiveStreamListener): Pair<LiveStreamStatusListener, LiveStreamStatusMilestones>? = values.remove(key)
    }

    private companion object {
        const val MINI_4_PRO_FULL_HD_BITRATE_BPS: Int = 500 * 1024 * 8
    }
}

private enum class CameraInputCheckpoint {
    BEFORE_START,
    AFTER_START_INVOKED,
    FIRST_STATUS,
}

private class CameraInputDiagnostics(
    private val manager: ICameraStreamManager,
    private val sink: LiveStreamDiagnosticSink,
) {
    private val available = java.util.concurrent.atomic.AtomicReference<Set<ComponentIndexType>?>(null)
    private val enabled = java.util.concurrent.atomic.AtomicReference<Map<ComponentIndexType, Boolean>?>(null)
    private val currentAttempt = AtomicLong()

    init {
        manager.addAvailableCameraUpdatedListener(object : ICameraStreamManager.AvailableCameraUpdatedListener {
            override fun onAvailableCameraUpdated(cameras: MutableList<ComponentIndexType>) {
                available.set(cameras.toSet())
                recordStateUpdate()
            }

            override fun onCameraStreamEnableUpdate(streams: MutableMap<ComponentIndexType, Boolean>) {
                enabled.set(streams.toMap())
                recordStateUpdate()
            }
        })
    }

    fun recordSnapshot(attempt: Long, checkpoint: CameraInputCheckpoint) {
        currentAttempt.set(attempt)
        record(LiveStreamDiagnosticKind.CAMERA_INPUT_SNAPSHOT, attempt, snapshot(checkpoint))
    }

    private fun recordStateUpdate() {
        record(LiveStreamDiagnosticKind.CAMERA_STREAM_STATE_UPDATED, currentAttempt.get(), snapshot(null))
    }

    private fun snapshot(checkpoint: CameraInputCheckpoint?): String {
        val main = ComponentIndexType.LEFT_OR_MAIN
        val availableCameras = available.get()
        val enabledStreams = enabled.get()
        val checkpointValue = checkpoint?.name?.let { "checkpoint=$it;" }.orEmpty()
        val encoderBitrate = runCatching { manager.getStreamEncoderBitrate(main) }.getOrNull()
        val priority = runCatching { manager.getStreamPriority(main) }.getOrNull()
        return "$checkpointValue" +
            "mainAvailable=${availableCameras?.contains(main) ?: "unknown"};" +
            "mainEnabled=${enabledStreams?.get(main) ?: "unknown"};" +
            "availableCount=${availableCameras?.size ?: -1};" +
            "encoderBitrate=${encoderBitrate ?: "unknown"};" +
            "streamPriority=${priority ?: "unknown"}"
    }

    private fun record(kind: LiveStreamDiagnosticKind, attempt: Long, detail: String) {
        runCatching { sink.record(LiveStreamDiagnosticEvent(kind, attempt, detail = detail)) }
    }
}
