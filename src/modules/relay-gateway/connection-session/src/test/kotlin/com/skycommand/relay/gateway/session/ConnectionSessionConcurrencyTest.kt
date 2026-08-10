package com.skycommand.relay.gateway.session

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionSessionConcurrencyTest {

    @Test
    fun concurrentStartCreatesOneAttemptAndOneAcceptedResult() {
        val fixture = SessionFixture.create()
        val results = runConcurrently(24) { fixture.session.start() }

        assertEquals(1, results.count { it == StartResult.StartAccepted })
        assertEquals(23, results.count { it is StartResult.AlreadyRunning })
        assertEquals(1, fixture.connector.openCalls.size)
        assertEquals(SessionState.CONNECTING, fixture.session.snapshot().state)
    }

    @Test
    fun concurrentStopPerformsCleanupExactlyOnce() {
        val fixture = SessionFixture.create()
        fixture.becomeActive()
        fixture.order.clear()

        val results = runConcurrently(24) { fixture.session.stop() }

        assertEquals(1, results.count { it == StopResult.Stopped })
        assertEquals(23, results.count { it == StopResult.AlreadyStopped })
        assertEquals(1, fixture.connector.current.closeCount)
        assertEquals(1, fixture.commandCleanup.calls.size)
        assertEquals(1, fixture.missionCleanup.calls.size)
        assertEquals(1, fixture.outbound.discarded.size)
        assertEquals(SessionState.STOPPED, fixture.session.snapshot().state)
    }

    @Test
    fun listenerRunsLaterAndCanSynchronouslyStopWithoutDeadlock() {
        val executor = ManualExecutor()
        val session = createSessionUsing(executor)
        val listenerStop = AtomicReference<StopResult?>()
        session.onStateChanged { event ->
            if (event.snapshot.state == SessionState.CONNECTING) {
                listenerStop.set(session.stop())
            }
        }

        session.start()

        assertEquals(SessionState.CONNECTING, session.snapshot().state)
        assertEquals(null, listenerStop.get())

        val runner = thread(start = true) { executor.runAll() }
        runner.join(2_000)

        assertFalse(runner.isAlive, "listener-triggered stop deadlocked")
        assertEquals(StopResult.Stopped, listenerStop.get())
        assertEquals(SessionState.STOPPED, session.snapshot().state)
    }

    @Test
    fun slowListenerDoesNotBlockSessionStop() {
        val executor = CapturingExecutor()
        val session = createSessionUsing(executor)
        val listenerStarted = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        session.onStateChanged {
            listenerStarted.countDown()
            releaseListener.await()
        }

        session.start()
        val notificationThread = executor.runNextInThread()
        assertTrue(listenerStarted.await(2, TimeUnit.SECONDS))

        val stopFinished = CountDownLatch(1)
        val stopResult = AtomicReference<StopResult?>()
        val stopThread = thread(start = true) {
            stopResult.set(session.stop())
            stopFinished.countDown()
        }
        val stopReturnedWhileListenerWasBlocked = stopFinished.await(2, TimeUnit.SECONDS)
        releaseListener.countDown()

        assertTrue(stopFinished.await(2, TimeUnit.SECONDS))
        stopThread.join(2_000)
        notificationThread.join(2_000)
        assertTrue(stopReturnedWhileListenerWasBlocked)
        assertEquals(StopResult.Stopped, stopResult.get())
        assertEquals(SessionState.STOPPED, session.snapshot().state)
    }

    @Test
    fun snapshotsNeverExposeAnInvalidStateFieldCombinationDuringRaces() {
        val fixture = SessionFixture.create()
        val invalid = ConcurrentLinkedQueue<SessionSnapshot>()
        val startGate = CountDownLatch(1)
        val workers = List(8) { index ->
            thread(start = true) {
                startGate.await()
                repeat(100) {
                    if ((index + it) % 2 == 0) fixture.session.start() else fixture.session.stop()
                    val snapshot = fixture.session.snapshot()
                    if (!snapshot.isValidCombination()) invalid += snapshot
                }
            }
        }

        startGate.countDown()
        workers.forEach { it.join(5_000) }

        assertTrue(workers.none { it.isAlive })
        assertTrue(invalid.isEmpty(), "invalid snapshots: $invalid")
        assertNotNull(fixture.session.snapshot())
    }

    @Test
    fun unregisterPreventsDeliveryOfAlreadyQueuedEvents() {
        val fixture = SessionFixture.create()
        val events = mutableListOf<SessionStateEvent>()
        val registration = fixture.session.onStateChanged { events += it }

        fixture.session.start()
        registration.unregister()
        fixture.notifier.drain()

        assertEquals(emptyList(), events)
    }

    @Test
    fun unregisterWaitsForInFlightListenerBeforeReturning() {
        val executor = CapturingExecutor()
        val session = createSessionUsing(executor)
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        val registration = session.onStateChanged {
            callbackCount.incrementAndGet()
            callbackStarted.countDown()
            releaseCallback.await()
        }

        session.start()
        val notificationThread = executor.runNextInThread()
        assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))

        val firstUnregisterFinished = CountDownLatch(1)
        val allUnregistersFinished = CountDownLatch(2)
        val unregisterThreads = List(2) {
            thread(start = true) {
                registration.unregister()
                firstUnregisterFinished.countDown()
                allUnregistersFinished.countDown()
            }
        }

        val returnedBeforeCallbackFinished = firstUnregisterFinished.await(100, TimeUnit.MILLISECONDS)
        releaseCallback.countDown()

        assertTrue(allUnregistersFinished.await(2, TimeUnit.SECONDS))
        unregisterThreads.forEach { thread -> thread.join(2_000) }
        notificationThread.join(2_000)
        assertFalse(notificationThread.isAlive)

        session.stop()
        executor.runNextInThread().join(2_000)

        assertFalse(returnedBeforeCallbackFinished)
        assertEquals(1, callbackCount.get())
    }

    @Test
    fun listenerCanUnregisterItselfWithoutDeadlock() {
        val fixture = SessionFixture.create()
        val callbackCount = AtomicInteger()
        lateinit var registration: Registration
        registration = fixture.session.onStateChanged {
            callbackCount.incrementAndGet()
            registration.unregister()
        }

        fixture.session.start()
        val notificationThread = thread(start = true) { fixture.notifier.drain() }
        notificationThread.join(2_000)

        assertFalse(notificationThread.isAlive, "self-unregister deadlocked")
        fixture.session.stop()
        fixture.notifier.drain()
        assertEquals(1, callbackCount.get())
    }

    @Test
    fun transportFailureAndHandshakeTimeoutRaceCleansGenerationOnce() {
        repeat(25) {
            val fixture = SessionFixture.create()
            fixture.session.start()
            val connection = fixture.connector.current
            connection.open()
            val timeout = fixture.scheduler.tasks.single()
            val startGate = CountDownLatch(1)
            val completed = CountDownLatch(2)
            val workers = listOf(
                thread(start = true) {
                    startGate.await()
                    try {
                        connection.fail()
                    } finally {
                        completed.countDown()
                    }
                },
                thread(start = true) {
                    startGate.await()
                    try {
                        fixture.scheduler.fire(timeout)
                    } finally {
                        completed.countDown()
                    }
                },
            )

            startGate.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            workers.forEach { worker -> worker.join(2_000) }

            assertEquals(SessionState.RECONNECT_WAIT, fixture.session.snapshot().state)
            assertEquals(1, connection.closeCount)
            assertEquals(1, fixture.commandCleanup.calls.size)
            assertEquals(1, fixture.missionCleanup.calls.size)
            assertEquals(1, fixture.outbound.discarded.size)
            assertEquals(1, fixture.scheduler.tasks.count { !it.cancelled && !it.fired })
        }
    }

    private fun createSessionUsing(executor: Executor): ConnectionSession {
        val diagnostics = RecordingDiagnosticSink()
        val order = mutableListOf<String>()
        val result = ConnectionSession.create(
            SessionConfig("ws://desktop:8765/relay", "device"),
            SessionDependencies(
                connector = RecordingConnector(order),
                outbound = RecordingOutbound(order),
                activeFrameConsumer = RecordingFrameConsumer(),
                commandCleanup = RecordingCommandCleanup(order),
                missionCleanup = RecordingMissionCleanup(order),
                scheduler = ManualScheduler(),
                stateNotifier = ExecutorOrderedStateNotifier(executor, diagnostics),
                diagnosticSink = diagnostics,
            ),
        )
        return (result as SessionCreated).session
    }

    private fun SessionSnapshot.isValidCombination(): Boolean = when (state) {
        SessionState.STOPPED,
        SessionState.RECONNECT_WAIT,
        -> generation == null && sessionId == null

        SessionState.CONNECTING,
        SessionState.AWAITING_PAIRING,
        -> generation != null && sessionId == null

        SessionState.ACTIVE -> generation != null && sessionId != null
    }

    private fun <T> runConcurrently(count: Int, action: () -> T): List<T> {
        val startGate = CountDownLatch(1)
        val completed = CountDownLatch(count)
        val results = ConcurrentLinkedQueue<T>()
        repeat(count) {
            thread(start = true) {
                startGate.await()
                try {
                    results += action()
                } finally {
                    completed.countDown()
                }
            }
        }
        startGate.countDown()
        assertTrue(completed.await(5, TimeUnit.SECONDS), "concurrent operations did not finish")
        return results.toList()
    }

    private class CapturingExecutor : Executor {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runNextInThread(): Thread {
            val task = try {
                tasks.poll(2, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("Interrupted while waiting for notification", interrupted)
            } ?: error("No notification task was submitted")
            return thread(start = true) {
                task.run()
            }
        }
    }
}
