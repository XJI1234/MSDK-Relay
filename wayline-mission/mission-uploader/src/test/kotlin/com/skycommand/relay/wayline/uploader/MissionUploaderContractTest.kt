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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MissionUploaderContractTest {

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
        assertEquals(1, fixture.reader.reads)
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

        fixture.store.apply(MissionStateEvent.FileStaged(2, metadata("new.kmz")))
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
                    MissionMetadata("mission.kmz", 3, "a".repeat(64)),
                ),
            )
        }
    }

    private class Reader : StagedMissionContentReader {
        var reads = 0
        var failure = false
        override fun read(metadata: MissionMetadata): ByteArray {
            reads += 1
            if (failure) error("reader failure")
            return byteArrayOf(1, 2, 3)
        }
    }

    private class Port : MissionUploadPort {
        var progress: ((Int) -> Unit)? = null
        var completion: UploadCompletion? = null
        var failure = false
        override fun upload(
            metadata: MissionMetadata,
            bytes: ByteArray,
            progress: (Int) -> Unit,
            completion: UploadCompletion,
        ) {
            if (failure) error("adapter failure")
            this.progress = progress
            this.completion = completion
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

    private fun metadata(name: String = "mission.kmz") = MissionMetadata(name, 3, "a".repeat(64))
}
