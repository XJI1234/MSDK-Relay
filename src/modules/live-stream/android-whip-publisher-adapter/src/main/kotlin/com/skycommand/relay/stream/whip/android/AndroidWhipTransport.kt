package com.skycommand.relay.stream.whip.android

import android.content.Context
import com.skycommand.relay.stream.video.EncodedVideoFrame
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import com.skycommand.relay.stream.whip.config.WhipConfigValidationResult
import com.skycommand.relay.stream.whip.config.WhipStreamConfigValidator
import com.skycommand.relay.stream.whip.publisher.WhipTransport
import com.skycommand.relay.stream.whip.publisher.WhipTransportCloseResult
import com.skycommand.relay.stream.whip.publisher.WhipTransportFailure
import com.skycommand.relay.stream.whip.publisher.WhipTransportListener
import com.skycommand.relay.stream.whip.publisher.WhipTransportOpenResult
import com.skycommand.relay.stream.whip.publisher.WhipTransportRejection
import com.skycommand.relay.stream.whip.publisher.WhipTransportSendResult
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class AndroidWhipTransportOptions(
    val queueCapacity: Int = 3,
    val signalingTimeoutMs: Long = 15_000,
) {
    init {
        require(queueCapacity in 1..8) { "queueCapacity must be between 1 and 8" }
        require(signalingTimeoutMs in 1_000..15_000) {
            "signalingTimeoutMs must be between 1000 and 15000"
        }
    }
}

internal data class AndroidWhipTransportDependencies(
    val webRtc: AndroidWhipWebRtcFactory,
    val http: AndroidWhipHttpClient,
    val executor: Executor,
    val scheduler: ScheduledExecutorService,
    val options: AndroidWhipTransportOptions = AndroidWhipTransportOptions(),
)

internal interface AndroidWhipWebRtcFactory {
    val encodedH264Available: Boolean

    fun create(): AndroidWhipWebRtcSession
}

internal interface AndroidWhipWebRtcSession {
    fun start(listener: AndroidWhipWebRtcListener)

    fun setRemoteAnswer(answerSdp: String)

    fun send(frame: EncodedVideoFrame): AndroidWhipSendResult

    fun close()
}

internal interface AndroidWhipWebRtcListener {
    fun onOfferReady(offerSdp: String)

    fun onConnected()

    fun onFailed(reason: AndroidWhipPlatformFailure)

    fun onDisconnected()
}

internal enum class AndroidWhipPlatformFailure {
    SIGNALING,
    ICE,
    NETWORK,
    TIMEOUT,
    INTERNAL,
}

internal sealed interface AndroidWhipSendResult {
    data object Accepted : AndroidWhipSendResult

    data object Dropped : AndroidWhipSendResult

    data object Backpressured : AndroidWhipSendResult

    data object NotConnected : AndroidWhipSendResult

    data class Failed(val reason: AndroidWhipPlatformFailure) : AndroidWhipSendResult
}

internal interface AndroidWhipHttpClient {
    fun postOffer(config: ValidatedWhipStreamConfig, offerSdp: String): AndroidWhipHttpResult
}

internal sealed interface AndroidWhipHttpResult {
    data class Answer(val sdp: String) : AndroidWhipHttpResult

    data object Rejected : AndroidWhipHttpResult

    data class Failed(val reason: AndroidWhipPlatformFailure) : AndroidWhipHttpResult
}

