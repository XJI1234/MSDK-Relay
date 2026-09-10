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
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class MissionExecutorContractTest {

    @Test
    fun containsNoLocalDeviceSafetyGateBeforeCallingDjiStartMission() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/wayline/executor/MissionExecutor.kt"),
            Path("src/modules/wayline-mission/mission-executor/src/main/kotlin/com/skycommand/relay/wayline/executor/MissionExecutor.kt"),
        ).first { it.exists() }.readText()

        assertFalse(source.contains("MissionStartSafetyGate"))
        assertFalse(source.contains("SAFETY_CHECK_FAILED"))
    }

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
    fun completesTheControlLifecycleWithoutTreatingStartAcceptanceAsFlightExecution() {
        val fixture = Fixture()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        assertEquals(ExecutionState.STARTING, fixture.store.snapshot().execution)
        fixture.port.completeSuccess()
        assertEquals(ExecutionState.STARTING, fixture.store.snapshot().execution)

        fixture.markExecutionStarted()

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
    fun leavesAStartedMissionUnconfirmedWhenTheDjiControlCallHasNoSuccessReceipt() {
        val failure = Fixture()
        failure.executor.start()
        failure.port.completeFailure()
        assertEquals(ExecutionState.STARTING, failure.store.snapshot().execution)

        val timeout = Fixture()
        timeout.executor.start()
        timeout.scheduler.fire()
        assertEquals(ExecutionState.STARTING, timeout.store.snapshot().execution)

        val cancelled = Fixture()
        val accepted = assertIs<ExecutionRequestResult.Accepted>(cancelled.executor.start())
        accepted.cancellation.cancel()
        assertEquals(ExecutionState.STARTING, cancelled.store.snapshot().execution)

        val exception = Fixture()
        exception.port.throwOnCall = true
        exception.executor.start()
        assertEquals(ExecutionState.STARTING, exception.store.snapshot().execution)
    }

    @Test
    fun keepsDjiControlQuarantinedAfterASynchronousPortExceptionButAllowsMissionStop() {
        val fixture = Fixture()
        fixture.markExecutionStarted()
        fixture.port.throwOnCall = true

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())

        fixture.port.throwOnCall = false
        assertEquals(
            ExecutionRejection.OPERATION_REJECTED,
            assertIs<ExecutionRequestResult.Rejected>(fixture.executor.pause()).reason,
        )
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.stop())
        assertEquals(1, fixture.port.stopCalls)
    }

    @Test
    fun stopPreemptsAnAcceptedStartThatIsStillWaitingForItsDjiReceipt() {
        val fixture = Fixture()
        val startOutcomes = mutableListOf<ExecutionTerminalOutcome>()
        val stopOutcomes = mutableListOf<ExecutionTerminalOutcome>()

        assertIs<ExecutionRequestResult.Accepted>(
            fixture.executor.start(ExecutionTerminalListener { startOutcomes += it }),
        )

        assertIs<ExecutionRequestResult.Accepted>(
            fixture.executor.stop(ExecutionTerminalListener { stopOutcomes += it }),
        )

        assertEquals(listOf(ExecutionTerminalOutcome.CANCELLED), startOutcomes)
        assertEquals(emptyList(), stopOutcomes)
        assertEquals(ExecutionState.STOPPING, fixture.store.snapshot().execution)
        assertEquals(1, fixture.port.stopCalls)

        fixture.port.completeSuccess()

        assertEquals(listOf(ExecutionTerminalOutcome.SUCCEEDED), stopOutcomes)
        assertEquals(ExecutionState.FINISHED, fixture.store.snapshot().execution)
    }

    @Test
    fun sendsStartMissionAfterPauseReceiptTimesOut() {
        val fixture = Fixture()
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        fixture.scheduler.fire()

        assertEquals(
            ExecutionRejection.OPERATION_UNCONFIRMED,
            assertIs<ExecutionRequestResult.Rejected>(fixture.executor.pause()).reason,
        )
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        assertEquals(1, fixture.port.startCalls)
        fixture.port.completeSuccess()
        fixture.markExecutionStarted()
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
    }

    @Test
    fun sendsStartMissionAfterResumeReceiptTimesOut() {
        val fixture = Fixture()
        fixture.markExecutionStarted()
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        fixture.port.completeSuccess()
        assertEquals(ExecutionState.PAUSED, fixture.store.snapshot().execution)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.resume())
        fixture.scheduler.fire()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        assertEquals(1, fixture.port.startCalls)
    }

    @Test
    fun doesNotStartWhilePauseIsStillWaitingForItsDjiReceipt() {
        val fixture = Fixture()
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        assertEquals(
            ExecutionRejection.ALREADY_ACTIVE,
            assertIs<ExecutionRequestResult.Rejected>(fixture.executor.start()).reason,
        )
        assertEquals(0, fixture.port.startCalls)
    }

    @Test
    fun doesNotRepeatPauseAfterItsDjiReceiptIsLost() {
        val fixture = Fixture()
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        fixture.scheduler.fire()

        assertEquals(
            ExecutionRejection.OPERATION_UNCONFIRMED,
            assertIs<ExecutionRequestResult.Rejected>(fixture.executor.pause()).reason,
        )
        fixture.setExecution(ExecutionState.PAUSED)
        fixture.executor.observeExecutionState(
            ExecutionState.PAUSED,
            fixture.store.snapshot().missionRevision!!,
            fixture.store.snapshot().deviceGeneration,
        )
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.resume())
    }

    @Test
    fun allowsStopRetryAfterItsDjiReceiptIsLost() {
        val fixture = Fixture()
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.stop())
        assertEquals(
            ExecutionRejection.ALREADY_ACTIVE,
            assertIs<ExecutionRequestResult.Rejected>(fixture.executor.stop()).reason,
        )
        assertEquals(1, fixture.port.stopCalls)
        fixture.scheduler.fire()

        assertEquals(ExecutionState.STOPPING, fixture.store.snapshot().execution)
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.stop())
        assertEquals(2, fixture.port.stopCalls)
    }

    @Test
    fun restoresPauseStateAfterExplicitDjiFailureAndAllowsRetry() {
        val fixture = Fixture()
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        fixture.port.completeFailure()

        assertEquals(ExecutionState.EXECUTING, fixture.store.snapshot().execution)
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
    }

    @Test
    fun restoresThePreStartStateAndForwardsTheNormalizedDjiFailureToTheAcceptedCaller() {
        val fixture = Fixture()
        val failure = MissionControlFailure.fromDjiError("WAYPOINT_MISSION_BUSY", "The mission manager is busy")
        var received: MissionControlFailure? = null
        fixture.executor.start(object : ExecutionTerminalListener {
            override fun onCompleted(outcome: ExecutionTerminalOutcome) = Unit
            override fun onCompleted(outcome: ExecutionTerminalOutcome, failure: MissionControlFailure?) { received = failure }
        })

        fixture.port.completeFailure(failure)

        assertEquals(failure, received)
        assertEquals(ExecutionState.NOT_STARTED, fixture.store.snapshot().execution)
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
    }

    @Test
    fun sendsStartMissionAgainFromStartingAfterDjiAcceptedTheFirstStart() {
        val fixture = Fixture()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        fixture.port.completeSuccess()
        assertEquals(ExecutionState.STARTING, fixture.store.snapshot().execution)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        assertEquals(2, fixture.port.startCalls)
    }

    @Test
    fun sendsStartMissionAgainAfterTheFirstStartReceiptTimesOut() {
        val fixture = Fixture()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        fixture.scheduler.fire()
        assertEquals(ExecutionState.STARTING, fixture.store.snapshot().execution)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        assertEquals(2, fixture.port.startCalls)
    }

    @Test
    fun sendsStartMissionEvenWhenLocalExecutionStateAlreadySaysExecuting() {
        val fixture = Fixture()
        fixture.executor.start()
        fixture.port.completeSuccess()
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.start())
        assertEquals(2, fixture.port.startCalls)
    }

    @Test
    fun doesNotUseTargetStateObservedBeforeControlInvocationToReleaseTimeout() {
        val operations = ManualExecutor()
        val fixture = Fixture(operationExecutor = operations)
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        fixture.setExecution(ExecutionState.PAUSED)
        operations.runNext()
        fixture.scheduler.fire()

        assertEquals(
            ExecutionRejection.OPERATION_UNCONFIRMED,
            assertIs<ExecutionRequestResult.Rejected>(fixture.executor.resume()).reason,
        )
    }

    @Test
    fun acceptsResumeWhenTheMatchingPauseStateArrivedBeforeThePauseReceiptTimedOut() {
        val fixture = Fixture()
        fixture.markExecutionStarted()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
        fixture.setExecution(ExecutionState.PAUSED)
        fixture.executor.observeExecutionState(
            ExecutionState.PAUSED,
            fixture.store.snapshot().missionRevision!!,
            fixture.store.snapshot().deviceGeneration,
        )
        fixture.scheduler.fire()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.resume())
    }

    @Test
    fun doesNotRepeatResumeAfterItsDjiReceiptIsLost() {
        val fixture = Fixture()
        fixture.markExecutionStarted()
        fixture.setExecution(ExecutionState.PAUSED)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.resume())
        fixture.scheduler.fire()

        assertEquals(
            ExecutionRejection.OPERATION_UNCONFIRMED,
            assertIs<ExecutionRequestResult.Rejected>(fixture.executor.resume()).reason,
        )
        fixture.setExecution(ExecutionState.EXECUTING)
        fixture.executor.observeExecutionState(
            ExecutionState.EXECUTING,
            fixture.store.snapshot().missionRevision!!,
            fixture.store.snapshot().deviceGeneration,
        )
        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
    }

    @Test
    fun acceptsPauseWhenTheMatchingResumeStateArrivedBeforeTheResumeReceiptTimedOut() {
        val fixture = Fixture()
        fixture.markExecutionStarted()
        fixture.setExecution(ExecutionState.PAUSED)

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.resume())
        fixture.setExecution(ExecutionState.EXECUTING)
        fixture.executor.observeExecutionState(
            ExecutionState.EXECUTING,
            fixture.store.snapshot().missionRevision!!,
            fixture.store.snapshot().deviceGeneration,
        )
        fixture.scheduler.fire()

        assertIs<ExecutionRequestResult.Accepted>(fixture.executor.pause())
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

    private class Fixture(
        ready: Boolean = true,
        operationExecutor: OperationExecutor = OperationExecutor { it() },
    ) {
        val store = MissionStateStore.create()
        val port = Port()
        val scheduler = Scheduler()
        private val executionStateRevision = AtomicLong(0)
        private val coordinator = DjiOperationCoordinator.create(
            executor = operationExecutor,
            scheduler = scheduler,
        )
        val executor = MissionExecutor.create(
            stateStore = store,
            controlPort = port,
            coordinator = coordinator,
            executionSourceRevision = executionStateRevision,
        )

        init {
            if (ready) {
                stageOnly()
                store.apply(
                    MissionStateEvent.UploadChanged(
                        1,
                        store.snapshot().missionRevision!!,
                        store.snapshot().deviceGeneration,
                        UploadState.UPLOADED,
                    ),
                )
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

        fun markExecutionStarted() {
            setExecution(ExecutionState.EXECUTING)
        }

        fun setExecution(state: ExecutionState) {
            store.apply(
                MissionStateEvent.ExecutionChanged(
                    executionStateRevision.incrementAndGet(),
                    store.snapshot().missionRevision!!,
                    store.snapshot().deviceGeneration,
                    state,
                ),
            )
        }
    }

    private class Port : MissionControlPort {
        var completion: ControlCompletion? = null
        var throwOnCall = false
        var startCalls = 0
        override fun start(completion: ControlCompletion) {
            startCalls += 1
            call(completion)
        }
        override fun pause(completion: ControlCompletion) = call(completion)
        override fun resume(completion: ControlCompletion) = call(completion)
        var stopCalls = 0
        override fun stop(completion: ControlCompletion) {
            stopCalls += 1
            call(completion)
        }
        private fun call(completion: ControlCompletion) {
            if (throwOnCall) error("adapter failure")
            this.completion = completion
        }
        fun completeSuccess() { completion!!.succeed() }
        fun completeFailure(failure: MissionControlFailure? = null) { completion!!.fail(failure) }
    }

    private class Scheduler : OperationScheduler {
        var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            this.callback = callback
            return OperationCancellation {}
        }
        fun fire() { callback?.invoke() }
    }

    private class ManualExecutor : OperationExecutor {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun execute(task: () -> Unit) {
            tasks += task
        }

        fun runNext() = tasks.removeFirst()()
    }

    private fun metadata(name: String = "mission.kmz") = MissionMetadata(name, 3, "a".repeat(64))
}
