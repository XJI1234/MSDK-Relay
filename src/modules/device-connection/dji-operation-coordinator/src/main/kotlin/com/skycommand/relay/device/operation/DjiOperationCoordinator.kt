package com.skycommand.relay.device.operation

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface DjiOperation {
    fun run(completion: OperationCompletion)

    /**
     * A stable, non-null marker permits this containment operation to replace a
     * timed-out operation with the same marker. Normal DJI operations must use
     * the default: a missing receipt keeps the shared slot quarantined.
     */
    fun unconfirmedRetryMarker(): Any? = null

    /**
     * Runs after this action has timed out or been cancelled after DJI may have received it.
     * The action may use its already-established authoritative state observation to settle the slot.
     */
    fun onHardwareOutcomeUnconfirmed(outcome: OperationOutcome) = Unit

    /**
     * Runs only when DJI finishes after a timeout or cancellation was already delivered.
     * It may arrange recovery work, but cannot report a second terminal result.
     */
    fun onLateDjiCompletion(outcome: OperationOutcome) = Unit
}

interface OperationCompletion {
    fun succeed()

    fun fail()

    /** Only the matching authoritative DJI state observation may use this after a timeout or cancellation. */
    fun confirmHardwareSettled(): Boolean
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
    private var hardwareOutcomeUnconfirmed = false

    fun submit(
        action: DjiOperation,
        timeoutMillis: Long,
        listener: OperationResultListener,
    ): SubmissionResult {
        if (timeoutMillis !in 1_000..60_000) return SubmissionResult.Rejected
        val entry = Entry(action, timeoutMillis, listener)
        val shouldStart = lock.withLock {
            if (hardwareOutcomeUnconfirmed) {
                val unresolved = running ?: return SubmissionResult.Rejected
                val replacementMarker = runCatching { action.unconfirmedRetryMarker() }.getOrNull()
                val unresolvedMarker = runCatching { unresolved.action.unconfirmedRetryMarker() }.getOrNull()
                if (replacementMarker == null || replacementMarker != unresolvedMarker) {
                    return SubmissionResult.Rejected
                }
                // This is deliberately limited to an equivalent containment command.
                // Its prior terminal result was already reported as unconfirmed;
                // a late DJI callback remains isolated from the replacement request.
                unresolved.hardwareSettled = true
                unresolved.superseded = true
                running = null
                hardwareOutcomeUnconfirmed = false
            }
            pending.addLast(entry)
            running == null
        }
        if (shouldStart) startNext()
        return SubmissionResult.Accepted(CancellationHandle(entry))
    }

    private fun startNext() {
        val next: Entry = lock.withLock {
            if (hardwareOutcomeUnconfirmed || running != null || pending.isEmpty()) {
                return
            }
            pending.removeFirst().also { running = it }
        }

        try {
            executor.execute { begin(next) }
        } catch (_: Throwable) {
            finishBeforeDjiInvocation(next, OperationOutcome.FAILED)
        }
    }

    private fun begin(entry: Entry) {
        val timeout = try {
            scheduler.schedule(entry.timeoutMillis) { timeOut(entry) }
        } catch (_: Throwable) {
            finishBeforeDjiInvocation(entry, OperationOutcome.FAILED)
            return
        }

        val shouldRun = lock.withLock {
            if (running === entry && !entry.hardwareSettled && !entry.terminalReported) {
                entry.timeout = timeout
                entry.mayHaveInvokedDji = true
                true
            } else {
                false
            }
        }
        if (!shouldRun) {
            runCatching { timeout.cancel() }
            return
        }
        try {
            entry.action.run(object : OperationCompletion {
                override fun succeed() = finishFromDji(entry, OperationOutcome.SUCCEEDED)

                override fun fail() = finishFromDji(entry, OperationOutcome.FAILED)

                override fun confirmHardwareSettled(): Boolean = confirmHardwareSettled(entry)
            })
        } catch (_: Throwable) {
            reportUnconfirmedHardwareOutcome(entry, OperationOutcome.FAILED)
        }
    }

    private fun cancel(entry: Entry): CancellationResult {
        val runningOperation: Boolean? = lock.withLock {
            if (entry.terminalReported) return CancellationResult.AlreadyFinished
            when {
                running === entry -> entry.mayHaveInvokedDji
                pending.remove(entry) -> {
                    entry.terminalReported = true
                    entry.hardwareSettled = true
                    null
                }
                else -> return CancellationResult.AlreadyFinished
            }
        }
        when (runningOperation) {
            true -> reportUnconfirmedHardwareOutcome(entry, OperationOutcome.CANCELLED)
            false -> finishBeforeDjiInvocation(entry, OperationOutcome.CANCELLED)
            null -> runCatching { entry.listener.onComplete(OperationOutcome.CANCELLED) }
        }
        return CancellationResult.Cancelled
    }

