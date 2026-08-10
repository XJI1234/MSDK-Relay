package com.skycommand.relay.wayline.executor

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionStateEvent
import com.skycommand.relay.wayline.state.MissionStateStore
import com.skycommand.relay.wayline.state.UploadState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MissionExecutorContractTest {

    @Test
    fun reportsExactlyOneSafeTerminalOutcomeToTheAcceptedCaller() {
        val fixture = Fixture()
        val outcomes = mutableListOf<ExecutionTerminalOutcome>()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start(ExecutionTerminalListener { outcomes += it }))
        fixture.port.completeSuccess()
        fixture.port.completeSuccess()

        assertEquals(listOf(ExecutionTerminalOutcome.SUCCEEDED), outcomes)
    }

    @Test
    fun completesTheFullStartPauseResumeStopLifecycle() {
        val fixture = Fixture()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        assertEquals(ExecutionState.STARTING, fixture.store.snapshot().execution)
        fixture.port.completeSuccess()
        assertEquals(ExecutionState.EXECUTING, fixture.store.snapshot().execution)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        fixture.port.completeSuccess()
        assertEquals(ExecutionState.PAUSED, fixture.store.snapshot().execution)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.resume())
        fixture.port.completeSuccess()
        assertEquals(ExecutionState.EXECUTING, fixture.store.snapshot().execution)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.stop())
        assertEquals(ExecutionState.STOPPING, fixture.store.snapshot().execution)
        fixture.port.completeSuccess()
        assertEquals(ExecutionState.FINISHED, fixture.store.snapshot().execution)
    }

    @Test
    fun rejectsCommandsWhenMissionOrRequiredStateIsMissing() {
        val empty = Fixture(ready = false)
        assertEquals(ExecutionRejection.NO_MISSION, assertIs<ExecutionRequestResult.Rejected>(empty.executor.start()).reason)

        val notUploaded = Fixture(ready = false).apply { stageOnly() }
        assertEquals(ExecutionRejection.NOT_UPLOADED, assertIs<ExecutionRequestResult.Rejected>(notUploaded.executor.start()).reason)

        val fixture = Fixture()
        assertEquals(ExecutionRejection.INVALID_STATE, assertIs<ExecutionRequestResult.Rejected>(fixture.executor.pause()).reason)
        assertEquals(ExecutionRejection.INVALID_STATE, assertIs<ExecutionRequestResult.Rejected>(fixture.executor.resume()).reason)
        fixture.executor.start()
        assertEquals(ExecutionRejection.ALREADY_ACTIVE, assertIs<ExecutionRequestResult.Rejected>(fixture.executor.pause()).reason)
    }

    @Test
    fun mapsFailureTimeoutCancellationAndAdapterExceptionToFailed() {
        val failure = Fixture()
        failure.executor.start()
        failure.port.completeFailure()
        assertEquals(ExecutionState.FAILED, failure.store.snapshot().execution)

        val timeout = Fixture()
        timeout.executor.start()
        timeout.scheduler.fire()
        assertEquals(ExecutionState.FAILED, timeout.store.snapshot().execution)

        val cancelled = Fixture()
        val accepted = assertIs<ExecutionRequestResult.Accepted>(cancelled.executor.start())
        accepted.cancellation.cancel()
        assertEquals(ExecutionState.FAILED, cancelled.store.snapshot().execution)

        val exception = Fixture()
        exception.port.throwOnCall = true
        exception.executor.start()
        assertEquals(ExecutionState.FAILED, exception.store.snapshot().execution)
    }

    @Test
    fun ignoresDuplicateCompletionAndCallbacksFromAnOldMission() {
        val fixture = Fixture()
        fixture.executor.start()
        val oldCompletion = fixture.port.completion!!
        fixture.store.apply(MissionStateEvent.FileStaged(2, metadata("replacement.kmz")))

        oldCompletion.succeed()
        oldCompletion.fail()

        assertEquals("replacement.kmz", fixture.store.snapshot().file?.fileName)
        assertEquals(ExecutionState.NOT_STARTED, fixture.store.snapshot().execution)
    }

    @Test
    fun acceptsOnlyOneConcurrentCommand() {
        val fixture = Fixture()
        val gate = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<ExecutionRequestResult>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2) {
                pool.submit {
                    gate.await()
                    results += fixture.executor.start()
                }
            }
            gate.countDown()
            pool.shutdown()
            check(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, results.count { it is ExecutionRequestResult.Accepted })
        assertEquals(1, results.count { it is ExecutionRequestResult.Rejected })
    }

    private class Fixture(ready: Boolean = true) {
        val store = MissionStateStore.create()
        val port = Port()
        val scheduler = Scheduler()
        private val coordinator = DjiOperationCoordinator.create(
            executor = OperationExecutor { it() },
            scheduler = scheduler,
        )
        val executor = MissionExecutor.create(store, port, coordinator)

        init {
            if (ready) {
                stageOnly()
                store.apply(MissionStateEvent.UploadChanged(1, store.snapshot().missionRevision!!, UploadState.UPLOADED))
            }
        }

        fun stageOnly() {
            store.apply(
                MissionStateEvent.FileStaged(
                    1,
                    MissionMetadata("mission.kmz", 3, "a".repeat(64)),
                ),
            )
        }
    }

    private class Port : MissionControlPort {
        var completion: ControlCompletion? = null
        var throwOnCall = false
        override fun start(completion: ControlCompletion) = call(completion)
        override fun pause(completion: ControlCompletion) = call(completion)
        override fun resume(completion: ControlCompletion) = call(completion)
        override fun stop(completion: ControlCompletion) = call(completion)
        private fun call(completion: ControlCompletion) {
            if (throwOnCall) error("adapter failure")
            this.completion = completion
        }
        fun completeSuccess() { completion!!.succeed() }
        fun completeFailure() { completion!!.fail() }
    }

    private class Scheduler : OperationScheduler {
        var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            this.callback = callback
            return OperationCancellation {}
        }
        fun fire() { callback?.invoke() }
    }

    private fun metadata(name: String = "mission.kmz") = MissionMetadata(name, 3, "a".repeat(64))
}
