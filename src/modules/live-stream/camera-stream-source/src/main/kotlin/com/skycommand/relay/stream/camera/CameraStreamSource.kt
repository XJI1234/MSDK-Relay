package com.skycommand.relay.stream.camera

import com.skycommand.relay.stream.video.EncodedVideoFrame
import com.skycommand.relay.stream.video.EncodedVideoListener
import com.skycommand.relay.stream.video.EncodedVideoSource
import com.skycommand.relay.stream.video.EncodedVideoCodec
import com.skycommand.relay.stream.video.SourceFailure
import com.skycommand.relay.stream.video.SourceStartResult
import com.skycommand.relay.stream.video.SourceStopResult

enum class CameraStreamCodec {
    H264,
    H265,
    UNKNOWN,
}

data class CameraStreamInfo(
    val codec: CameraStreamCodec,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val presentationTimeMs: Long,
    val isKeyFrame: Boolean,
)

fun interface CameraStreamListener {
    fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: CameraStreamInfo)
}

interface CameraStreamApi {
    fun addReceiveStreamListener(listener: CameraStreamListener)

    fun removeReceiveStreamListener(listener: CameraStreamListener)
}

fun interface CameraStreamSourceDiagnosticSink {
    fun record(kind: CameraStreamSourceDiagnosticKind)
}

enum class CameraStreamSourceDiagnosticKind {
    UNSUPPORTED_CODEC,
    INVALID_FRAME,
    LISTENER_FAILURE,
    PLATFORM_FAILURE,
}

object CameraStreamSource {
    fun create(
        api: CameraStreamApi,
        diagnosticSink: CameraStreamSourceDiagnosticSink = CameraStreamSourceDiagnosticSink { },
    ): EncodedVideoSource = Source(api, diagnosticSink)

    private class Source(
        private val api: CameraStreamApi,
        private val diagnosticSink: CameraStreamSourceDiagnosticSink,
    ) : EncodedVideoSource {
        private val lock = Any()
        private var generation = 0L
        private var active: Active? = null

        override fun start(listener: EncodedVideoListener): SourceStartResult = start(listener) {}

        override fun start(listener: EncodedVideoListener, onFailure: (SourceFailure) -> Unit): SourceStartResult {
            val prepared = synchronized(lock) {
                if (active != null) {
                    null
                } else {
                    val next = Active(++generation, listener, onFailure)
                    next.platformListener = platformListener(next)
                    active = next
                    next
                }
            } ?: return SourceStartResult.AlreadyStarted

            try {
                api.addReceiveStreamListener(prepared.platformListener!!)
            } catch (_: Throwable) {
                synchronized(lock) { if (active === prepared) active = null }
                record(CameraStreamSourceDiagnosticKind.PLATFORM_FAILURE)
                return SourceStartResult.Failed(SourceFailure.PLATFORM_FAILURE)
            }
            if (!isActive(prepared)) {
                runCatching { api.removeReceiveStreamListener(prepared.platformListener!!) }
                return SourceStartResult.Failed(SourceFailure.PLATFORM_FAILURE)
            }
            return SourceStartResult.Started
        }

        override fun stop(): SourceStopResult {
            val prepared = synchronized(lock) {
                active.also { active = null }
            } ?: return SourceStopResult.AlreadyStopped

            return try {
                api.removeReceiveStreamListener(prepared.platformListener!!)
                SourceStopResult.Stopped
            } catch (_: Throwable) {
                record(CameraStreamSourceDiagnosticKind.PLATFORM_FAILURE)
                SourceStopResult.Failed(SourceFailure.PLATFORM_FAILURE)
            }
        }

        private fun platformListener(active: Active) = CameraStreamListener { data, offset, length, info ->
            val target = synchronized(lock) {
                this.active?.takeIf { it === active }?.listener
            } ?: return@CameraStreamListener

            if (info.codec != CameraStreamCodec.H264) {
                record(CameraStreamSourceDiagnosticKind.UNSUPPORTED_CODEC)
                val notify = synchronized(lock) {
                    this.active?.takeIf { it === active && !it.failureNotified }?.also { it.failureNotified = true }?.onFailure
                }
                if (notify != null) runCatching { notify(SourceFailure.UNSUPPORTED_CODEC) }
                return@CameraStreamListener
            }

            val frame = try {
                EncodedVideoFrame(
                    data = data,
                    offset = offset,
                    length = length,
                    width = info.width,
                    height = info.height,
                    frameRate = info.frameRate,
                    presentationTimeMs = info.presentationTimeMs,
                    isKeyFrame = info.isKeyFrame,
                    codec = EncodedVideoCodec.H264,
                )
            } catch (_: Throwable) {
                record(CameraStreamSourceDiagnosticKind.INVALID_FRAME)
                return@CameraStreamListener
            }

            try {
                target.onFrame(frame)
            } catch (_: Throwable) {
                record(CameraStreamSourceDiagnosticKind.LISTENER_FAILURE)
            }
        }

        private fun isActive(value: Active): Boolean = synchronized(lock) { active === value }

        private fun record(kind: CameraStreamSourceDiagnosticKind) {
            runCatching { diagnosticSink.record(kind) }
        }

        private class Active(
            val generation: Long,
            val listener: EncodedVideoListener,
            val onFailure: (SourceFailure) -> Unit,
            var platformListener: CameraStreamListener? = null,
            var failureNotified: Boolean = false,
        )
    }
}