    private fun timeOut(entry: Entry) {
        val mayHaveInvokedDji = lock.withLock {
            running === entry && !entry.hardwareSettled && !entry.terminalReported && entry.mayHaveInvokedDji
        }
        if (mayHaveInvokedDji) {
            reportUnconfirmedHardwareOutcome(entry, OperationOutcome.TIMED_OUT)
        } else {
            finishBeforeDjiInvocation(entry, OperationOutcome.TIMED_OUT)
        }
    }

    private fun finishBeforeDjiInvocation(entry: Entry, outcome: OperationOutcome) {
        var timeout: OperationCancellation? = null
        var startNext = false
        var listener: OperationResultListener? = null
        lock.withLock {
            if (entry.hardwareSettled || entry.mayHaveInvokedDji) return
            entry.hardwareSettled = true
            if (!entry.terminalReported) {
                entry.terminalReported = true
                listener = entry.listener
            }
            timeout = entry.timeout
            entry.timeout = null
            startNext = running === entry
            if (startNext) running = null
        }
        runCatching { timeout?.cancel() }
        listener?.let { runCatching { it.onComplete(outcome) } }
        if (startNext) startNext()
    }

    private fun reportUnconfirmedHardwareOutcome(entry: Entry, outcome: OperationOutcome) {
        var timeout: OperationCancellation? = null
        var listener: OperationResultListener? = null
        var action: DjiOperation? = null
        val cancelledPending = mutableListOf<Entry>()
        lock.withLock {
            if (running !== entry || entry.hardwareSettled || entry.terminalReported) return
            entry.terminalReported = true
            timeout = entry.timeout
            entry.timeout = null
            hardwareOutcomeUnconfirmed = true
            listener = entry.listener
            action = entry.action
            while (pending.isNotEmpty()) {
                pending.removeFirst().also {
                    it.terminalReported = true
                    it.hardwareSettled = true
                    cancelledPending += it
                }
            }
        }
        runCatching { timeout?.cancel() }
        listener?.let { runCatching { it.onComplete(outcome) } }
        cancelledPending.forEach { pendingEntry ->
            runCatching { pendingEntry.listener.onComplete(OperationOutcome.CANCELLED) }
        }
        action?.let { runCatching { it.onHardwareOutcomeUnconfirmed(outcome) } }
    }

    private fun finishFromDji(entry: Entry, outcome: OperationOutcome) {
        var timeout: OperationCancellation? = null
        var listener: OperationResultListener? = null
        var lateCompletion: DjiOperation? = null
        var startNext = false
        var completed = false
        lock.withLock {
            if (running !== entry || entry.hardwareSettled) {
                if (entry.superseded && !entry.lateCompletionReported) {
                    entry.lateCompletionReported = true
                    lateCompletion = entry.action
                }
                return@withLock
            }
            entry.hardwareSettled = true
            if (!entry.terminalReported) {
                entry.terminalReported = true
                listener = entry.listener
            } else {
                lateCompletion = entry.action
            }
            timeout = entry.timeout
            entry.timeout = null
            running = null
            hardwareOutcomeUnconfirmed = false
            startNext = pending.isNotEmpty()
            completed = true
        }
        if (!completed) {
            lateCompletion?.let { runCatching { it.onLateDjiCompletion(outcome) } }
            return
        }
        runCatching { timeout?.cancel() }
        listener?.let { runCatching { it.onComplete(outcome) } }
        lateCompletion?.let { runCatching { it.onLateDjiCompletion(outcome) } }
        if (startNext) startNext()
    }

    private fun confirmHardwareSettled(entry: Entry): Boolean {
        var timeout: OperationCancellation? = null
        lock.withLock {
            if (
                running !== entry ||
                entry.hardwareSettled ||
                !entry.terminalReported ||
                !hardwareOutcomeUnconfirmed
            ) {
                return false
            }
            entry.hardwareSettled = true
            timeout = entry.timeout
            entry.timeout = null
            running = null
            hardwareOutcomeUnconfirmed = false
        }
        runCatching { timeout?.cancel() }
        return true
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
        var terminalReported: Boolean = false,
        var hardwareSettled: Boolean = false,
        var mayHaveInvokedDji: Boolean = false,
        var superseded: Boolean = false,
        var lateCompletionReported: Boolean = false,
    )

    companion object {
        fun create(executor: OperationExecutor, scheduler: OperationScheduler): DjiOperationCoordinator =
            DjiOperationCoordinator(executor, scheduler)
    }
}
