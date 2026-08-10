package com.skycommand.relay.gateway.session

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Ordered notifier backed by a deferred executor.
 *
 * The executor must enqueue work for later execution on a thread independent of
 * the session event loop. If it temporarily rejects work, pending events stay
 * in this notifier and are retried when a later event is enqueued.
 */
class ExecutorOrderedStateNotifier(
    private val executor: Executor,
    private val diagnosticSink: SessionDiagnosticSink = SessionDiagnosticSink { },
) : OrderedStateNotifier {
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<PendingNotification>()
    private var drainScheduled = false
    private var queueVersion = 0L

    override fun enqueue(event: SessionStateEvent, listeners: List<SessionStateListener>) {
        val scheduleVersion = lock.withLock {
            queue.addLast(PendingNotification(event, listeners.toList()))
            queueVersion += 1
            if (drainScheduled) {
                null
            } else {
                drainScheduled = true
                queueVersion
            }
        }
        if (scheduleVersion != null) {
            scheduleDrain(event.snapshot.state, scheduleVersion)
        }
    }

    private fun scheduleDrain(state: SessionState, initialVersion: Long) {
        var observedVersion = initialVersion
        while (true) {
            try {
                executor.execute(::drain)
                return
            } catch (_: Throwable) {
                // Hand off scheduling when an enqueue raced with the rejected attempt.
                val retry = lock.withLock {
                    drainScheduled = false
                    if (queue.isNotEmpty() && queueVersion != observedVersion) {
                        observedVersion = queueVersion
                        drainScheduled = true
                        true
                    } else {
                        false
                    }
                }
                recordDiagnostic(
                    SessionDiagnosticKind.DEPENDENCY_FAILURE,
                    state,
                    "State notification executor rejected work",
                )
                if (!retry) {
                    return
                }
            }
        }
    }

    private fun drain() {
        while (true) {
            val pending = lock.withLock {
                if (queue.isEmpty()) {
                    drainScheduled = false
                    null
                } else {
                    queue.removeFirst()
                }
            } ?: return

            pending.listeners.forEach { listener ->
                try {
                    listener.onStateChanged(pending.event)
                } catch (_: Throwable) {
                    recordDiagnostic(
                        SessionDiagnosticKind.LISTENER_FAILURE,
                        pending.event.snapshot.state,
                        "State listener failed",
                    )
                }
            }
        }
    }

    private fun recordDiagnostic(
        kind: SessionDiagnosticKind,
        state: SessionState,
        detail: String,
    ) {
        runCatching { diagnosticSink.record(SessionDiagnostic(kind, state, detail)) }
    }

    private data class PendingNotification(
        val event: SessionStateEvent,
        val listeners: List<SessionStateListener>,
    )
}
