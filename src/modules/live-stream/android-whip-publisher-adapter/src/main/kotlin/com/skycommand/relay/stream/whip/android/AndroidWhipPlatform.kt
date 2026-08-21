package com.skycommand.relay.stream.whip.android

import android.content.Context
import com.skycommand.relay.stream.video.EncodedVideoFrame
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.EncodedImage
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.VideoCodecStatus
import java.util.concurrent.TimeUnit

internal object AndroidWebRtcSessionFactory {
    fun create(context: Context): AndroidWhipWebRtcFactory =
        Factory(context.applicationContext ?: context)

    private class Factory(
        private val context: Context,
    ) : AndroidWhipWebRtcFactory {
        override val encodedH264Available: Boolean = true

        override fun create(): AndroidWhipWebRtcSession {
            initializeWebRtc(context)
            val encoderFactory = PassthroughH264EncoderFactory()
            val peerFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .createPeerConnectionFactory()
            return WebRtcSession(peerFactory, encoderFactory)
        }
    }
}

internal object AndroidWhipExecutors {
    fun createWorker() = Executors.newSingleThreadExecutor(named("sky-whip-worker"))

    fun createScheduler() = Executors.newSingleThreadScheduledExecutor(named("sky-whip-timeout"))

    private fun named(prefix: String) = ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }

    private val threadIds = AtomicInteger()
}

private val webRtcInitializationLock = Any()
private var webRtcInitialized = false

