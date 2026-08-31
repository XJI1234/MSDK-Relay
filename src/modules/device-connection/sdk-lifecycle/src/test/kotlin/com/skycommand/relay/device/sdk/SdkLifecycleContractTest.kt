package com.skycommand.relay.device.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SdkLifecycleContractTest {

    @Test
    fun startsOnlyOnceAndBecomesReadyOnlyAfterThePortCallback() {
        val port = RecordingPort()
        val lifecycle = SdkLifecycle.create(port)
        val states = mutableListOf<SdkAvailability>()
        lifecycle.onChanged { states += it }

        assertIs<StartResult.StartAccepted>(lifecycle.start())
        assertEquals(SdkAvailability.STARTING, lifecycle.state())
        assertIs<StartResult.AlreadyRunning>(lifecycle.start())
        port.ready()

        assertEquals(SdkAvailability.READY, lifecycle.state())
        assertEquals(listOf(SdkAvailability.STARTING, SdkAvailability.READY), states)
        assertEquals(1, port.initializeCalls)
    }

    @Test
    fun handlesSynchronousReadyAndRejectsASecondStartAfterFailureUntilStopped() {
        val port = RecordingPort().apply { callbackDuringInitialize = PortCallback.READY }
        val lifecycle = SdkLifecycle.create(port)

        assertIs<StartResult.StartAccepted>(lifecycle.start())
        assertEquals(SdkAvailability.READY, lifecycle.state())

        val failedPort = RecordingPort().apply { rejectInitialize = true }
        val failed = SdkLifecycle.create(failedPort)
        assertIs<StartResult.StartRejected>(failed.start())
        assertEquals(SdkAvailability.FAILED, failed.state())
        assertIs<StartResult.AlreadyRunning>(failed.start())
        assertIs<StopResult.Stopped>(failed.stop())
        failedPort.rejectInitialize = false
        assertIs<StartResult.StartAccepted>(failed.start())
    }

    @Test
    fun stopInvalidatesOldCallbacksAndContainsPortCloseFailure() {
        val port = RecordingPort().apply { throwOnClose = true }
        val lifecycle = SdkLifecycle.create(port)
        lifecycle.start()

        assertIs<StopResult.Stopped>(lifecycle.stop())
        assertEquals(SdkAvailability.STOPPED, lifecycle.state())
        assertEquals(1, port.closeCalls)
        port.ready()
        assertEquals(SdkAvailability.STOPPED, lifecycle.state())
    }

    @Test
    fun failsWithAStableDiagnosticWhenAcceptedStartupNeverCallsBack() {
        val port = RecordingPort()
        val timeouts = RecordingStartupTimeoutScheduler()
        val diagnostics = mutableListOf<SdkLifecycleDiagnosticKind>()
        val lifecycle = SdkLifecycle.create(
            port,
            SdkLifecycleDiagnosticSink { diagnostics += it.kind },
            timeouts,
        )

        assertIs<StartResult.StartAccepted>(lifecycle.start())
        timeouts.latest().fire()

        assertEquals(SdkAvailability.FAILED, lifecycle.state())
        assertEquals(listOf(SdkLifecycleDiagnosticKind.START_TIMEOUT), diagnostics)
    }

    @Test
    fun readyCancelsTheStartupTimeout() {
        val port = RecordingPort()
        val timeouts = RecordingStartupTimeoutScheduler()
        val lifecycle = SdkLifecycle.create(port, startupTimeoutScheduler = timeouts)

        lifecycle.start()
        val timeout = timeouts.latest()
        port.ready()

        assertEquals(SdkAvailability.READY, lifecycle.state())
        assertTrue(timeout.cancelled)
    }

    @Test
    fun failureCancelsTheStartupTimeout() {
        val port = RecordingPort()
        val timeouts = RecordingStartupTimeoutScheduler()
        val lifecycle = SdkLifecycle.create(port, startupTimeoutScheduler = timeouts)

        lifecycle.start()
        val timeout = timeouts.latest()
        port.failure()

        assertEquals(SdkAvailability.FAILED, lifecycle.state())
        assertTrue(timeout.cancelled)
    }

    @Test
    fun aCancelledTimeoutFromAnOldRunCannotFailTheNextRun() {
        val port = RecordingPort()
        val timeouts = RecordingStartupTimeoutScheduler()
        val lifecycle = SdkLifecycle.create(port, startupTimeoutScheduler = timeouts)

        lifecycle.start()
        val oldTimeout = timeouts.latest()
        lifecycle.stop()
        lifecycle.start()
        oldTimeout.fire()

        assertTrue(oldTimeout.cancelled)
        assertEquals(SdkAvailability.STARTING, lifecycle.state())
    }

    @Test
    fun reportsFailedWhenTheStartupTimeoutCannotBeScheduled() {
        val port = RecordingPort()
        val states = mutableListOf<SdkAvailability>()
        val lifecycle = SdkLifecycle.create(
            port,
            startupTimeoutScheduler = SdkStartupTimeoutScheduler { _, _ ->
                error("timeout scheduler unavailable")
            },
        )
        lifecycle.onChanged { states += it }

        assertIs<StartResult.StartRejected>(lifecycle.start())

        assertEquals(0, port.initializeCalls)
        assertEquals(SdkAvailability.FAILED, lifecycle.state())
        assertEquals(listOf(SdkAvailability.STARTING, SdkAvailability.FAILED), states)
    }

    private class RecordingPort : DjiSdkPort {
        var initializeCalls = 0
        var closeCalls = 0
        var rejectInitialize = false
        var throwOnClose = false
        var callbackDuringInitialize: PortCallback? = null
        private var callbacks: DjiSdkCallbacks? = null

        override fun initialize(callbacks: DjiSdkCallbacks): PortStartResult {
            initializeCalls += 1
            this.callbacks = callbacks
            if (rejectInitialize) return PortStartResult.Rejected("SDK unavailable")
            when (callbackDuringInitialize) {
                PortCallback.READY -> callbacks.onReady()
                PortCallback.FAILURE -> callbacks.onFailure()
                null -> Unit
            }
            return PortStartResult.Accepted
        }

        override fun close() {
            closeCalls += 1
            if (throwOnClose) error("port close failed")
        }

        fun ready() = checkNotNull(callbacks).onReady()

        fun failure() = checkNotNull(callbacks).onFailure()
    }

    private class RecordingStartupTimeoutScheduler : SdkStartupTimeoutScheduler {
        private val scheduled = mutableListOf<RecordingStartupTimeout>()

        override fun schedule(delayMillis: Long, callback: () -> Unit): SdkStartupTimeout =
            RecordingStartupTimeout(callback).also { scheduled += it }

        fun latest(): RecordingStartupTimeout = checkNotNull(scheduled.lastOrNull())
    }

    private class RecordingStartupTimeout(
        private val callback: () -> Unit,
    ) : SdkStartupTimeout {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }

        fun fire() {
            callback()
        }
    }

    private enum class PortCallback { READY, FAILURE }
}
