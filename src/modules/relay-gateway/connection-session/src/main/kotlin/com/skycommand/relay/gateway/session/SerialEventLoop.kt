package com.skycommand.relay.gateway.session

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SerialEventLoop(
    private val onUnhandledFailure: (Throwable) -> Unit,
) {
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false
    private var drainingThread: Thread? = null

    fun execute(action: () -> Unit) {
        val shouldDrain = lock.withLock {
            queue.addLast(action)
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (shouldDrain) {
            drain()
        }
    }

    fun <T> call(action: () -> T): T {
        check(!isDrainingThread()) { "Session control cannot be called reentrantly from a session dependency" }

        val completed = CountDownLatch(1)
        var value: Any? = null
        var failure: Throwable? = null
        execute {
            try {
                value = action()
            } catch (error: Throwable) {
                failure = error
            } finally {
                completed.countDown()
            }
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

    private fun isDrainingThread(): Boolean = lock.withLock { drainingThread === Thread.currentThread() }

    private fun drain() {
        lock.withLock { drainingThread = Thread.currentThread() }
        while (true) {
            val next = lock.withLock {
                if (queue.isEmpty()) {
                    draining = false
                    drainingThread = null
                    null
                } else {
                    queue.removeFirst()
                }
            } ?: return

            try {
                next()
            } catch (error: Throwable) {
                runCatching { onUnhandledFailure(error) }
            }
        }
    }
}