private fun initializeWebRtc(context: Context) {
    synchronized(webRtcInitializationLock) {
        if (webRtcInitialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        webRtcInitialized = true
    }
}

private class WebRtcSession(
    private val factory: PeerConnectionFactory,
    private val encoderFactory: PassthroughH264EncoderFactory,
) : AndroidWhipWebRtcSession {
    private val lock = Any()
    private var listener: AndroidWhipWebRtcListener? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var sender: RtpSender? = null
    private var localDescriptionSet = false
    private var gatheringComplete = false
    private var offerSent = false
    private var connectedNotified = false
    private var closed = false

    override fun start(listener: AndroidWhipWebRtcListener) {
        synchronized(lock) {
            check(!closed) { "WebRTC session is closed" }
            check(this.listener == null) { "WebRTC session is already started" }
            this.listener = listener
        }

        try {
            val peer = factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()),
                Observer(),
            ) ?: throw IllegalStateException("PeerConnection creation failed")
            val source = factory.createVideoSource(true, false)
            val track = factory.createVideoTrack("dji-h264", source)
            val transceiver = peer.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    listOf("dji-camera"),
                ),
            )
            synchronized(lock) {
                if (closed) {
                    releaseResources(peer, source, track, transceiver.sender)
                    return
                }
                peerConnection = peer
                videoSource = source
                videoTrack = track
                sender = transceiver.sender
            }
            source.capturerObserver.onCapturerStarted(true)
            peer.createOffer(
                object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        peer.setLocalDescription(
                            object : SdpObserver {
                                override fun onSetSuccess() {
                                    synchronized(lock) { localDescriptionSet = true }
                                    maybePublishOffer()
                                }

                                override fun onSetFailure(error: String) {
                                    fail(AndroidWhipPlatformFailure.SIGNALING)
                                }

                                override fun onCreateSuccess(description: SessionDescription) = Unit

                                override fun onCreateFailure(error: String) = Unit
                            },
                            description,
                        )
                    }

                    override fun onCreateFailure(error: String) {
                        fail(AndroidWhipPlatformFailure.SIGNALING)
                    }

                    override fun onSetSuccess() = Unit

                    override fun onSetFailure(error: String) = Unit
                },
                MediaConstraints(),
            )
        } catch (_: Throwable) {
            fail(AndroidWhipPlatformFailure.INTERNAL)
        }
    }

    override fun setRemoteAnswer(answerSdp: String) {
        val peer = synchronized(lock) {
            if (closed) return
            peerConnection
        } ?: return
        try {
            peer.setRemoteDescription(
                object : SdpObserver {
                    override fun onSetSuccess() = Unit

                    override fun onSetFailure(error: String) {
                        fail(AndroidWhipPlatformFailure.SIGNALING)
                    }

                    override fun onCreateSuccess(description: SessionDescription) = Unit

                    override fun onCreateFailure(error: String) = Unit
                },
                SessionDescription(SessionDescription.Type.ANSWER, answerSdp),
            )
        } catch (_: Throwable) {
            fail(AndroidWhipPlatformFailure.SIGNALING)
        }
    }

    override fun send(frame: EncodedVideoFrame): AndroidWhipSendResult {
        val source = synchronized(lock) {
            if (closed || !connectedNotified) return AndroidWhipSendResult.NotConnected
            videoSource
        } ?: return AndroidWhipSendResult.NotConnected
        val encoder = encoderFactory.currentEncoder()
            ?: return AndroidWhipSendResult.NotConnected
        if (!encoder.enqueue(frame)) return AndroidWhipSendResult.Backpressured
        return try {
            source.capturerObserver.onFrameCaptured(
                VideoFrame(
                    PlaceholderBuffer(frame.width, frame.height),
                    0,
                    TimeUnit.MILLISECONDS.toNanos(frame.presentationTimeMs),
                ),
            )
            AndroidWhipSendResult.Accepted
        } catch (_: Throwable) {
            AndroidWhipSendResult.Failed(AndroidWhipPlatformFailure.INTERNAL)
        }
    }

    override fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true
            val result = Resources(peerConnection, videoSource, videoTrack, sender)
            peerConnection = null
            videoSource = null
            videoTrack = null
            sender = null
            result
        }
        releaseResources(resources.peer, resources.source, resources.track, resources.sender)
        runCatching { factory.dispose() }
        encoderFactory.release()
    }

    private fun maybePublishOffer() {
        val description = synchronized(lock) {
            if (closed || !localDescriptionSet || !gatheringComplete || offerSent) return
            offerSent = true
            peerConnection?.localDescription?.description
        } ?: return
        notify { it.onOfferReady(description) }
    }

    private fun fail(reason: AndroidWhipPlatformFailure) {
        val target = synchronized(lock) {
            if (closed) return
            listener
        } ?: return
        notify { target.onFailed(reason) }
    }

    private fun connected() {
        val notify = synchronized(lock) {
            if (closed || connectedNotified) false else {
                connectedNotified = true
                true
            }
        }
        if (notify) notify { it.onConnected() }
    }

    private fun disconnected() {
        val notify = synchronized(lock) { !closed }
        if (notify) notify { it.onDisconnected() }
    }

    private fun notify(action: (AndroidWhipWebRtcListener) -> Unit) {
        val target = synchronized(lock) { listener.takeUnless { closed } } ?: return
        runCatching { action(target) }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> connected()

                PeerConnection.IceConnectionState.FAILED -> fail(AndroidWhipPlatformFailure.ICE)
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.CLOSED,
                -> disconnected()

                PeerConnection.IceConnectionState.NEW,
                PeerConnection.IceConnectionState.CHECKING,
                -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                synchronized(lock) { gatheringComplete = true }
                maybePublishOffer()
            }
        }

        override fun onIceCandidate(candidate: org.webrtc.IceCandidate) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<org.webrtc.IceCandidate>) = Unit

        override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> connected()
                PeerConnection.PeerConnectionState.FAILED -> fail(AndroidWhipPlatformFailure.ICE)
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> disconnected()

                PeerConnection.PeerConnectionState.NEW,
                PeerConnection.PeerConnectionState.CONNECTING,
                -> Unit
            }
        }
    }

    private data class Resources(
        val peer: PeerConnection?,
        val source: VideoSource?,
        val track: VideoTrack?,
        val sender: RtpSender?,
    )

    private fun releaseResources(
        peer: PeerConnection?,
        source: VideoSource?,
        track: VideoTrack?,
        sender: RtpSender?,
    ) {
        runCatching { source?.capturerObserver?.onCapturerStopped() }
        runCatching { peer?.close() }
        runCatching { sender?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { peer?.dispose() }
    }
}

private class PassthroughH264EncoderFactory : VideoEncoderFactory {
    private val encoder = AtomicReference<PassthroughH264Encoder?>()

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (!info.name.equals("H264", ignoreCase = true)) return null
        return PassthroughH264Encoder().also { encoder.set(it) }
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(
        VideoCodecInfo(
            "H264",
            mapOf(
                VideoCodecInfo.H264_FMTP_PROFILE_LEVEL_ID to "42e01f",
                VideoCodecInfo.H264_FMTP_LEVEL_ASYMMETRY_ALLOWED to "1",
                VideoCodecInfo.H264_FMTP_PACKETIZATION_MODE to "1",
            ),
        ),
    )