class AndroidWhipTransport private constructor(
    private val dependencies: AndroidWhipTransportDependencies,
) : WhipTransport {
    private val lock = Any()
    private var nextGeneration = 0L
    private var active: Session? = null

    override fun open(
        config: ValidatedWhipStreamConfig,
        listener: WhipTransportListener,
    ): WhipTransportOpenResult {
        val validated = when (WhipStreamConfigValidator.validate(config.whipUrl)) {
            is WhipConfigValidationResult.Valid -> config
            is WhipConfigValidationResult.Invalid ->
                return WhipTransportOpenResult.Rejected(WhipTransportRejection.INVALID_CONFIGURATION)
        }
        if (!dependencies.webRtc.encodedH264Available) {
            return WhipTransportOpenResult.Rejected(WhipTransportRejection.ENCODED_H264_UNAVAILABLE)
        }

        val session = synchronized(lock) {
            if (active != null) return WhipTransportOpenResult.Rejected(WhipTransportRejection.INTERNAL)
            Session(++nextGeneration, validated, listener)
                .also { active = it }
        }

        return try {
            dependencies.executor.execute { start(session) }
            WhipTransportOpenResult.Accepted
        } catch (_: Throwable) {
            synchronized(lock) { if (active === session) active = null }
            WhipTransportOpenResult.Rejected(WhipTransportRejection.INTERNAL)
        }
    }

    override fun send(frame: EncodedVideoFrame): WhipTransportSendResult {
        val session = synchronized(lock) {
            active?.takeIf { it.state == SessionState.CONNECTED }
                ?: return WhipTransportSendResult.NotConnected
        }
        synchronized(lock) {
            if (active !== session || session.state != SessionState.CONNECTED) {
                return WhipTransportSendResult.NotConnected
            }
            if (!offerQueueSlot(session, frame)) {
                return WhipTransportSendResult.Backpressured
            }
        }

        val copied = normalizeFrame(frame) ?: return WhipTransportSendResult.Dropped
        val shouldSchedule = synchronized(lock) {
            if (active !== session || session.state != SessionState.CONNECTED) {
                false
            } else if (!offerQueueSlot(session, copied)) {
                return WhipTransportSendResult.Backpressured
            } else {
                session.frames.addLast(copied)
                if (session.drainScheduled) false else {
                    session.drainScheduled = true
                    true
                }
            }
        }
        if (!isCurrent(session)) return WhipTransportSendResult.NotConnected
        if (shouldSchedule) {
            try {
                dependencies.executor.execute { drain(session) }
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (active === session) {
                        session.frames.removeLastOccurrence(copied)
                        session.drainScheduled = false
                    }
                }
                fail(session, AndroidWhipPlatformFailure.INTERNAL)
                return WhipTransportSendResult.Failed(WhipTransportFailure.INTERNAL)
            }
        }
        return WhipTransportSendResult.Accepted
    }

    override fun close(): WhipTransportCloseResult {
        val session = synchronized(lock) {
            val current = active ?: return WhipTransportCloseResult.AlreadyClosed
            active = null
            current.state = SessionState.CLOSING
            current.frames.clear()
            current.timeout?.cancel(false)
            current
        }
        return try {
            session.platform?.close()
            WhipTransportCloseResult.Closed
        } catch (_: Throwable) {
            WhipTransportCloseResult.Failed(WhipTransportFailure.INTERNAL)
        }
    }

    private fun offerQueueSlot(session: Session, incoming: EncodedVideoFrame): Boolean {
        if (session.frames.size < dependencies.options.queueCapacity) return true
        if (!incoming.isKeyFrame) return false
        val iterator = session.frames.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().isKeyFrame) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    private fun start(session: Session) {
        if (!isCurrent(session)) return
        val platform = try {
            dependencies.webRtc.create()
        } catch (_: UnsupportedOperationException) {
            fail(session, AndroidWhipPlatformFailure.INTERNAL)
            return
        } catch (_: Throwable) {
            fail(session, AndroidWhipPlatformFailure.INTERNAL)
            return
        }
        synchronized(lock) {
            if (active !== session) {
                runCatching { platform.close() }
                return
            }
            session.platform = platform
            session.timeout = try {
                dependencies.scheduler.schedule(
                    { fail(session, AndroidWhipPlatformFailure.TIMEOUT) },
                    dependencies.options.signalingTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: Throwable) {
                null
            }
        }
        if (!isCurrent(session)) return
        try {
            platform.start(platformListener(session))
        } catch (_: Throwable) {
            fail(session, AndroidWhipPlatformFailure.INTERNAL)
        }
    }

    private fun platformListener(session: Session) = object : AndroidWhipWebRtcListener {
        override fun onOfferReady(offerSdp: String) {
            if (offerSdp.isBlank() || !markOfferSeen(session)) return
            try {
                dependencies.executor.execute {
                    if (!isCurrent(session)) return@execute
                    val result = try {
                        dependencies.http.postOffer(session.config, offerSdp)
                    } catch (_: Throwable) {
                        AndroidWhipHttpResult.Failed(AndroidWhipPlatformFailure.NETWORK)
                    }
                    when (result) {
                        is AndroidWhipHttpResult.Answer -> applyAnswer(session, result.sdp)
                        AndroidWhipHttpResult.Rejected -> fail(session, AndroidWhipPlatformFailure.SIGNALING)
                        is AndroidWhipHttpResult.Failed -> fail(session, result.reason)
                    }
                }
            } catch (_: Throwable) {
                fail(session, AndroidWhipPlatformFailure.INTERNAL)
            }
        }

        override fun onConnected() {
            val notify = synchronized(lock) {
                if (active !== session || session.state != SessionState.OPENING || !session.answerApplied) {
                    false
                } else {
                    session.state = SessionState.CONNECTED
                    session.timeout?.cancel(false)
                    session.timeout = null
                    true
                }
            }
            if (notify) notifyConnected(session.listener)
        }

        override fun onFailed(reason: AndroidWhipPlatformFailure) {
            fail(session, reason)
        }

        override fun onDisconnected() {
            val listener = detach(session)
            if (listener != null) notifyDisconnected(listener)
        }
    }

    private fun applyAnswer(session: Session, answerSdp: String) {
        if (answerSdp.isBlank() || !isCurrent(session)) {
            if (answerSdp.isBlank()) fail(session, AndroidWhipPlatformFailure.SIGNALING)
            return
        }
        val platform = synchronized(lock) {
            if (active !== session || session.state != SessionState.OPENING) {
                null
            } else {
                session.answerApplied = true
                session.platform
            }
        } ?: return
        try {
            platform.setRemoteAnswer(answerSdp)
        } catch (_: Throwable) {
            fail(session, AndroidWhipPlatformFailure.SIGNALING)
        }
    }

    private fun drain(session: Session) {
        while (true) {
            val frame = synchronized(lock) {
                if (active !== session || session.state != SessionState.CONNECTED) {
                    session.frames.clear()
                    session.drainScheduled = false
                    null
                } else if (session.frames.isEmpty()) {
                    session.drainScheduled = false
                    null
                } else {
                    session.frames.removeFirst()
                }
            }
            if (frame == null) return
            val result = try {
                session.platform?.send(frame) ?: AndroidWhipSendResult.NotConnected
            } catch (_: Throwable) {
                AndroidWhipSendResult.Failed(AndroidWhipPlatformFailure.INTERNAL)
            }
            when (result) {
                AndroidWhipSendResult.Accepted,
                AndroidWhipSendResult.Dropped,
                AndroidWhipSendResult.Backpressured,
                -> Unit

                AndroidWhipSendResult.NotConnected -> {
                    val listener = detach(session)
                    if (listener != null) notifyDisconnected(listener)
                    return
                }

                is AndroidWhipSendResult.Failed -> {
                    fail(session, result.reason)
                    return
                }
            }
        }
    }

    private fun fail(session: Session, reason: AndroidWhipPlatformFailure) {
        val listener = detach(session) ?: return
        notifyFailed(listener, reason.toTransportFailure())
    }

    private fun detach(session: Session): WhipTransportListener? {
        val listener = synchronized(lock) {
            if (active !== session) return null
            active = null
            session.state = SessionState.CLOSING
            session.frames.clear()
            session.drainScheduled = false
            session.timeout?.cancel(false)
            session.timeout = null
            session.listener
        }
        runCatching { session.platform?.close() }
        return listener
    }

    private fun markOfferSeen(session: Session): Boolean = synchronized(lock) {
        if (active !== session || session.state != SessionState.OPENING || session.offerSeen) {
            false
        } else {
            session.offerSeen = true
            true
        }
    }

    private fun isCurrent(session: Session): Boolean = synchronized(lock) { active === session }

    private fun notifyConnected(listener: WhipTransportListener) {
        runCatching { listener.onConnected() }
    }

    private fun notifyFailed(listener: WhipTransportListener, reason: WhipTransportFailure) {
        runCatching { listener.onFailed(reason) }
    }

    private fun notifyDisconnected(listener: WhipTransportListener) {
        runCatching { listener.onDisconnected() }
    }

    private class Session(
        val generation: Long,
        val config: ValidatedWhipStreamConfig,
        val listener: WhipTransportListener,
        val frames: ArrayDeque<EncodedVideoFrame> = ArrayDeque(),
        var platform: AndroidWhipWebRtcSession? = null,
        var state: SessionState = SessionState.OPENING,
        var offerSeen: Boolean = false,
        var answerApplied: Boolean = false,
        var drainScheduled: Boolean = false,
        var timeout: ScheduledFuture<*>? = null,
    )

    private enum class SessionState {
        OPENING,
        CONNECTED,
        CLOSING,
    }

    companion object {
        fun create(context: Context, options: AndroidWhipTransportOptions = AndroidWhipTransportOptions()): WhipTransport =
            createForTest(
                AndroidWhipTransportDependencies(
                    webRtc = AndroidWebRtcSessionFactory.create(context),
                    http = OkHttpWhipClient.create(options),
                    executor = AndroidWhipExecutors.createWorker(),
                    scheduler = AndroidWhipExecutors.createScheduler(),
                    options = options,
                ),
            )

        internal fun createForTest(dependencies: AndroidWhipTransportDependencies): AndroidWhipTransport =
            AndroidWhipTransport(dependencies)
    }
}

