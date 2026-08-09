package com.skycommand.relay.gateway.session

import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutorOrderedStateNotifierTest {

    @Test
    fun notificationIsDeferredAndPreservesEventThenRegistrationOrder() {
        val executor = ManualExecutor()
        val diagnostics = RecordingDiagnosticSink()
        val notifier = ExecutorOrderedStateNotifier(executor, diagnostics)
        val observed = mutableListOf<String>()
        val first = SessionStateListener { event -> observed += "first:${event.snapshot.state}" }
        val second = SessionStateListener { event -> observed += "second:${event.snapshot.state}" }

        notifier.enqueue(event(SessionState.CONNECTING), listOf(first, second))
        notifier.enqueue(event(SessionState.AWAITING_PAIRING), listOf(first, second))

        assertEquals(emptyList(), observed)
        executor.runAll()
        assertEquals(
            listOf(
                "first:CONNECTING",
                "second:CONNECTING",
                "first:AWAITING_PAIRING",
                "second:AWAITING_PAIRING",
            ),
            observed,
        )
    }

    @Test
    fun listenerFailureDoesNotBlockRemainingListenersOrEvents() {
        val executor = ManualExecutor()
        val diagnostics = RecordingDiagnosticSink()
        val notifier = ExecutorOrderedStateNotifier(executor, diagnostics)
        val observed = mutableListOf<SessionState>()
        val failing = SessionStateListener { throw IllegalStateException("listener secret") }
        val healthy = SessionStateListener { observed += it.snapshot.state }

        notifier.enqueue(event(SessionState.CONNECTING), listOf(failing, healthy))
        notifier.enqueue(event(SessionState.ACTIVE), listOf(failing, healthy))
        executor.runAll()

        assertEquals(listOf(SessionState.CONNECTING, SessionState.ACTIVE), observed)
        assertEquals(2, diagnostics.diagnostics.count { it.kind == SessionDiagnosticKind.LISTENER_FAILURE })
        assertTrue(diagnostics.diagnostics.none { it.detail.contains("secret") })
    }

    @Test
    fun listenerCanEnqueueAnotherEventWithoutReordering() {
        val executor = ManualExecutor()
        val notifier = ExecutorOrderedStateNotifier(executor, RecordingDiagnosticSink())
        val observed = mutableListOf<SessionState>()
        lateinit var listener: SessionStateListener
        listener = SessionStateListener { current ->
            observed += current.snapshot.state
            if (current.snapshot.state == SessionState.CONNECTING) {
                notifier.enqueue(event(SessionState.ACTIVE), listOf(listener))
            }
        }

        notifier.enqueue(event(SessionState.CONNECTING), listOf(listener))
        executor.runAll()

        assertEquals(listOf(SessionState.CONNECTING, SessionState.ACTIVE), observed)
    }

    @Test
    fun executorRejectionRetainsEventsForTheNextSuccessfulDispatch() {
        val executor = RejectOnceExecutor()
        val diagnostics = RecordingDiagnosticSink()
        val notifier = ExecutorOrderedStateNotifier(executor, diagnostics)
        val observed = mutableListOf<SessionState>()
        val listener = SessionStateListener { observed += it.snapshot.state }

        notifier.enqueue(event(SessionState.CONNECTING), listOf(listener))
        notifier.enqueue(event(SessionState.AWAITING_PAIRING), listOf(listener))
        executor.runAll()

        assertEquals(listOf(SessionState.CONNECTING, SessionState.AWAITING_PAIRING), observed)
        assertEquals(1, diagnostics.diagnostics.count { it.kind == SessionDiagnosticKind.DEPENDENCY_FAILURE })
    }

    @Test
    fun rejectionAfterConcurrentEnqueueRetriesTheStrandedQueue() {
        val executor = RejectAfterBlockingFirstCallExecutor()
        val notifier = ExecutorOrderedStateNotifier(executor, RecordingDiagnosticSink())
        val observed = mutableListOf<SessionState>()
        val listener = SessionStateListener { observed += it.snapshot.state }
        val firstEnqueue = thread(start = true) {
            notifier.enqueue(event(SessionState.CONNECTING), listOf(listener))
        }

        assertTrue(executor.firstCallEntered.await(2, TimeUnit.SECONDS))
        notifier.enqueue(event(SessionState.AWAITING_PAIRING), listOf(listener))
        executor.releaseFirstCall.countDown()
        firstEnqueue.join(2_000)
        assertTrue(!firstEnqueue.isAlive)

        executor.runAccepted()

        assertEquals(listOf(SessionState.CONNECTING, SessionState.AWAITING_PAIRING), observed)
    }

    private fun event(state: SessionState): SessionStateEvent = SessionStateEvent(
        previousState = SessionState.STOPPED,
        snapshot = SessionSnapshot(state, null, null),
        endReason = null,
    )
}

internal class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }
}

private class RejectOnceExecutor : Executor {
    private val delegate = ManualExecutor()
    private var rejectNext = true

    override fun execute(command: Runnable) {
        if (rejectNext) {
            rejectNext = false
            throw IllegalStateException("executor secret")
        }
        delegate.execute(command)
    }

    fun runAll() = delegate.runAll()
}

private class RejectAfterBlockingFirstCallExecutor : Executor {
    val firstCallEntered = CountDownLatch(1)
    val releaseFirstCall = CountDownLatch(1)
    private val callCount = AtomicInteger()
    private val acceptedTask = AtomicReference<Runnable?>()

    override fun execute(command: Runnable) {
        if (callCount.getAndIncrement() == 0) {
            firstCallEntered.countDown()
            releaseFirstCall.await()
            throw IllegalStateException("executor temporarily unavailable")
        }
        check(acceptedTask.compareAndSet(null, command)) { "Unexpected concurrent accepted task" }
    }

    fun runAccepted() {
        acceptedTask.getAndSet(null)?.run() ?: error("No retry task was accepted")
    }
}
