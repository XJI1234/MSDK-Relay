package com.skycommand.relay.stream.state

import com.skycommand.relay.stream.config.StreamConfigValidator
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamStateStoreContractTest {
    @Test
    fun startsAndStopsOnlyAfterMatchingTerminalCallbacks() {
        val store = StreamStateStore.create()
        val start = assertIs<StreamStartResult.Accepted>(store.requestStart(config())).operationId
        assertEquals(StreamLifecycleState.STARTING, store.snapshot().state)
        assertIs<StreamUpdateResult.Applied>(store.markStarted(start))
        assertEquals(StreamLifecycleState.STREAMING, store.snapshot().state)

        val stop = assertIs<StreamStopResult.Accepted>(store.requestStop()).operationId
        assertEquals(StreamLifecycleState.STOPPING, store.snapshot().state)
        assertIs<StreamUpdateResult.Applied>(store.markStopped(stop, "Stopped"))
        assertEquals(StreamLifecycleState.STOPPED, store.snapshot().state)
        assertEquals(false, store.snapshot().targetConfigured)
        assertEquals(null, store.snapshot().djiStreaming)
    }

    @Test
    fun keepsDjiStreamingFactSeparateFromStartLifecycle() {
        val store = StreamStateStore.create()
        val operation = assertIs<StreamStartResult.Accepted>(store.requestStart(config())).operationId

        assertIs<StreamUpdateResult.Applied>(store.markStarted(operation))
        assertEquals(StreamLifecycleState.STREAMING, store.snapshot().state)
        assertEquals(null, store.snapshot().djiStreaming)

        assertIs<StreamUpdateResult.Applied>(store.reportDjiStreaming(operation, StreamMetrics("1080p", 30.0, 2_000.0, 40)))
        assertEquals(true, store.snapshot().djiStreaming)
        assertEquals(StreamMetrics("1080p", 30.0, 2_000.0, 40), store.snapshot().metrics)

        assertIs<StreamUpdateResult.Applied>(store.reportDjiStopped(operation))
        assertEquals(false, store.snapshot().djiStreaming)
        assertEquals(StreamLifecycleState.FAILED, store.snapshot().state)
        assertEquals(null, store.snapshot().metrics)
    }

    @Test
    fun rejectsInvalidRequestsAndDoesNotChangeState() {
        val store = StreamStateStore.create()
        assertEquals(StreamStopRejection.NO_ACTIVE_STREAM, assertIs<StreamStopResult.Rejected>(store.requestStop()).reason)
        val start = assertIs<StreamStartResult.Accepted>(store.requestStart(config())).operationId
        assertEquals(StreamStartRejection.ALREADY_ACTIVE, assertIs<StreamStartResult.Rejected>(store.requestStart(config())).reason)
        val stop = assertIs<StreamStopResult.Accepted>(store.requestStop()).operationId
        assertEquals(StreamStopRejection.ALREADY_STOPPING, assertIs<StreamStopResult.Rejected>(store.requestStop()).reason)
        store.markFailed(stop, "failed")
        assertEquals(StreamLifecycleState.FAILED, store.snapshot().state)
        assertEquals(StreamStopRejection.NO_ACTIVE_STREAM, assertIs<StreamStopResult.Rejected>(store.requestStop()).reason)
    }

    @Test
    fun ignoresLateDuplicateCallbacksAndCallbacksFromAnOldStream() {
        val store = StreamStateStore.create()
        val old = assertIs<StreamStartResult.Accepted>(store.requestStart(config())).operationId
        store.markFailed(old, "old failed")
        val current = assertIs<StreamStartResult.Accepted>(store.requestStart(config("new"))).operationId
        val before = store.snapshot()

        assertIs<StreamUpdateResult.IgnoredStale>(store.markStarted(old))
        assertEquals(before, store.snapshot())
        store.markStarted(current)
        assertIs<StreamUpdateResult.IgnoredStale>(store.markStarted(current))
        assertIs<StreamUpdateResult.IgnoredStale>(store.markStopped(old, "late"))
        assertEquals(StreamLifecycleState.STREAMING, store.snapshot().state)
    }

    @Test
    fun validatesMetricBoundariesAndRejectsInvalidValues() {
        val store = StreamStateStore.create()
        val operation = assertIs<StreamStartResult.Accepted>(store.requestStart(config())).operationId
        assertIs<StreamUpdateResult.Applied>(store.markStarted(operation))
        assertIs<StreamUpdateResult.Applied>(store.reportDjiStreaming(operation, StreamMetrics("x".repeat(64), 240.0, 1_000_000.0, 60_000)))
        assertEquals(240.0, store.snapshot().metrics?.fps)
        listOf(
            StreamMetrics("x".repeat(65), 1.0, 1.0, 1),
            StreamMetrics(null, -0.1, 1.0, 1),
            StreamMetrics(null, 1.0, -0.1, 1),
            StreamMetrics(null, 1.0, 1.0, -1),
        ).forEach { metrics ->
            kotlin.test.assertFailsWith<IllegalArgumentException> { store.reportDjiStreaming(operation, metrics) }
        }
    }

    @Test
    fun listenerFailureDoesNotBlockOtherListenersAndUnregisterIsEffective() {
        val diagnostics = ConcurrentLinkedQueue<StreamStateDiagnosticKind>()
        val store = StreamStateStore.create(StreamStateDiagnosticSink { diagnostics += it })
        var firstCalls = 0
        var secondCalls = 0
        val first = store.onChanged { firstCalls += 1; error("listener") }
        val second = store.onChanged { secondCalls += 1 }
        store.requestStart(config())
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
        assertEquals(listOf(StreamStateDiagnosticKind.LISTENER_FAILURE), diagnostics.toList())
        first.unregister()
        second.unregister()
        store.markDeviceUnavailable("offline")
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun concurrentStartRequestsAcceptOnlyOne() {
        val store = StreamStateStore.create()
        val gate = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<StreamStartResult>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(4) { pool.submit { gate.await(); results += store.requestStart(config()) } }
            gate.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, results.count { it is StreamStartResult.Accepted })
        assertEquals(3, results.count { it is StreamStartResult.Rejected })
    }

    @Test
    fun listenerCanUnregisterReentrantlyWithoutDeadlockingOrReceivingLaterEvents() {
        val store = StreamStateStore.create()
        var calls = 0
        lateinit var registration: Registration
        registration = store.onChanged {
            calls += 1
            registration.unregister()
            assertEquals(StreamLifecycleState.STARTING, store.snapshot().state)
        }

        store.requestStart(config())
        store.markDeviceUnavailable("offline")

        assertEquals(1, calls)
    }

    private fun config(suffix: String = "device") = ValidatedStreamConfig("rtmp://computer/live/$suffix")
}
