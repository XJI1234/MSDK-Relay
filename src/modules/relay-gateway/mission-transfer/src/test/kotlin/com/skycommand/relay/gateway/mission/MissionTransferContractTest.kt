package com.skycommand.relay.gateway.mission

import com.skycommand.relay.gateway.session.ActiveFrameConsumer
import com.skycommand.relay.gateway.session.AttachResult
import com.skycommand.relay.gateway.session.CommandSessionCleanup
import com.skycommand.relay.gateway.session.ConfigurationRejected
import com.skycommand.relay.gateway.session.ConnectionSession
import com.skycommand.relay.gateway.session.HandshakeSendResult
import com.skycommand.relay.gateway.session.MissionSessionCleanup
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.OrderedStateNotifier
import com.skycommand.relay.gateway.session.ScheduledCancellation
import com.skycommand.relay.gateway.session.SessionConfig
import com.skycommand.relay.gateway.session.SessionCreated
import com.skycommand.relay.gateway.session.SessionDependencies
import com.skycommand.relay.gateway.session.SessionDiagnosticSink
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.TransportCloseResult
import com.skycommand.relay.gateway.session.TransportConnection
import com.skycommand.relay.gateway.session.TransportConnector
import com.skycommand.relay.gateway.session.TransportListener
import com.skycommand.relay.gateway.session.TransportOpenResult
import com.skycommand.relay.gateway.session.TransportWriter
import com.skycommand.relay.gateway.session.TransportWriteResult
import com.skycommand.relay.protocol.Accepted
import com.skycommand.relay.protocol.MissionBeginFrame
import com.skycommand.relay.protocol.MissionChunkFrame
import com.skycommand.relay.protocol.MissionCompleteFrame
import com.skycommand.relay.protocol.MissionResultFrame
import com.skycommand.relay.protocol.PairedFrame
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.RelayFrameCodec
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.TelemetryFrame
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MissionTransferContractTest {

    @Test
    fun rejectsChunksAndCompletionWithoutAnActiveTransfer() {
        val sink = RecordingMissionSink()
        val publisher = RecordingMissionResultPublisher()
        val transfer = MissionTransfer(sink, publisher)
        val session = activeSession()

        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_NOT_ACTIVE),
            transfer.accept(session, MissionChunkFrame("missing", byteArrayOf(1))),
        )
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_NOT_ACTIVE),
            transfer.accept(session, MissionCompleteFrame("missing")),
        )
        assertTrue(sink.appended.isEmpty())
        assertEquals(listOf("missing", "missing"), publisher.frames.map { it.second.id })
    }

    @Test
    fun duplicateBeginPreservesTheExistingTransferAndDifferentBeginSupersedesIt() {
        val sink = RecordingMissionSink()
        val publisher = RecordingMissionResultPublisher()
        val transfer = MissionTransfer(sink, publisher)
        val session = activeSession()
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)

        assertEquals(MissionTransferResult.Accepted, transfer.accept(session, begin("one", first)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_ALREADY_ACTIVE),
            transfer.accept(session, begin("one", first)),
        )
        assertEquals(MissionTransferResult.Accepted, transfer.accept(session, begin("two", second)))

        assertEquals(listOf(MissionAbortReason.SUPERSEDED), sink.aborts)
        assertEquals(
            listOf(
                MissionResultFrame("one", false, "Mission transfer is already active"),
                MissionResultFrame("one", false, "Mission transfer was superseded"),
            ),
            publisher.frames.map { it.second },
        )
        assertEquals("two", sink.metadata?.transferId)
    }

    @Test
    fun rejectsMismatchedChunkIdsWithoutAppendingToTheCurrentTransfer() {
        val sink = RecordingMissionSink()
        val publisher = RecordingMissionResultPublisher()
        val transfer = MissionTransfer(sink, publisher)
        val session = activeSession()

        transfer.accept(session, begin("one", byteArrayOf(1)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_NOT_ACTIVE),
            transfer.accept(session, MissionChunkFrame("other", byteArrayOf(9))),
        )
        assertTrue(sink.appended.isEmpty())
        assertEquals(MissionTransferResult.Accepted, transfer.accept(session, MissionChunkFrame("one", byteArrayOf(1))))
    }

    @Test
    fun abortsOnOverrunUnderrunAndChecksumMismatch() {
        val session = activeSession()

        val overrunSink = RecordingMissionSink()
        val overrunPublisher = RecordingMissionResultPublisher()
        val overrun = MissionTransfer(overrunSink, overrunPublisher)
        overrun.accept(session, begin("overrun", byteArrayOf(1)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_SIZE_MISMATCH),
            overrun.accept(session, MissionChunkFrame("overrun", byteArrayOf(1, 2))),
        )
        assertEquals(listOf(MissionAbortReason.SIZE_MISMATCH), overrunSink.aborts)
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_NOT_ACTIVE),
            overrun.accept(session, MissionChunkFrame("overrun", byteArrayOf(1))),
        )

        val underrunSink = RecordingMissionSink()
        val underrunPublisher = RecordingMissionResultPublisher()
        val underrun = MissionTransfer(underrunSink, underrunPublisher)
        underrun.accept(session, begin("underrun", byteArrayOf(1, 2)))
        underrun.accept(session, MissionChunkFrame("underrun", byteArrayOf(1)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_SIZE_MISMATCH),
            underrun.accept(session, MissionCompleteFrame("underrun")),
        )
        assertEquals(listOf(MissionAbortReason.SIZE_MISMATCH), underrunSink.aborts)

        val checksumSink = RecordingMissionSink()
        val checksumPublisher = RecordingMissionResultPublisher()
        val checksum = MissionTransfer(checksumSink, checksumPublisher)
        checksum.accept(session, MissionBeginFrame("checksum", "route.kmz", 1, "0".repeat(64)))
        checksum.accept(session, MissionChunkFrame("checksum", byteArrayOf(1)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_CHECKSUM_MISMATCH),
            checksum.accept(session, MissionCompleteFrame("checksum")),
        )
        assertEquals(listOf(MissionAbortReason.CHECKSUM_MISMATCH), checksumSink.aborts)
    }

    @Test
    fun convertsSinkRejectionsAndExceptionsToRedactedFailure() {
        val session = activeSession()

        val beginRejectedSink = RecordingMissionSink().apply { beginResult = MissionSinkResult.Rejected }
        val beginRejectedPublisher = RecordingMissionResultPublisher()
        val beginRejected = MissionTransfer(beginRejectedSink, beginRejectedPublisher)
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED),
            beginRejected.accept(session, begin("begin", byteArrayOf(1))),
        )

        val appendRejectedSink = RecordingMissionSink()
        val appendRejectedPublisher = RecordingMissionResultPublisher()
        val appendRejected = MissionTransfer(appendRejectedSink, appendRejectedPublisher)
        appendRejected.accept(session, begin("append", byteArrayOf(1)))
        appendRejectedSink.appendResult = MissionSinkResult.Rejected
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED),
            appendRejected.accept(session, MissionChunkFrame("append", byteArrayOf(1))),
        )

        val completeRejectedSink = RecordingMissionSink().apply { completeResult = MissionSinkCompletionResult.Rejected }
        val completeRejectedPublisher = RecordingMissionResultPublisher()
        val completeRejected = MissionTransfer(completeRejectedSink, completeRejectedPublisher)
        completeRejected.accept(session, begin("complete", byteArrayOf(1)))
        completeRejected.accept(session, MissionChunkFrame("complete", byteArrayOf(1)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED),
            completeRejected.accept(session, MissionCompleteFrame("complete")),
        )

        listOf(beginRejectedPublisher, appendRejectedPublisher, completeRejectedPublisher).forEach { publisher ->
            assertTrue(publisher.frames.all { it.second.detail == "Mission transfer failed" })
        }
    }

    @Test
    fun convertsSinkExceptionsAtEveryStageToTheSameSafeFailure() {
        val session = activeSession()

        val beginSink = RecordingMissionSink().apply { throwOnBegin = true }
        val beginPublisher = RecordingMissionResultPublisher()
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED),
            MissionTransfer(beginSink, beginPublisher).accept(session, begin("begin-throw", byteArrayOf(1))),
        )

        val appendSink = RecordingMissionSink().apply { throwOnAppend = true }
        val appendPublisher = RecordingMissionResultPublisher()
        val appendTransfer = MissionTransfer(appendSink, appendPublisher)
        appendTransfer.accept(session, begin("append-throw", byteArrayOf(1)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED),
            appendTransfer.accept(session, MissionChunkFrame("append-throw", byteArrayOf(1))),
        )

        val completeSink = RecordingMissionSink().apply { throwOnComplete = true }
        val completePublisher = RecordingMissionResultPublisher()
        val completeTransfer = MissionTransfer(completeSink, completePublisher)
        completeTransfer.accept(session, begin("complete-throw", byteArrayOf(1)))
        completeTransfer.accept(session, MissionChunkFrame("complete-throw", byteArrayOf(1)))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED),
            completeTransfer.accept(session, MissionCompleteFrame("complete-throw")),
        )

        listOf(beginPublisher, appendPublisher, completePublisher).forEach { publisher ->
            assertEquals("Mission transfer failed", publisher.frames.single().second.detail)
        }
    }

    @Test
    fun oldGenerationFramesCannotAppendAfterAReplacementGenerationBegins() {
        val sink = RecordingMissionSink()
        val publisher = RecordingMissionResultPublisher()
        val transfer = MissionTransfer(sink, publisher)
        val oldHarness = activeHarness(transfer)
        val newHarness = activeHarness(transfer)

        transfer.accept(oldHarness.activeSession, begin("old", byteArrayOf(1)))
        oldHarness.session.stop()
        transfer.accept(newHarness.activeSession, begin("new", byteArrayOf(2)))

        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_NOT_ACTIVE),
            transfer.accept(oldHarness.activeSession, MissionChunkFrame("old", byteArrayOf(1))),
        )
        assertEquals(
            MissionTransferResult.Accepted,
            transfer.accept(newHarness.activeSession, MissionChunkFrame("new", byteArrayOf(2))),
        )
        assertEquals(listOf(byteArrayOf(2).toList()), sink.appended.map { it.toList() })
    }

    @Test
    fun serializesConcurrentChunksBeforeCompletingTheTransfer() {
        val chunkCount = 32
        val content = ByteArray(chunkCount) { 1 }
        val sink = RecordingMissionSink().apply { appendDelayMillis = 2 }
        val transfer = MissionTransfer(sink, RecordingMissionResultPublisher())
        val session = activeSession()
        val executor = Executors.newFixedThreadPool(8)

        transfer.accept(session, begin("concurrent", content))
        try {
            List(chunkCount) {
                executor.submit {
                    assertEquals(
                        MissionTransferResult.Accepted,
                        transfer.accept(session, MissionChunkFrame("concurrent", byteArrayOf(1))),
                    )
                }
            }.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, sink.maximumConcurrentAppends.get())
        assertIs<MissionTransferResult.Completed>(transfer.accept(session, MissionCompleteFrame("concurrent")))
    }

    @Test
    fun rejectsInconsistentStagedMetadataAndPublisherRejectionDoesNotChangeCompletion() {
        val content = byteArrayOf(7)
        val inconsistentSink = RecordingMissionSink().apply {
            completeResult = MissionSinkCompletionResult.Accepted(
                StagedMission("wrong", "route.kmz", 1, digestSha256(content), MissionReadable { content.inputStream() }),
            )
        }
        val inconsistent = MissionTransfer(inconsistentSink, RecordingMissionResultPublisher())
        val session = activeSession()
        inconsistent.accept(session, begin("consistent", content))
        inconsistent.accept(session, MissionChunkFrame("consistent", content))
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_FAILED),
            inconsistent.accept(session, MissionCompleteFrame("consistent")),
        )
        assertEquals(listOf(MissionAbortReason.TRANSFER_FAILED), inconsistentSink.aborts)

        val deliverySink = RecordingMissionSink()
        val rejectedPublisher = RecordingMissionResultPublisher().apply {
            result = com.skycommand.relay.gateway.outbound.PublishResult.Rejected(
                com.skycommand.relay.gateway.outbound.PublishRejectionKind.WRITE_REJECTED,
            )
        }
        val delivery = MissionTransfer(deliverySink, rejectedPublisher)
        delivery.accept(session, begin("publisher", content))
        delivery.accept(session, MissionChunkFrame("publisher", content))
        assertIs<MissionTransferResult.Completed>(delivery.accept(session, MissionCompleteFrame("publisher")))
    }

    @Test
    fun cleanupAbortsTheTransferAndLateFramesCannotWriteToTheSink() {
        val sink = RecordingMissionSink()
        val publisher = RecordingMissionResultPublisher()
        val transfer = MissionTransfer(sink, publisher)
        val harness = activeHarness(transfer)
        val content = byteArrayOf(1, 2)

        transfer.accept(harness.activeSession, begin("cancelled", content))
        harness.session.stop()
        harness.session.stop()

        assertEquals(listOf(MissionAbortReason.SESSION_ENDED), sink.aborts)
        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_NOT_ACTIVE),
            transfer.accept(harness.activeSession, MissionChunkFrame("cancelled", content)),
        )
        assertTrue(sink.appended.isEmpty())
    }

    @Test
    fun isolatesGenerationsAndSurvivesPublisherFailure() {
        val sink = RecordingMissionSink()
        val publisher = RecordingMissionResultPublisher().apply { throwOnPublish = true }
        val transfer = MissionTransfer(sink, publisher)
        val oldHarness = activeHarness(transfer)
        val oldContent = byteArrayOf(1)
        transfer.accept(oldHarness.activeSession, begin("old", oldContent))
        oldHarness.session.stop()

        assertEquals(
            MissionTransferResult.Rejected(TransferRejectionKind.TRANSFER_NOT_ACTIVE),
            transfer.accept(oldHarness.activeSession, MissionChunkFrame("old", oldContent)),
        )
        assertTrue(sink.appended.isEmpty())
    }

    @Test
    fun streamsChunksToTheSinkAndPublishesOneSuccessResultAfterVerifiedCompletion() {
        val content = "mission-bytes".encodeToByteArray()
        val sink = RecordingMissionSink()
        val publisher = RecordingMissionResultPublisher()
        val transfer = MissionTransfer(sink, publisher)
        val session = activeSession()
        val hash = digestSha256(content)

        assertEquals(
            MissionTransferResult.Accepted,
            transfer.accept(session, MissionBeginFrame("mission-1", "route.kmz", content.size.toLong(), hash)),
        )
        assertEquals(
            MissionTransferResult.Accepted,
            transfer.accept(session, MissionChunkFrame("mission-1", content.copyOfRange(0, 7))),
        )
        assertEquals(
            MissionTransferResult.Accepted,
            transfer.accept(session, MissionChunkFrame("mission-1", content.copyOfRange(7, content.size))),
        )

        val completed = transfer.accept(session, MissionCompleteFrame("mission-1"))

        val staged = assertIs<MissionTransferResult.Completed>(completed).mission
        assertEquals("mission-1", staged.transferId)
        assertEquals(content.toList(), sink.appended.flatMap { it.toList() })
        assertEquals(listOf("route.kmz", content.size.toLong(), hash), sink.metadataSeen)
        assertEquals(
            listOf(MissionResultFrame("mission-1", true, "Mission staged")),
            publisher.frames.map { it.second },
        )
    }

    private fun activeSession(): com.skycommand.relay.gateway.session.ActiveSession {
        return activeHarness().activeSession
    }

    private fun activeHarness(missionCleanup: MissionSessionCleanup = MissionSessionCleanup { _, _ -> }): SessionHarness {
        val connector = RecordingConnector()
        val consumer = CapturingConsumer()
        val result = ConnectionSession.create(
            SessionConfig(endpoint = "ws://desktop/relay", deviceId = "phone-1"),
            SessionDependencies(
                connector = connector,
                outbound = AcceptingOutbound(),
                activeFrameConsumer = consumer,
                commandCleanup = CommandSessionCleanup { _, _ -> },
                missionCleanup = missionCleanup,
                scheduler = MonotonicScheduler { _, _ -> ScheduledCancellation { } },
                stateNotifier = OrderedStateNotifier { _, _ -> },
                diagnosticSink = SessionDiagnosticSink { },
            ),
        )
        val session = when (result) {
            is SessionCreated -> result.session
            is ConfigurationRejected -> error(result.detail)
        }
        session.start()
        connector.open()
        connector.receive(encoded(PairedFrame("desktop-session", null)))
        connector.receive(encoded(TelemetryFrame(JsonObject(emptyMap()), JsonObject(emptyMap()))))
        return SessionHarness(session, consumer.activeSession)
    }

    private fun begin(id: String, bytes: ByteArray): MissionBeginFrame =
        MissionBeginFrame(id, "route.kmz", bytes.size.toLong(), digestSha256(bytes))

    private class CapturingConsumer : ActiveFrameConsumer {
        lateinit var activeSession: com.skycommand.relay.gateway.session.ActiveSession

        override fun accept(
            activeSession: com.skycommand.relay.gateway.session.ActiveSession,
            frame: RelayFrame,
        ) {
            this.activeSession = activeSession
        }
    }

    private class RecordingMissionSink : MissionSink {
        val appended = mutableListOf<ByteArray>()
        var metadata: MissionMetadata? = null
        val metadataSeen: List<Any>
            get() = listOfNotNull(metadata?.fileName, metadata?.size, metadata?.sha256)
        var beginResult: MissionSinkResult = MissionSinkResult.Accepted
        var appendResult: MissionSinkResult = MissionSinkResult.Accepted
        var completeResult: MissionSinkCompletionResult? = null
        var throwOnBegin = false
        var throwOnAppend = false
        var throwOnComplete = false
        var appendDelayMillis = 0L
        val aborts = mutableListOf<MissionAbortReason>()
        val maximumConcurrentAppends = AtomicInteger()
        private val concurrentAppends = AtomicInteger()

        override fun begin(metadata: MissionMetadata): MissionSinkResult {
            if (throwOnBegin) throw IllegalStateException("begin secret")
            this.metadata = metadata
            return beginResult
        }

        override fun append(bytes: ByteArray): MissionSinkResult {
            if (throwOnAppend) throw IllegalStateException("append secret")
            val concurrent = concurrentAppends.incrementAndGet()
            maximumConcurrentAppends.accumulateAndGet(concurrent, ::maxOf)
            try {
                if (appendDelayMillis > 0) Thread.sleep(appendDelayMillis)
                if (appendResult == MissionSinkResult.Accepted) {
                    appended += bytes.copyOf()
                }
                return appendResult
            } finally {
                concurrentAppends.decrementAndGet()
            }
        }

        override fun complete(): MissionSinkCompletionResult {
            if (throwOnComplete) throw IllegalStateException("complete secret")
            return completeResult ?: MissionSinkCompletionResult.Accepted(
                StagedMission(
                    transferId = requireNotNull(metadata).transferId,
                    fileName = requireNotNull(metadata).fileName,
                    size = requireNotNull(metadata).size,
                    sha256 = digestSha256(appended.flatMap { it.toList() }.toByteArray()),
                    readableByMissionModule = MissionReadable { appended.asSequence().flatMap { it.asSequence() }.toList().toByteArray().inputStream() },
                ),
            )
        }

        override fun abort(reason: MissionAbortReason) {
            aborts += reason
        }
    }

    private class RecordingMissionResultPublisher : MissionResultPublisher {
        val frames = mutableListOf<Pair<com.skycommand.relay.gateway.session.ActiveSession, MissionResultFrame>>()
        var throwOnPublish = false
        var result: com.skycommand.relay.gateway.outbound.PublishResult = com.skycommand.relay.gateway.outbound.PublishResult.Delivered

        override fun publish(
            activeSession: com.skycommand.relay.gateway.session.ActiveSession,
            frame: MissionResultFrame,
        ): com.skycommand.relay.gateway.outbound.PublishResult {
            if (throwOnPublish) throw IllegalStateException("publisher secret")
            frames += activeSession to frame
            return result
        }
    }

    private class RecordingConnector : TransportConnector {
        private lateinit var current: RecordingConnection

        override fun open(
            endpoint: String,
            generation: SessionGeneration,
            listener: TransportListener,
        ): TransportOpenResult {
            current = RecordingConnection(generation, listener)
            return TransportOpenResult.OpenAccepted(current)
        }

        fun open() = current.open()

        fun receive(bytes: ByteArray) = current.receive(bytes)
    }

    private class RecordingConnection(
        override val generation: SessionGeneration,
        private val listener: TransportListener,
    ) : TransportConnection {
        override val writer = TransportWriter { TransportWriteResult.WriteAccepted }

        fun open() = listener.onOpened(this)

        fun receive(bytes: ByteArray) = listener.onBytes(generation, bytes)

        override fun close(reason: String): TransportCloseResult = TransportCloseResult.CloseRequested
    }

    private class AcceptingOutbound : com.skycommand.relay.gateway.session.SessionOutbound {
        override fun attach(generation: SessionGeneration, writer: TransportWriter): AttachResult = AttachResult.AttachAccepted

        override fun sendHandshake(
            generation: SessionGeneration,
            frame: com.skycommand.relay.protocol.HelloFrame,
        ): HandshakeSendResult = HandshakeSendResult.SendAccepted

        override fun discard(generation: SessionGeneration) = Unit
    }

    private data class SessionHarness(
        val session: ConnectionSession,
        val activeSession: com.skycommand.relay.gateway.session.ActiveSession,
    )

    private fun encoded(frame: RelayFrame): ByteArray = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value

}

private fun digestSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
