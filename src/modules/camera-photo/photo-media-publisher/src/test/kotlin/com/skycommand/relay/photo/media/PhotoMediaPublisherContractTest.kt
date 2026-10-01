package com.skycommand.relay.photo.media

import com.skycommand.relay.protocol.MediaBeginFrame
import com.skycommand.relay.protocol.MediaChunkFrame
import com.skycommand.relay.protocol.MediaCompleteFrame
import com.skycommand.relay.protocol.MediaResultFrame
import com.skycommand.relay.protocol.ProtocolLimits
import com.skycommand.relay.protocol.RelayFrame
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhotoMediaPublisherContractTest {
    @Test
    fun sendsBeginChunksAndCompleteThenSucceedsOnlyOnMatchingMediaResult() {
        val writer = Writer()
        val clock = Clock()
        val publisher = PhotoMediaPublisher.create(writer, clock, timeoutMillis = 1_000)
        val bytes = ByteArray(ProtocolLimits.maxMissionChunkBytes + 3) { 7 }
        val hash = PhotoMediaPublisher.sha256Hex(bytes)
        val outcomes = mutableListOf<PhotoMediaOutcome>()

        assertEquals(
            PhotoMediaSubmitResult.Accepted,
            publisher.publish(file("shot.jpg", bytes, hash)) { outcomes += it },
        )
        assertEquals(4, writer.frames.size)
        assertIs<MediaBeginFrame>(writer.frames[0])
        assertIs<MediaChunkFrame>(writer.frames[1])
        assertEquals(ProtocolLimits.maxMissionChunkBytes, (writer.frames[1] as MediaChunkFrame).bytes.size)
        assertIs<MediaChunkFrame>(writer.frames[2])
        assertIs<MediaCompleteFrame>(writer.frames[3])
        publisher.acceptResult(MediaResultFrame("photo-1", true, "stored"))
        assertEquals(1, outcomes.size)
        assertEquals(PhotoMediaOutcome.Delivered("shot.jpg", bytes.size.toLong(), hash), outcomes.single())
    }

    @Test
    fun rejectsMismatchedDigestBeforeWritingAndReportsBusyWhileAwaitingResult() {
        val writer = Writer()
        val publisher = PhotoMediaPublisher.create(writer, Clock(), timeoutMillis = 1_000)
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(
            PhotoMediaSubmitResult.Rejected(PhotoMediaSubmitResult.Reason.INVALID_FILE),
            publisher.publish(file("shot.jpg", bytes, "0".repeat(64))) { },
        )
        assertEquals(0, writer.frames.size)

        val hash = PhotoMediaPublisher.sha256Hex(bytes)
        assertEquals(PhotoMediaSubmitResult.Accepted, publisher.publish(file("shot.jpg", bytes, hash)) { })
        assertEquals(
            PhotoMediaSubmitResult.Rejected(PhotoMediaSubmitResult.Reason.BUSY),
            publisher.publish(file("shot.jpg", bytes, hash)) { },
        )
    }

    @Test
    fun abortAndTimeoutFailTheTransferAndIgnoreLateResults() {
        val clock = Clock()
        val outcomes = mutableListOf<PhotoMediaOutcome>()
        val publisher = PhotoMediaPublisher.create(Writer(), clock, timeoutMillis = 1_000)
        val bytes = byteArrayOf(9)
        val hash = PhotoMediaPublisher.sha256Hex(bytes)
        publisher.publish(file("shot.jpg", bytes, hash)) { outcomes += it }
        publisher.abort()
        assertEquals(listOf<PhotoMediaOutcome>(PhotoMediaOutcome.Failed), outcomes)
        publisher.acceptResult(MediaResultFrame("photo-1", true, "late"))
        assertEquals(1, outcomes.size)

        publisher.publish(file("shot.jpg", bytes, hash)) { outcomes += it }
        clock.fire()
        assertEquals(PhotoMediaOutcome.Failed, outcomes.last())
    }

    private fun file(name: String, bytes: ByteArray, hash: String) = PhotoMediaFile(
        name,
        bytes.size.toLong(),
        hash,
        object : PhotoReadableHandle {
            override fun openStream() = ByteArrayInputStream(bytes)
            override fun close() = Unit
        },
    )

    private class Writer : PhotoMediaWriter {
        val frames = mutableListOf<RelayFrame>()
        override fun write(frame: RelayFrame): Boolean {
            frames += frame
            return true
        }
    }

    private class Clock : PhotoMediaClock {
        private var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): PhotoMediaCancellation {
            this.callback = callback
            return PhotoMediaCancellation { }
        }
        fun fire() = checkNotNull(callback).invoke()
    }
}
