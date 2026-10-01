package com.skycommand.relay.photo.executor

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.command.PhotoDjiFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhotoExecutorContractTest {
    @Test
    fun returnsCaptureIdentityAndDoesNotStartDownloadUntilTheSlotIsFree() {
        val executor = ManualExecutor()
        val port = Port()
        val photo = PhotoExecutor.create(port, DjiOperationCoordinator.create(executor, Scheduler()))
        val outcomes = mutableListOf<PhotoExecutionOutcome>()

        assertIs<PhotoSubmissionResult.Accepted>(photo.execute(PhotoHardwareRequest.Capture) { outcomes += it })
        assertIs<PhotoSubmissionResult.Accepted>(
            photo.execute(PhotoHardwareRequest.Download(PhotoCaptureIdentity("shot.jpg", 1))) { outcomes += it },
        )
        executor.runNext()
        assertEquals(1, port.requests.size)
        port.captured(PhotoCaptureIdentity("shot.jpg", 1))
        executor.runNext()

        assertEquals(2, port.requests.size)
        assertEquals(1, outcomes.size)
        assertEquals(PhotoExecutionOutcome.Captured(PhotoCaptureIdentity("shot.jpg", 1)), outcomes.first())
    }

    @Test
    fun mapsTimeoutAndMismatchedSuccessIntoStableTerminalOutcomes() {
        val executor = ManualExecutor()
        val scheduler = Scheduler()
        val port = Port()
        val photo = PhotoExecutor.create(port, DjiOperationCoordinator.create(executor, scheduler))
        val outcomes = mutableListOf<PhotoExecutionOutcome>()

        assertIs<PhotoSubmissionResult.Accepted>(photo.execute(PhotoHardwareRequest.Capture) { outcomes += it })
        executor.runNext()
        port.delivered(file("shot.jpg"))
        assertEquals(listOf<PhotoExecutionOutcome>(PhotoExecutionOutcome.Failed), outcomes)

        assertIs<PhotoSubmissionResult.Accepted>(photo.execute(PhotoHardwareRequest.Capture) { outcomes += it })
        executor.runNext()
        scheduler.fire()
        assertEquals(PhotoExecutionOutcome.TimedOut, outcomes.last())
        assertEquals(1, port.abortCount)

        assertIs<PhotoSubmissionResult.Accepted>(
            photo.execute(PhotoHardwareRequest.Download(PhotoCaptureIdentity("shot.jpg", 1))) { outcomes += it },
        )
        executor.runNext()
        assertEquals(3, port.requests.size)
    }

    @Test
    fun forwardsOnlyARealDjiFailureSummaryToTheExecutionListener() {
        val executor = ManualExecutor()
        val port = Port()
        val photo = PhotoExecutor.create(port, DjiOperationCoordinator.create(executor, Scheduler()))
        val failure = PhotoDjiFailure.fromDjiError("CAMERA_BUSY", "The camera is busy")
        var received: PhotoDjiFailure? = null

        assertIs<PhotoSubmissionResult.Accepted>(
            photo.execute(
                PhotoHardwareRequest.Capture,
                object : PhotoExecutionListener {
                    override fun onCompleted(outcome: PhotoExecutionOutcome) = Unit
                    override fun onCompleted(outcome: PhotoExecutionOutcome, failure: PhotoDjiFailure?) {
                        received = failure
                    }
                },
            ),
        )
        executor.runNext()
        port.fail(failure)

        assertEquals(failure, received)
    }

    @Test
    fun timeoutAllowsAnotherAttemptWithoutLettingTheOldCallbackCompleteIt() {
        val executor = ManualExecutor()
        val scheduler = Scheduler()
        val port = Port()
        val photo = PhotoExecutor.create(port, DjiOperationCoordinator.create(executor, scheduler))
        val outcomes = mutableListOf<PhotoExecutionOutcome>()

        assertIs<PhotoSubmissionResult.Accepted>(photo.execute(PhotoHardwareRequest.Capture) { outcomes += it })
        executor.runNext()
        val first = port.completions.first()
        scheduler.fire()
        assertEquals(listOf<PhotoExecutionOutcome>(PhotoExecutionOutcome.TimedOut), outcomes)
        assertEquals(listOf(first), port.aborted)

        assertIs<PhotoSubmissionResult.Accepted>(photo.execute(PhotoHardwareRequest.Capture) { outcomes += it })
        executor.runNext()
        first.captured(PhotoCaptureIdentity("late.jpg", 1))
        assertEquals(listOf<PhotoExecutionOutcome>(PhotoExecutionOutcome.TimedOut), outcomes)

        val failure = PhotoDjiFailure.fromDjiError("CAMERA_BUSY", "Camera busy")
        port.fail(failure)
        assertEquals(listOf(PhotoExecutionOutcome.TimedOut, PhotoExecutionOutcome.Failed), outcomes)
        assertIs<PhotoSubmissionResult.Accepted>(photo.execute(PhotoHardwareRequest.Capture) { outcomes += it })
        executor.runNext()
        port.captured(PhotoCaptureIdentity("new.jpg", 2))
        assertEquals(PhotoExecutionOutcome.Captured(PhotoCaptureIdentity("new.jpg", 2)), outcomes.last())
    }

    @Test
    fun exposesASeparateHardwareReleaseNotificationForCancelledPhotoWork() {
        assertEquals(
            1,
            PhotoExecutionListener::class.java.methods.count { method -> method.name == "onHardwareReleased" },
        )
    }

    private fun file(name: String) = PhotoLocalFile(name, 1, "a".repeat(64), PhotoReadable { byteArrayOf(1).inputStream() })

    private class Port : DjiPhotoPort {
        val requests = mutableListOf<PhotoHardwareRequest>()
        var abortCount = 0
        val aborted = mutableListOf<PhotoDjiCompletion>()
        val completions = mutableListOf<PhotoDjiCompletion>()
        private var completion: PhotoDjiCompletion? = null
        override fun execute(request: PhotoHardwareRequest, completion: PhotoDjiCompletion) {
            requests += request
            this.completion = completion
            completions += completion
        }
        override fun abort(completion: PhotoDjiCompletion) {
            aborted += completion
            abortCount += 1
        }
        fun captured(identity: PhotoCaptureIdentity) = checkNotNull(completion).captured(identity)
        fun delivered(file: PhotoLocalFile) = checkNotNull(completion).delivered(file)
        fun fail(failure: PhotoDjiFailure? = null) = checkNotNull(completion).fail(failure)
    }

    private class ManualExecutor : OperationExecutor {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks += task }
        fun runNext() = tasks.removeFirst()()
    }

    private class Scheduler : OperationScheduler {
        private var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            this.callback = callback
            return OperationCancellation { }
        }
        fun fire() = checkNotNull(callback).invoke()
    }
}
