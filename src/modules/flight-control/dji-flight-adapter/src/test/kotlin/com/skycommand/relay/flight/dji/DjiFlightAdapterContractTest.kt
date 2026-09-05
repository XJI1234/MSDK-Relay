package com.skycommand.relay.flight.dji

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.flight.command.FlightAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DjiFlightAdapterContractTest {
    @Test
    fun submitsActionsSeriallyAndMapsSdkCompletionOnce() {
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, Scheduler()), 1_000)
        val outcomes = mutableListOf<FlightDjiTerminalResult>()

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF) { outcomes += it })
        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.LAND) { outcomes += it })
        executor.runNext()
        assertEquals(listOf(FlightAction.TAKEOFF), port.actions)
        port.succeed()
        port.succeed()
        executor.runNext()

        assertEquals(listOf(FlightAction.TAKEOFF, FlightAction.LAND), port.actions)
        assertEquals(listOf(FlightDjiTerminalResult(FlightDjiTerminalOutcome.SUCCEEDED)), outcomes)
    }

    @Test
    fun blocksFurtherNormalFlightActionsUntilTimedOutFlightActionIsResolved() {
        val scheduler = Scheduler()
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, scheduler), 1_000)
        val outcomes = mutableListOf<FlightDjiTerminalResult>()
        val accepted = assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF) { outcomes += it })
        executor.runNext()
        scheduler.fire()
        assertEquals(listOf(FlightDjiTerminalResult(FlightDjiTerminalOutcome.TIMED_OUT)), outcomes)

        assertIs<FlightSubmissionResult.Rejected>(adapter.execute(FlightAction.TAKEOFF) { outcomes += it })
        assertEquals(listOf(FlightDjiTerminalResult(FlightDjiTerminalOutcome.TIMED_OUT)), outcomes)
        port.succeed()
        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.LAND) { outcomes += it })
        accepted.cancellation.cancel()
    }

    @Test
    fun submitsLandingAfterAnUnconfirmedEarlierFlightActionWithoutLettingItsLateCompletionOverwriteLanding() {
        val scheduler = Scheduler()
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, scheduler), 1_000)
        val outcomes = mutableListOf<FlightDjiTerminalResult>()

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF) { outcomes += it })
        executor.runNext()
        scheduler.fire()
        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.LAND) { outcomes += it })
        executor.runNext()

        assertEquals(listOf(FlightAction.TAKEOFF, FlightAction.LAND), port.actions)
        port.succeed(0)
        assertEquals(listOf(FlightDjiTerminalResult(FlightDjiTerminalOutcome.TIMED_OUT)), outcomes)
        port.succeed(1)
        assertEquals(
            listOf(
                FlightDjiTerminalResult(FlightDjiTerminalOutcome.TIMED_OUT),
                FlightDjiTerminalResult(FlightDjiTerminalOutcome.SUCCEEDED),
            ),
            outcomes,
        )
    }

    @Test
    fun submitsReturnHomeAfterAnUnconfirmedEarlierFlightAction() {
        val scheduler = Scheduler()
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, scheduler), 1_000)

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF))
        executor.runNext()
        scheduler.fire()
        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.RETURN_HOME))
        executor.runNext()

        assertEquals(listOf(FlightAction.TAKEOFF, FlightAction.RETURN_HOME), port.actions)
    }

    @Test
    fun releasesATimedOutTakeoffAfterANewerFactInTheSameObservationGenerationConfirmsFlight() {
        val scheduler = Scheduler()
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, scheduler), 1_000)
        adapter.observeState(FlightActionState(4, 18, isFlying = false, motorsOn = false, flightMode = "GPS_ATTI"))

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF))
        executor.runNext()
        scheduler.fire()
        adapter.observeState(FlightActionState(4, 19, isFlying = true, motorsOn = true, flightMode = "AUTO_TAKE_OFF"))

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF))
    }

    @Test
    fun doesNotReleaseTimedOutTakeoffFromAnOldObservationGeneration() {
        val scheduler = Scheduler()
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, scheduler), 1_000)
        adapter.observeState(FlightActionState(4, 18, isFlying = false, motorsOn = false, flightMode = "GPS_ATTI"))

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF))
        executor.runNext()
        scheduler.fire()
        adapter.observeState(FlightActionState(3, 19, isFlying = true, motorsOn = true, flightMode = "AUTO_TAKE_OFF"))

        assertIs<FlightSubmissionResult.Rejected>(adapter.execute(FlightAction.TAKEOFF))
    }

    @Test
    fun doesNotReleaseTimeoutFromAFactObservedBeforeTheInvocationBoundary() {
        val scheduler = Scheduler()
        val executor = ManualExecutor()
        val port = Port()
        lateinit var adapter: DjiFlightAdapter
        adapter = DjiFlightAdapter.createForTest(
            port,
            DjiOperationCoordinator.create(executor, scheduler),
            1_000,
        ) {
            adapter.observeState(FlightActionState(4, 19, isFlying = true, motorsOn = true, flightMode = "AUTO_TAKE_OFF"))
        }
        adapter.observeState(FlightActionState(4, 18, isFlying = false, motorsOn = false, flightMode = "GPS_ATTI"))

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF))
        executor.runNext()
        scheduler.fire()

        assertIs<FlightSubmissionResult.Rejected>(adapter.execute(FlightAction.TAKEOFF))
    }

    @Test
    fun forwardsTheDjiFailureSummaryWithOnlyTheFailedTerminalOutcome() {
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, Scheduler()), 1_000)
        val outcomes = mutableListOf<FlightDjiTerminalResult>()
        val failure = FlightDjiFailure.fromDjiError("COMMON_SYSTEM_BUSY", "The aircraft is busy")

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF) { outcomes += it })
        executor.runNext()
        port.fail(failure)

        assertEquals(listOf(FlightDjiTerminalResult(FlightDjiTerminalOutcome.FAILED, failure)), outcomes)
    }

    @Test
    fun normalizesDjiFailureTextsBeforeTheyCanCrossTheAndroidBoundary() {
        val failure = FlightDjiFailure.fromDjiError(
            "C".repeat(129) + "\n",
            "D".repeat(513) + "\u0000",
        )

        assertEquals(128, failure.errorCode.length)
        assertEquals(512, failure.errorDescription.length)
        assertTrue(failure.errorCode.none(Char::isISOControl))
        assertTrue(failure.errorDescription.none(Char::isISOControl))
    }

    private class Port : DjiFlightPort {
        val actions = mutableListOf<FlightAction>()
        private val completions = mutableListOf<FlightDjiCompletion>()
        override fun execute(action: FlightAction, completion: FlightDjiCompletion) { actions += action; completions += completion }
        fun succeed(index: Int = completions.lastIndex) = completions[index].succeed()
        fun fail(failure: FlightDjiFailure) = completions.last().fail(failure)
    }
    private class ManualExecutor : OperationExecutor {
        private val tasks = ArrayDeque<() -> Unit>(); override fun execute(task: () -> Unit) { tasks += task }; fun runNext() = tasks.removeFirst()()
    }
    private class Scheduler : OperationScheduler {
        private var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation { this.callback = callback; return OperationCancellation { } }
        fun fire() = checkNotNull(callback).invoke()
    }
}
