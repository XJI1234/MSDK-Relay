package com.skycommand.relay.flight.dji

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.flight.command.FlightAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DjiFlightAdapterContractTest {
    @Test
    fun submitsActionsSeriallyAndMapsSdkCompletionOnce() {
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, Scheduler()), 1_000)
        val outcomes = mutableListOf<FlightDjiTerminalOutcome>()

        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF) { outcomes += it })
        assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.LAND) { outcomes += it })
        executor.runNext()
        assertEquals(listOf(FlightAction.TAKEOFF), port.actions)
        port.succeed()
        port.succeed()
        executor.runNext()

        assertEquals(listOf(FlightAction.TAKEOFF, FlightAction.LAND), port.actions)
        assertEquals(listOf(FlightDjiTerminalOutcome.SUCCEEDED), outcomes)
    }

    @Test
    fun mapsTimeoutCancellationAndPortExceptionToSafeTerminalFailures() {
        val scheduler = Scheduler()
        val executor = ManualExecutor()
        val port = Port()
        val adapter = DjiFlightAdapter.create(port, DjiOperationCoordinator.create(executor, scheduler), 1_000)
        val outcomes = mutableListOf<FlightDjiTerminalOutcome>()
        val accepted = assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.TAKEOFF) { outcomes += it })
        executor.runNext()
        scheduler.fire()
        assertEquals(listOf(FlightDjiTerminalOutcome.TIMED_OUT), outcomes)

        val cancelled = assertIs<FlightSubmissionResult.Accepted>(adapter.execute(FlightAction.LAND) { outcomes += it })
        assertEquals(com.skycommand.relay.device.operation.CancellationResult.Cancelled, cancelled.cancellation.cancel())
        assertEquals(listOf(FlightDjiTerminalOutcome.TIMED_OUT, FlightDjiTerminalOutcome.CANCELLED), outcomes)
        accepted.cancellation.cancel()
    }

    private class Port : DjiFlightPort {
        val actions = mutableListOf<FlightAction>(); private var completion: FlightDjiCompletion? = null
        override fun execute(action: FlightAction, completion: FlightDjiCompletion) { actions += action; this.completion = completion }
        fun succeed() = checkNotNull(completion).succeed()
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
