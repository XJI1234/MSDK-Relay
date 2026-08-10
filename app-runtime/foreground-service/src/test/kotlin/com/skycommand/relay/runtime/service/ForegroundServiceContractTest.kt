package com.skycommand.relay.runtime.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ForegroundServiceContractTest {
    @Test fun startsAndStopsOnlyAfterTerminalCallbacks() {
        val port = FakePort()
        val controller = ForegroundServiceController.create(port)

        assertIs<ForegroundRequestResult.Accepted>(controller.start())
        assertEquals(ForegroundServiceState.STARTING, controller.snapshot())
        port.started()
        assertEquals(ForegroundServiceState.RUNNING, controller.snapshot())
        assertIs<ForegroundRequestResult.Accepted>(controller.stop())
        assertEquals(ForegroundServiceState.STOPPING, controller.snapshot())
        port.stopped()
        assertEquals(ForegroundServiceState.STOPPED, controller.snapshot())
    }

    @Test fun rejectsDuplicatesAndIgnoresLateOrDuplicateCallbacks() {
        val port = FakePort()
        val controller = ForegroundServiceController.create(port)

        controller.start()
        assertEquals(ForegroundRejection.TRANSITION_IN_PROGRESS, assertIs<ForegroundRequestResult.Rejected>(controller.start()).reason)
        port.started()
        controller.stop()
        port.stopped()
        port.started()
        assertEquals(ForegroundServiceState.STOPPED, controller.snapshot())
        assertEquals(ForegroundRejection.ALREADY_STOPPED, assertIs<ForegroundRequestResult.Rejected>(controller.stop()).reason)
    }

    @Test fun mapsPortFailureAndAllowsRetry() {
        val port = FakePort(throwOnStart = true)
        val controller = ForegroundServiceController.create(port)

        assertEquals(ForegroundRejection.PORT_FAILURE, assertIs<ForegroundRequestResult.Rejected>(controller.start()).reason)
        assertEquals(ForegroundServiceState.FAILED, controller.snapshot())
        port.throwOnStart = false
        assertIs<ForegroundRequestResult.Accepted>(controller.start())
    }

    private class FakePort(
        private val callbacks: MutableList<ForegroundServiceCallback> = mutableListOf(),
        var throwOnStart: Boolean = false,
    ) : ForegroundServicePort {
        override fun start(callback: ForegroundServiceCallback) {
            if (throwOnStart) error("start failed")
            callbacks += callback
        }

        override fun stop(callback: ForegroundServiceCallback) {
            callbacks += callback
        }

        fun started() { callbacks.last().started() }
        fun stopped() { callbacks.last().stopped() }
    }
}
