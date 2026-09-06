package com.skycommand.relay.wayline.uploader

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionStateEvent
import com.skycommand.relay.wayline.state.MissionStateStore
import com.skycommand.relay.wayline.state.UploadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.security.MessageDigest
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MissionUploaderContractTest {

    @Test
    fun exposesStreamingBoundariesForBoundedMissionMemory() {
        val readerMethod = StagedMissionContentReader::class.java.methods.singleOrNull { method ->
            method.name == "open" && method.parameterTypes.contentEquals(arrayOf(MissionMetadata::class.java))
        }
        val prepareMethod = MissionUploadPort::class.java.methods.singleOrNull { method ->
            method.name == "prepare" && method.parameterTypes.contentEquals(
                arrayOf(MissionMetadata::class.java, InputStream::class.java),
            )
        }

        assertEquals(InputStream::class.java, readerMethod?.returnType)
        assertTrue(prepareMethod != null)
    }

    @Test
    fun reportsExactlyOneSafeTerminalOutcomeToTheAcceptedCaller() {
        val fixture = Fixture()
        val outcomes = mutableListOf<UploadTerminalOutcome>()

        assertIs<UploadStartResult.Accepted>(fixture.uploader.start(UploadTerminalListener { outcomes += it }))
        fixture.port.completion!!.succeed()
        fixture.port.completion!!.succeed()

        assertEquals(listOf(UploadTerminalOutcome.SUCCEEDED), outcomes)
    }

    @Test
    fun forwardsTheNormalizedDjiFailureToTheAcceptedCaller() {
        val fixture = Fixture()
        val failure = MissionUploadFailure.fromDjiError("WAYPOINT_MISSION_BUSY", "The mission manager is busy")
        var received: MissionUploadFailure? = null
        fixture.uploader.start(object : UploadTerminalListener {
            override fun onCompleted(outcome: UploadTerminalOutcome) = Unit
            override fun onCompleted(outcome: UploadTerminalOutcome, failure: MissionUploadFailure?) { received = failure }
        })

        fixture.port.completion!!.fail(failure)

        assertEquals(failure, received)
        assertEquals(UploadState.FAILED, fixture.store.snapshot().upload)
    }

    @Test
    fun recordsProgressAndPublishesUploadedOnlyAfterCoordinatorSuccess() {
        val fixture = Fixture()
        val accepted = assertIs<UploadStartResult.Accepted>(fixture.uploader.start())
        assertEquals(UploadState.Uploading(0), fixture.store.snapshot().upload)
        fixture.port.progress?.invoke(100)
        assertEquals(UploadState.Uploading(100), fixture.store.snapshot().upload)

        fixture.port.completion?.succeed()

        assertEquals(UploadState.UPLOADED, fixture.store.snapshot().upload)
        assertTrue(accepted.cancellation.cancel() is com.skycommand.relay.device.operation.CancellationResult.AlreadyFinished)
    }

    @Test
    fun rejectsNoMissionAndDuplicateStartsWithoutCallingReaderTwice() {
        val empty = Fixture(stage = false)
        assertEquals(UploadRejection.NO_MISSION, assertIs<UploadStartResult.Rejected>(empty.uploader.start()).reason)

        val fixture = Fixture()
        assertIs<UploadStartResult.Accepted>(fixture.uploader.start())
        assertEquals(
            UploadRejection.ALREADY_ACTIVE,
            assertIs<UploadStartResult.Rejected>(fixture.uploader.start()).reason,
        )
        assertEquals(1, fixture.reader.opens)
    }

    @Test
    fun mapsReaderFailureAdapterFailureAndCoordinatorRejectionToSafeFailure() {
        val readerFailure = Fixture().apply { reader.failure = true }
        assertEquals(
            UploadRejection.CONTENT_UNAVAILABLE,
            assertIs<UploadStartResult.Rejected>(readerFailure.uploader.start()).reason,
        )
        assertEquals(UploadState.FAILED, readerFailure.store.snapshot().upload)

        val adapterFailure = Fixture()
        assertIs<UploadStartResult.Accepted>(adapterFailure.uploader.start())
        adapterFailure.port.completion?.fail()
        assertEquals(UploadState.FAILED, adapterFailure.store.snapshot().upload)

        val rejected = Fixture(timeoutMillis = 999)
        assertEquals(
            UploadRejection.OPERATION_REJECTED,
            assertIs<UploadStartResult.Rejected>(rejected.uploader.start()).reason,
        )
        assertEquals(UploadState.FAILED, rejected.store.snapshot().upload)
    }

    @Test
    fun rejectsChangedStagedContentBeforeStartingDjiUpload() {
        val fixture = Fixture().apply { reader.bytes = byteArrayOf(1, 2, 9) }

        assertEquals(
            UploadRejection.CONTENT_UNAVAILABLE,
            assertIs<UploadStartResult.Rejected>(fixture.uploader.start()).reason,
        )
        assertEquals(UploadState.FAILED, fixture.store.snapshot().upload)
        assertEquals(1, fixture.port.calls)
        assertEquals(0, fixture.port.starts)
    }

    @Test
    fun discardsPreparedContentThatWasNotFullyConsumedBeforeCallingDji() {
        val fixture = Fixture().apply { port.consumeAll = false }

        assertEquals(
            UploadRejection.CONTENT_UNAVAILABLE,
            assertIs<UploadStartResult.Rejected>(fixture.uploader.start()).reason,
        )
        assertEquals(0, fixture.port.starts)
        assertTrue(fixture.port.discarded)
    }

    @Test
    fun mapsAnAdapterExceptionToFailure() {
        val fixture = Fixture().apply { port.failure = true }

        assertIs<UploadStartResult.Accepted>(fixture.uploader.start())

        assertEquals(UploadState.FAILED, fixture.store.snapshot().upload)
    }

    @Test
    fun mapsTimeoutAndCancellationToFailureAndIgnoresLateAdapterCallbacks() {
        val timeout = Fixture()
        assertIs<UploadStartResult.Accepted>(timeout.uploader.start())
        timeout.scheduler.fire()
        assertEquals(UploadState.FAILED, timeout.store.snapshot().upload)
        timeout.port.progress?.invoke(100)
        timeout.port.completion?.succeed()
        assertEquals(UploadState.FAILED, timeout.store.snapshot().upload)

        val cancelled = Fixture()
        val accepted = assertIs<UploadStartResult.Accepted>(cancelled.uploader.start())
        accepted.cancellation.cancel()
        assertEquals(UploadState.FAILED, cancelled.store.snapshot().upload)
        cancelled.port.progress?.invoke(100)
        cancelled.port.completion?.succeed()
        assertEquals(UploadState.FAILED, cancelled.store.snapshot().upload)
    }

    @Test
    fun ignoresCallbacksThatBelongToAReplacedMission() {
        val fixture = Fixture()
        assertIs<UploadStartResult.Accepted>(fixture.uploader.start())
        val oldProgress = fixture.port.progress!!
        val oldCompletion = fixture.port.completion!!

        fixture.store.apply(MissionStateEvent.FileStaged(2, stagedMetadata("new.kmz")))
        oldProgress(100)
        oldCompletion.succeed()

        assertEquals("new.kmz", fixture.store.snapshot().file?.fileName)
        assertEquals(UploadState.NOT_UPLOADED, fixture.store.snapshot().upload)
    }

    @Test
    fun acceptsOnlyOneConcurrentStart() {
        val fixture = Fixture()
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<UploadStartResult>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(2) {
                executor.submit {
                    start.await()
                    results += fixture.uploader.start()
                }
            }
            start.countDown()
            executor.shutdown()
            check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it is UploadStartResult.Accepted })
        assertEquals(1, results.count { it is UploadStartResult.Rejected })
    }

    private class Fixture(
        stage: Boolean = true,
        val timeoutMillis: Long = 30_000,
    ) {
        val store = MissionStateStore.create()
        val reader = Reader()
        val port = Port()
        val scheduler = Scheduler()
        private val coordinator = DjiOperationCoordinator.create(
            executor = OperationExecutor { it() },
            scheduler = scheduler,
        )
        val uploader = MissionUploader.create(
            stateStore = store,
            contentReader = reader,
            uploadPort = port,
            operationCoordinator = coordinator,
            timeoutMillis = timeoutMillis,
        )

        init {
            if (stage) store.apply(
                MissionStateEvent.FileStaged(
                    1,
                    stagedMetadata(),
                ),
            )
        }
    }

    private class Reader : StagedMissionContentReader {
        var opens = 0
        var failure = false
        var bytes = byteArrayOf(1, 2, 3)
        override fun open(metadata: MissionMetadata): InputStream {
            opens += 1
            if (failure) error("reader failure")
            return bytes.copyOf().inputStream()
        }
    }

    private class Port : MissionUploadPort {
        var progress: ((Int) -> Unit)? = null
        var completion: UploadCompletion? = null
        var failure = false
        var calls = 0
        var starts = 0
        var consumeAll = true
        var discarded = false

        override fun prepare(
            metadata: MissionMetadata,
            content: InputStream,
        ): MissionUploadPreparation {
            calls += 1
            if (consumeAll) content.readBytes() else content.read()
            return MissionUploadPreparation.Prepared(object : PreparedMissionUpload {
                override fun start(progress: (Int) -> Unit, completion: UploadCompletion) {
                    starts += 1
                    if (failure) error("adapter failure")
                    this@Port.progress = progress
                    this@Port.completion = completion
                }

                override fun discard() {
                    discarded = true
                }
            })
        }
    }

    private class Scheduler : OperationScheduler {
        var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            this.callback = callback
            return OperationCancellation {}
        }
        fun fire() = callback?.invoke()
    }

}

private fun stagedMetadata(name: String = "mission.kmz") = MissionMetadata(
    name,
    3,
    MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3)).joinToString("") { "%02x".format(it) },
)
