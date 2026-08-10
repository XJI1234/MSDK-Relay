package com.skycommand.relay.device.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    }

    private enum class PortCallback { READY, FAILURE }
}
