package com.skycommand.relay.gateway.outbound

import com.skycommand.relay.gateway.session.AttachResult
import com.skycommand.relay.gateway.session.HandshakeSendResult
import com.skycommand.relay.gateway.session.ActiveSession
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.SessionOutbound
import com.skycommand.relay.gateway.session.TransportWriteResult
import com.skycommand.relay.gateway.session.TransportWriter
import com.skycommand.relay.protocol.Accepted
import com.skycommand.relay.protocol.CommandResultFrame
import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.MissionResultFrame
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import com.skycommand.relay.protocol.TelemetryFrame
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class OutboundPublisher : SessionOutbound {
    private val lock = ReentrantLock()
    private var attachment: Attachment? = null

    override fun attach(generation: SessionGeneration, writer: TransportWriter): AttachResult = lock.withLock {
        val current = attachment
        when {
            current == null -> {
                attachment = Attachment(generation, writer, handshakeSent = false)
                AttachResult.AttachAccepted
            }

            current.generation == generation && current.writer === writer -> AttachResult.AttachAccepted
            else -> AttachResult.AttachRejected
        }
    }

    override fun sendHandshake(generation: SessionGeneration, frame: HelloFrame): HandshakeSendResult = lock.withLock {
        val current = attachment
        if (current == null || current.generation != generation || current.handshakeSent) {
            return HandshakeSendResult.SendRejected
        }

        val encoded = RelayFrameCodec.encode(frame) as? Accepted<ByteArray>
            ?: return HandshakeSendResult.SendRejected
        val writeResult = runCatching { current.writer.write(encoded.value) }.getOrNull()
        if (writeResult != TransportWriteResult.WriteAccepted) {
            return HandshakeSendResult.SendRejected
        }

        attachment = current.copy(handshakeSent = true)
        HandshakeSendResult.SendAccepted
    }

    override fun discard(generation: SessionGeneration) {
        lock.withLock {
            if (attachment?.generation == generation) {
                attachment = null
            }
        }
    }

    fun publish(activeSession: ActiveSession, frame: RelayFrame): PublishResult = lock.withLock {
        val current = attachment
        if (current == null || current.generation != activeSession.generation) {
            return PublishResult.Rejected(PublishRejectionKind.STALE_SESSION)
        }
        if (frame !is TelemetryFrame && frame !is CommandResultFrame && frame !is MissionResultFrame) {
            return PublishResult.Rejected(PublishRejectionKind.DIRECTION_NOT_ALLOWED)
        }

        val encoded = runCatching { RelayFrameCodec.encode(frame) }.getOrNull() as? Accepted<ByteArray>
            ?: return PublishResult.Rejected(PublishRejectionKind.ENCODING_REJECTED)
        val writeResult = runCatching { current.writer.write(encoded.value) }.getOrNull()
        if (writeResult != TransportWriteResult.WriteAccepted) {
            return PublishResult.Rejected(PublishRejectionKind.WRITE_REJECTED)
        }
        PublishResult.Delivered
    }

    private data class Attachment(
        val generation: SessionGeneration,
        val writer: TransportWriter,
        val handshakeSent: Boolean,
    )
}

sealed interface PublishResult {
    data object Delivered : PublishResult

    data class Rejected(val kind: PublishRejectionKind) : PublishResult
}

enum class PublishRejectionKind {
    STALE_SESSION,
    DIRECTION_NOT_ALLOWED,
    ENCODING_REJECTED,
    WRITE_REJECTED,
}
