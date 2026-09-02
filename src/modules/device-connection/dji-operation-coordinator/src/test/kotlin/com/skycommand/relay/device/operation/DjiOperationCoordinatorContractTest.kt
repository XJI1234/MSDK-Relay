package com.skycommand.relay.device.operation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DjiOperationCoordinatorContractTest {

    @Test
    fun startsAcceptedOperationsOneAtATimeAndAdvancesAfterCompletion() {
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        val coordinator = DjiOperationCoordinator.create(executor, scheduler)
        val first = RecordingAction()
        val second = RecordingAction()
        val results = mutableListOf<OperationOutcome>()

        coordinator.submit(first, 1_000) { results += it }
        coordinator.submit(second, 1_000) { results += it }
        executor.runNext()
        assertEquals(1, first.starts)
        assertEquals(0, second.starts)
        first.succeed()
        executor.runNext()
        assertEquals(1, second.starts)
        second.fail()

        assertEquals(listOf(OperationOutcome.SUCCEEDED, OperationOutcome.FAILED), results)
    }

    @Test
    fun timesOutWithoutStartingQueuedWorkUntilTheLateDjiCompletionArrives() {
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        val coordinator = DjiOperationCoordinator.create(executor, scheduler)
        val first = RecordingAction()
        val second = RecordingAction()
        val afterRecovery = RecordingAction()
        val results = mutableListOf<OperationOutcome>()

        coordinator.submit(first, 1_000) { results += it }
        coordinator.submit(second, 1_000) { results += it }
        executor.runNext()
        scheduler.fireNext()

        assertEquals(listOf(OperationOutcome.TIMED_OUT, OperationOutcome.CANCELLED), results)
        assertEquals(0, second.starts)
        assertEquals(0, executor.taskCount())
        assertIs<SubmissionResult.Rejected>(coordinator.submit(RecordingAction(), 1_000) { })

        first.succeed()
        assertEquals(listOf(OperationOutcome.SUCCEEDED), first.lateOutcomes)
        assertIs<SubmissionResult.Accepted>(coordinator.submit(afterRecovery, 1_000) { })
        executor.runNext()

        assertEquals(1, afterRecovery.starts)
    }

    @Test
    fun cancellingStartedWorkDoesNotReleaseTheDjiSlotBeforeItsLateCompletion() {
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        val coordinator = DjiOperationCoordinator.create(executor, scheduler)
        val first = RecordingAction()
        val queued = RecordingAction()
        val results = mutableListOf<OperationOutcome>()

        val firstSubmission = assertIs<SubmissionResult.Accepted>(coordinator.submit(first, 1_000) { results += it })
        coordinator.submit(queued, 1_000) { results += it }
        executor.runNext()

        assertEquals(CancellationResult.Cancelled, firstSubmission.cancellation.cancel())
        assertEquals(listOf(OperationOutcome.CANCELLED, OperationOutcome.CANCELLED), results)
        assertEquals(0, queued.starts)
        assertIs<SubmissionResult.Rejected>(coordinator.submit(RecordingAction(), 1_000) { })

        first.fail()
        val afterRecovery = RecordingAction()
        assertIs<SubmissionResult.Accepted>(coordinator.submit(afterRecovery, 1_000) { })
        executor.runNext()

        assertEquals(1, afterRecovery.starts)
    }

    @Test
    fun authoritativeStateConfirmationReleasesOnlyTheTimedOutOperationSlot() {
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        val coordinator = DjiOperationCoordinator.create(executor, scheduler)
        val first = RecordingAction()
        val afterConfirmation = RecordingAction()
        val results = mutableListOf<OperationOutcome>()

        coordinator.submit(first, 1_000) { results += it }
        executor.runNext()
        scheduler.fireNext()

        assertEquals(listOf(OperationOutcome.TIMED_OUT), results)
        assertEquals(true, first.confirmHardwareSettled())
        assertEquals(false, first.confirmHardwareSettled())
        assertIs<SubmissionResult.Accepted>(coordinator.submit(afterConfirmation, 1_000) { })
        executor.runNext()

        assertEquals(1, afterConfirmation.starts)
    }

    @Test
    fun cancelsQueuedWorkWithoutStartingItAndRejectsInvalidTimeouts() {
        val executor = ManualExecutor()
        val coordinator = DjiOperationCoordinator.create(executor, ManualScheduler())
        val first = RecordingAction()
        val queued = RecordingAction()
        val results = mutableListOf<OperationOutcome>()

        coordinator.submit(first, 1_000) { results += it }
        val accepted = assertIs<SubmissionResult.Accepted>(coordinator.submit(queued, 1_000) { results += it })
        assertEquals(CancellationResult.Cancelled, accepted.cancellation.cancel())
        assertIs<SubmissionResult.Rejected>(coordinator.submit(RecordingAction(), 999) { })
        executor.runNext()
        first.succeed()

        assertEquals(0, queued.starts)
        assertEquals(listOf(OperationOutcome.CANCELLED, OperationOutcome.SUCCEEDED), results)
    }

    private class RecordingAction : DjiOperation {
        var starts = 0
        val lateOutcomes = mutableListOf<OperationOutcome>()
        private var completion: OperationCompletion? = null

        override fun run(completion: OperationCompletion) {
            starts += 1
            this.completion = completion
        }

        override fun onLateDjiCompletion(outcome: OperationOutcome) { lateOutcomes += outcome }

        fun succeed() = checkNotNull(completion).succeed()

        fun fail() = checkNotNull(completion).fail()

        fun confirmHardwareSettled() = checkNotNull(completion).confirmHardwareSettled()
    }

    private class ManualExecutor : OperationExecutor {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        fun runNext() = tasks.removeFirst().invoke()
        fun taskCount(): Int = tasks.size
    }

    private class ManualScheduler : OperationScheduler {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            var cancelled = false
            tasks.addLast { if (!cancelled) callback() }
            return OperationCancellation { cancelled = true }
        }
        fun fireNext() = tasks.removeFirst().invoke()
    }
}