    fun currentEncoder(): PassthroughH264Encoder? = encoder.get()

    fun release() {
        encoder.getAndSet(null)?.release()
    }
}

private class PassthroughH264Encoder : VideoEncoder {
    private val frames = ArrayDeque<EncodedVideoFrame>()
    private val lock = Any()
    private var callback: VideoEncoder.Callback? = null
    private var released = false

    fun enqueue(frame: EncodedVideoFrame): Boolean = synchronized(lock) {
        if (released || frames.size >= 4) return false
        frames.addLast(frame)
        true
    }

    override fun initEncode(
        settings: VideoEncoder.Settings,
        encodeCallback: VideoEncoder.Callback,
    ): VideoCodecStatus = synchronized(lock) {
        if (released) VideoCodecStatus.UNINITIALIZED else {
            callback = encodeCallback
            VideoCodecStatus.OK
        }
    }

    override fun release(): VideoCodecStatus {
        synchronized(lock) {
            released = true
            callback = null
            frames.clear()
        }
        return VideoCodecStatus.OK
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val encoded = synchronized(lock) {
            if (released) return VideoCodecStatus.UNINITIALIZED
            if (frames.isEmpty()) null else frames.removeFirst()
        } ?: return VideoCodecStatus.NO_OUTPUT
        val target = synchronized(lock) { callback } ?: return VideoCodecStatus.UNINITIALIZED
        val buffer = ByteBuffer.allocateDirect(encoded.length)
        buffer.put(encoded.data, encoded.offset, encoded.length)
        buffer.flip()
        val image = EncodedImage.builder()
            .setBuffer(buffer) { }
            .setEncodedWidth(encoded.width)
            .setEncodedHeight(encoded.height)
            .setCaptureTimeNs(TimeUnit.MILLISECONDS.toNanos(encoded.presentationTimeMs))
            .setFrameType(
                if (encoded.isKeyFrame) EncodedImage.FrameType.VideoFrameKey
                else EncodedImage.FrameType.VideoFrameDelta,
            )
            .createEncodedImage()
        target.onEncodedFrame(image, VideoEncoder.CodecSpecificInfoH264())
        return VideoCodecStatus.OK
    }

    override fun setRateAllocation(
        allocation: VideoEncoder.BitrateAllocation,
        framerate: Int,
    ): VideoCodecStatus = VideoCodecStatus.OK

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF

    override fun getImplementationName(): String = "sky-command-dji-h264-passthrough"
}

private class PlaceholderBuffer(
    private val width: Int,
    private val height: Int,
) : VideoFrame.Buffer {
    private val references = AtomicInteger(1)

    override fun getWidth(): Int = width

    override fun getHeight(): Int = height

    override fun toI420(): VideoFrame.I420Buffer? = null

    override fun retain() {
        references.incrementAndGet()
    }

    override fun release() {
        references.decrementAndGet()
    }

    override fun cropAndScale(
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int,
        scaleWidth: Int,
        scaleHeight: Int,
    ): VideoFrame.Buffer = PlaceholderBuffer(scaleWidth, scaleHeight)
}

internal class OkHttpWhipClient private constructor(
    private val client: OkHttpClient,
) : AndroidWhipHttpClient {
    override fun postOffer(
        config: com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig,
        offerSdp: String,
    ): AndroidWhipHttpResult {
        val request = Request.Builder()
            .url(config.whipUrl)
            .header("Accept", "application/sdp")
            .post(offerSdp.toRequestBody("application/sdp".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return AndroidWhipHttpResult.Rejected
                val answer = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: return AndroidWhipHttpResult.Rejected
                AndroidWhipHttpResult.Answer(answer)
            }
        } catch (_: SocketTimeoutException) {
            AndroidWhipHttpResult.Failed(AndroidWhipPlatformFailure.TIMEOUT)
        } catch (_: IOException) {
            AndroidWhipHttpResult.Failed(AndroidWhipPlatformFailure.NETWORK)
        } catch (_: Throwable) {
            AndroidWhipHttpResult.Failed(AndroidWhipPlatformFailure.SIGNALING)
        }
    }

    companion object {
        fun create(options: AndroidWhipTransportOptions): AndroidWhipHttpClient =
            OkHttpWhipClient(
                OkHttpClient.Builder()
                    .connectTimeout(options.signalingTimeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(options.signalingTimeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(options.signalingTimeoutMs, TimeUnit.MILLISECONDS)
                    .build(),
            )
    }
}
