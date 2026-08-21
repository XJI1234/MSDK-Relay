package com.skycommand.relay.stream.whip.state

import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WhipStreamStateStoreContractTest {
    @Test
    fun exposesIdleSnapshotsAndSortsIndependentDevices() {
        val store = WhipStreamStateStore.create()

        assertEquals(
            WhipDeviceSnapshot("drone-b", 0, WhipStreamLifecycle.IDLE, false, WhipStreamNotice.NONE, null, null),
            store.snapshot("drone-b"),
        )
        val first = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-b", config("b")))
        val second = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-a", config("a")))

        assertTrue(first.operationId != second.operationId)
        assertEquals(listOf("drone-a", "drone-b"), store.snapshots().map { it.deviceId })
    }

    @Test
    fun appliesStartPublishMetricsAndStopUsingAReplacementOperation() {
        val store = WhipStreamStateStore.create()
        val start = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-a", config("a")))
        assertEquals(WhipStreamLifecycle.CONNECTING, store.snapshot("drone-a").state)

        val initialMetrics = WhipStreamMetrics(
            resolution = "1920x1080",
            fps = 30.0,
            bitrateKbps = 4_000.0,
            rttMillis = 18,
        )
        assertIs<WhipUpdateResult.Applied>(store.markPublishing("drone-a", start.operationId, initialMetrics))
        assertEquals(WhipStreamLifecycle.PUBLISHING, store.snapshot("drone-a").state)
        store.updateMetrics("drone-a", start.operationId, initialMetrics.copy(rttMillis = 12))
        assertEquals(12, store.snapshot("drone-a").metrics?.rttMillis)

        val stop = assertIs<WhipStopResult.Accepted>(store.requestStop("drone-a"))
        assertTrue(stop.operationId != start.operationId)
        assertEquals(WhipStreamLifecycle.STOPPING, store.snapshot("drone-a").state)
        assertIs<WhipUpdateResult.IgnoredStale>(store.markPublishing("drone-a", start.operationId))

        assertIs<WhipUpdateResult.Applied>(store.markStopped("drone-a", stop.operationId))
        assertEquals(WhipStreamLifecycle.IDLE, store.snapshot("drone-a").state)
        assertEquals(false, store.snapshot("drone-a").targetConfigured)
        assertEquals(null, store.snapshot("drone-a").metrics)
    }

    @Test
    fun rejectsInvalidTransitionsAndInvalidDeviceIds() {
        val store = WhipStreamStateStore.create()

        assertEquals(
            WhipStartRejection.INVALID_DEVICE_ID,
            assertIs<WhipStartResult.Rejected>(store.requestStart("", config("empty"))).reason,
        )
        assertEquals(
            WhipStartRejection.INVALID_DEVICE_ID,
            assertIs<WhipStartResult.Rejected>(store.requestStart("bad\nname", config("bad"))).reason,
        )
        assertEquals(
            WhipStopRejection.NO_ACTIVE_STREAM,
            assertIs<WhipStopResult.Rejected>(store.requestStop("unknown")).reason,
        )

        val start = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-a", config("a")))
        assertEquals(
            WhipStartRejection.ALREADY_ACTIVE,
            assertIs<WhipStartResult.Rejected>(store.requestStart("drone-a", config("again"))).reason,
        )
        assertIs<WhipStopResult.Accepted>(store.requestStop("drone-a"))
        assertEquals(
            WhipStopRejection.ALREADY_STOPPING,
            assertIs<WhipStopResult.Rejected>(store.requestStop("drone-a")).reason,
        )
    }

    @Test
    fun ignoresOldGenerationFailureMetricsAndCompletionAfterReplacementOrDisconnect() {
        val store = WhipStreamStateStore.create()
        val first = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-a", config("first")))
        store.markFailed("drone-a", first.operationId, WhipStreamFailure.TIMEOUT)
        val second = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-a", config("second")))
        assertEquals(WhipStreamLifecycle.CONNECTING, store.snapshot("drone-a").state)

        assertIs<WhipUpdateResult.IgnoredStale>(store.markPublishing("drone-a", first.operationId))
        assertIs<WhipUpdateResult.IgnoredStale>(store.markFailed("drone-a", first.operationId, WhipStreamFailure.INTERNAL))
        assertIs<WhipUpdateResult.Applied>(store.markPublishing("drone-a", second.operationId))
        assertIs<WhipUpdateResult.Applied>(store.markDisconnected("drone-a", second.operationId))
        assertEquals(WhipStreamLifecycle.DISCONNECTED, store.snapshot("drone-a").state)
        assertIs<WhipUpdateResult.IgnoredStale>(store.markStopped("drone-a", second.operationId))
        assertEquals(
            WhipStopRejection.NO_ACTIVE_STREAM,
            assertIs<WhipStopResult.Rejected>(store.requestStop("drone-a")).reason,
        )
    }

    @Test
    fun deviceUnavailableInvalidatesTheActiveOperationAndKeepsIdleDevicesIdle() {
        val store = WhipStreamStateStore.create()
        val start = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-a", config("a")))

        val unavailable = store.markDeviceUnavailable("drone-a")
        assertEquals(WhipStreamLifecycle.DISCONNECTED, unavailable.state)
        assertEquals(false, unavailable.targetConfigured)
        assertEquals(null, unavailable.metrics)
        assertIs<WhipUpdateResult.IgnoredStale>(store.markPublishing("drone-a", start.operationId))

        assertEquals(WhipStreamLifecycle.IDLE, store.markDeviceUnavailable("drone-b").state)
    }

    @Test
    fun validatesMetricsBeforeChangingState() {
        val store = WhipStreamStateStore.create()
        val start = assertIs<WhipStartResult.Accepted>(store.requestStart("drone-a", config("a")))
        val before = store.snapshot("drone-a")

        assertFailsWith<IllegalArgumentException> {
            store.markPublishing("drone-a", start.operationId, WhipStreamMetrics(fps = 241.0))
        }
        assertFailsWith<IllegalArgumentException> {
            store.markPublishing("drone-a", start.operationId, WhipStreamMetrics(resolution = "bad\nresolution"))
        }
        assertFailsWith<IllegalArgumentException> {
            store.markPublishing("drone-a", start.operationId, WhipStreamMetrics(bitrateKbps = Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            store.markPublishing("drone-a", start.operationId, WhipStreamMetrics(rttMillis = 60_001))
        }
        assertEquals(before, store.snapshot("drone-a"))
    }

    @Test
    fun isolatesListenerFailuresSupportsUnregisterAndProtectsSnapshots() {
        val diagnostics = mutableListOf<WhipStreamStateDiagnosticKind>()
        val store = WhipStreamStateStore.create(WhipStreamStateDiagnosticSink { diagnostics += it })
        val throwing = store.onChanged { error("listener failure") }
        val received = mutableListOf<WhipStreamStateEvent>()
        store.onChanged { received += it }

        store.requestStart("drone-a", config("a"))
        assertEquals(1, received.size)
        assertEquals(listOf(WhipStreamStateDiagnosticKind.LISTENER_FAILURE), diagnostics)
        throwing.unregister()
        store.requestStart("drone-b", config("b"))
        assertEquals(2, received.size)

        val snapshots = store.snapshots()
        assertFailsWith<UnsupportedOperationException> { (snapshots as MutableList<WhipDeviceSnapshot>).clear() }
    }

    @Test
    fun unregisterInsideCallbackDoesNotDeadlockOrReceiveQueuedEvents() {
        val store = WhipStreamStateStore.create()
        var registration: WhipStreamStateRegistration? = null
        var calls = 0
        registration = store.onChanged {
            calls += 1
            registration?.unregister()
        }

        store.requestStart("drone-a", config("a"))
        store.requestStart("drone-b", config("b"))

        assertEquals(1, calls)
    }

    @Test
    fun serializesConcurrentStartRequestsPerDevice() {
        val store = WhipStreamStateStore.create()
        val results = ConcurrentLinkedQueue<WhipStartResult>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(20) { index ->
                pool.submit { results += store.requestStart("drone-a", config("$index")) }
            }
            pool.shutdown()
            check(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, results.count { it is WhipStartResult.Accepted })
        assertEquals(19, results.count { it is WhipStartResult.Rejected })
    }

    private fun config(device: String) = ValidatedWhipStreamConfig("http://computer/live/$device/whip")
}
