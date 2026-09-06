package com.skycommand.relay.gateway.session

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SerialEventLoop(
    private val onUnhandledFailure: (Throwable) -> Unit,
) {
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<QueuedAction>()
    private val priorityQueue = ArrayDeque<QueuedAction>()
    private var draining = false
    private var drainingThread: Thread? = null
    private var pendingInbound = 0
    private var priorityPending = false

    fun execute(action: () -> Unit) {
        enqueue(QueuedAction(action, isInbound = false), priority = false)
    }

    /**
     * Queues one network frame only while the bounded waiting area has capacity.
     * The currently executing frame is not included in this limit.
     */
    fun executeInbound(action: () -> Unit): Boolean {
        val shouldDrain = lock.withLock {
            if (pendingInbound >= MAX_PENDING_INBOUND) {
                return false
            }
            pendingInbound += 1
            enqueueLocked(QueuedAction(action, isInbound = true), priority = false)
        }
        if (shouldDrain) {
            drain()
        }
        return true
    }

    /** Queues one terminal control action ahead of unstarted input frames. */
    fun executePriority(action: () -> Unit): Boolean {
        val shouldDrain = lock.withLock {
            if (priorityPending) {
                return false
            }
            priorityPending = true
            enqueueLocked(QueuedAction(action, isInbound = false, isPriority = true, releasesPrioritySlot = true), priority = true)
        }
        if (shouldDrain) {
            drain()
        }
        return true
    }

    /** Removes inbound frames that have not started execution and returns their count. */
    fun discardPendingInbound(): Int = lock.withLock {
        var discarded = 0
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isInbound) {
                iterator.remove()
                pendingInbound -= 1
                discarded += 1
            }
        }
        discarded
    }

    fun <T> call(action: () -> T): T {
        return call(action, priority = false)
    }

    /** Runs a terminal session control action before input frames that have not started. */
    fun <T> callPriority(action: () -> T): T {
        return call(action, priority = true)
    }

    private fun <T> call(action: () -> T, priority: Boolean): T {
        check(!isDrainingThread()) { "Session control cannot be called reentrantly from a session dependency" }

        val completed = CountDownLatch(1)
        var value: Any? = null
        var failure: Throwable? = null
        val queued = {
            try {
                value = action()
            } catch (error: Throwable) {
                failure = error
            } finally {
                completed.countDown()
            }
        }
        if (priority) {
            enqueue(QueuedAction(queued, isInbound = false, isPriority = true), priority = true)
        } else {
            execute(queued)
        }
        try {
            completed.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for the session event loop", interrupted)
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun <T> read(action: () -> T): T = if (isDrainingThread()) action() else call(action)

    private fun isDrainingThread(): Boolean = lock.withLock { drainingThread === Thread.currentThread() }

    private fun drain() {
        lock.withLock { drainingThread = Thread.currentThread() }
        while (true) {
            val next = lock.withLock {
                if (queue.isEmpty() && priorityQueue.isEmpty()) {
                    draining = false
                    drainingThread = null
                    null
                } else {
                    (if (priorityQueue.isNotEmpty()) priorityQueue else queue).removeFirst().also { queued ->
                        if (queued.isInbound) {
                            pendingInbound -= 1
                        }
                        if (queued.releasesPrioritySlot) {
                            priorityPending = false
                        }
                    }
                }
            } ?: return

            try {
                next.action()
            } catch (error: Throwable) {
                runCatching { onUnhandledFailure(error) }
            }
        }
    }

    private fun enqueue(action: QueuedAction, priority: Boolean) {
        val shouldDrain = lock.withLock { enqueueLocked(action, priority) }
        if (shouldDrain) {
            drain()
        }
    }

    private fun enqueueLocked(action: QueuedAction, priority: Boolean): Boolean {
        if (priority) {
            priorityQueue.addLast(action)
        } else {
            queue.addLast(action)
        }
        if (draining) {
            return false
        }
        draining = true
        return true
    }

    private data class QueuedAction(
        val action: () -> Unit,
        val isInbound: Boolean,
        val isPriority: Boolean = false,
        val releasesPrioritySlot: Boolean = false,
    )

    private companion object {
        const val MAX_PENDING_INBOUND = 16
    }
}
