package com.skycommand.relay.device.operation

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface DjiOperation {
    fun run(completion: OperationCompletion)
}

interface OperationCompletion {
    fun succeed()

    fun fail()
}

fun interface OperationExecutor {
    fun execute(task: () -> Unit)
}

fun interface OperationScheduler {
    fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation
}

fun interface OperationCancellation {
    fun cancel()
}

fun interface OperationResultListener {
    fun onComplete(outcome: OperationOutcome)
}

enum class OperationOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface SubmissionResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : SubmissionResult

    data object Rejected : SubmissionResult
}

interface OperationCancellationHandle {
    fun cancel(): CancellationResult
}

sealed interface CancellationResult {
    data object Cancelled : CancellationResult

    data object AlreadyFinished : CancellationResult
}

class DjiOperationCoordinator private constructor(
    private val executor: OperationExecutor,
    private val scheduler: OperationScheduler,
) {
    private val lock = ReentrantLock()
    private val pending = ArrayDeque<Entry>()
    private var running: Entry? = null

    fun submit(
        action: DjiOperation,
        timeoutMillis: Long,
        listener: OperationResultListener,
    ): SubmissionResult {
        if (timeoutMillis !in 1_000..60_000) return SubmissionResult.Rejected
        val entry = Entry(action, timeoutMillis, listener)
        val shouldStart = lock.withLock {
            pending.addLast(entry)
            running == null
        }
        if (shouldStart) startNext()
        return SubmissionResult.Accepted(CancellationHandle(entry))
    }

    private fun startNext() {
        val next: Entry = lock.withLock {
            if (running != null || pending.isEmpty()) {
                return
            }
            pending.removeFirst().also { running = it }
        }

        try {
            executor.execute { begin(next) }
        } catch (_: Throwable) {
            finish(next, OperationOutcome.FAILED)
        }
    }

    private fun begin(entry: Entry) {
        val shouldRun = lock.withLock { running === entry && !entry.finished }
        if (!shouldRun) return

        val timeout = try {
            scheduler.schedule(entry.timeoutMillis) { finish(entry, OperationOutcome.TIMED_OUT) }
        } catch (_: Throwable) {
            finish(entry, OperationOutcome.FAILED)
            return
        }
        val stillRunning = lock.withLock {
            if (running === entry && !entry.finished) {
                entry.timeout = timeout
                true
            } else {
                false
            }
        }
        if (!stillRunning) {
            runCatching { timeout.cancel() }
            return
        }
        try {
            entry.action.run(object : OperationCompletion {
                override fun succeed() = finish(entry, OperationOutcome.SUCCEEDED)

                override fun fail() = finish(entry, OperationOutcome.FAILED)
            })
        } catch (_: Throwable) {
            finish(entry, OperationOutcome.FAILED)
        }
    }

    private fun cancel(entry: Entry): CancellationResult {
        val cancelled = lock.withLock {
            if (entry.finished) return CancellationResult.AlreadyFinished
            if (running === entry) {
                true
            } else if (pending.remove(entry)) {
                true
            } else {
                return CancellationResult.AlreadyFinished
            }
        }
        if (cancelled) finish(entry, OperationOutcome.CANCELLED)
        return CancellationResult.Cancelled
    }

    private fun finish(entry: Entry, outcome: OperationOutcome) {
        var timeout: OperationCancellation? = null
        var startNext = false
        lock.withLock {
            if (entry.finished) return
            entry.finished = true
            timeout = entry.timeout
            entry.timeout = null
            startNext = running === entry
            if (startNext) running = null
        }
        runCatching { timeout?.cancel() }
        runCatching { entry.listener.onComplete(outcome) }
        if (startNext) startNext()
    }

    private inner class CancellationHandle(
        private val entry: Entry,
    ) : OperationCancellationHandle {
        override fun cancel(): CancellationResult = this@DjiOperationCoordinator.cancel(entry)
    }

    private class Entry(
        val action: DjiOperation,
        val timeoutMillis: Long,
        val listener: OperationResultListener,
        var timeout: OperationCancellation? = null,
        var finished: Boolean = false,
    )

    companion object {
        fun create(executor: OperationExecutor, scheduler: OperationScheduler): DjiOperationCoordinator =
            DjiOperationCoordinator(executor, scheduler)
    }
}