private fun AndroidWhipPlatformFailure.toTransportFailure(): WhipTransportFailure = when (this) {
    AndroidWhipPlatformFailure.SIGNALING -> WhipTransportFailure.SIGNALING
    AndroidWhipPlatformFailure.ICE -> WhipTransportFailure.ICE
    AndroidWhipPlatformFailure.NETWORK -> WhipTransportFailure.NETWORK
    AndroidWhipPlatformFailure.TIMEOUT -> WhipTransportFailure.TIMEOUT
    AndroidWhipPlatformFailure.INTERNAL -> WhipTransportFailure.INTERNAL
}

private fun normalizeFrame(frame: EncodedVideoFrame): EncodedVideoFrame? {
    val source = frame.data.copyOfRange(frame.offset, frame.offset + frame.length)
    val normalized = when {
        hasAnnexBStartCode(source) -> source
        else -> avccToAnnexB(source) ?: return null
    }
    return EncodedVideoFrame(
        data = normalized,
        offset = 0,
        length = normalized.size,
        width = frame.width,
        height = frame.height,
        frameRate = frame.frameRate,
        presentationTimeMs = frame.presentationTimeMs,
        isKeyFrame = frame.isKeyFrame,
        codec = frame.codec,
    )
}

private fun hasAnnexBStartCode(data: ByteArray): Boolean {
    var index = 0
    while (index + 3 <= data.size) {
        if (data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte()) {
            return true
        }
        if (index + 4 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
            data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()
        ) {
            return true
        }
        index += 1
    }
    return false
}

private fun avccToAnnexB(data: ByteArray): ByteArray? {
    if (data.size < 5) return null
    val output = ByteArrayOutputStream(data.size + 16)
    var position = 0
    var nalCount = 0
    while (position < data.size) {
        if (data.size - position < 4) return null
        val length = ((data[position].toInt() and 0xff) shl 24) or
            ((data[position + 1].toInt() and 0xff) shl 16) or
            ((data[position + 2].toInt() and 0xff) shl 8) or
            (data[position + 3].toInt() and 0xff)
        position += 4
        if (length <= 0 || length > data.size - position) return null
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(data, position, length)
        position += length
        nalCount += 1
    }
    return if (nalCount == 0) null else output.toByteArray()
}
