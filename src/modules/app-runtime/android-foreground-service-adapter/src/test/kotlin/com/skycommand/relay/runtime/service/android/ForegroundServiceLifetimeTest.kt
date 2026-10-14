package com.skycommand.relay.runtime.service.android

import com.skycommand.relay.runtime.service.ForegroundServiceCallback
import org.junit.Test
import kotlin.test.*

class ForegroundServiceLifetimeTest {
    @Test fun unexpectedDestroyReportsActiveStartFailureOnce() {
        val lifetime = ForegroundServiceLifetime<String>()
        lifetime.starting("start")
        assertEquals(ForegroundServiceLifetime.Terminal("start", ForegroundServiceLifetime.End.FAILED), lifetime.destroyed())
        assertNull(lifetime.activeStart)
        assertNull(lifetime.destroyed())
    }
    @Test fun explicitStopReportsStopOperationWithoutUnexpectedFailure() {
        val lifetime = ForegroundServiceLifetime<String>()
        lifetime.starting("start")
        lifetime.stopping("stop")
        assertNull(lifetime.activeStart)
        assertEquals(ForegroundServiceLifetime.Terminal("stop", ForegroundServiceLifetime.End.STOPPED), lifetime.destroyed())
        assertNull(lifetime.destroyed())
    }
    @Test fun startupFailureDoesNotRepeatWhenServiceIsDestroyed() {
        val lifetime = ForegroundServiceLifetime<String>()
        lifetime.starting("start")
        assertEquals(ForegroundServiceLifetime.Terminal("start", ForegroundServiceLifetime.End.FAILED), lifetime.startupFailed())
        assertNull(lifetime.startupFailed())
        assertNull(lifetime.destroyed())
    }
    @Test fun emptyRestartHasNoActiveOperation() {
        val lifetime = ForegroundServiceLifetime<String>()
        assertNull(lifetime.activeStart)
        assertNull(lifetime.destroyed())
    }
    @Test fun newStartDoesNotKeepPendingStopFromPriorOperation() {
        val lifetime = ForegroundServiceLifetime<String>()
        lifetime.stopping("old-stop")
        lifetime.starting("new-start")
        assertEquals(ForegroundServiceLifetime.Terminal("new-start", ForegroundServiceLifetime.End.FAILED), lifetime.destroyed())
    }
    @Test fun realPortReceivesUnexpectedFailureFromServiceLifetime() {
        val lifetime = ForegroundServiceLifetime<String>()
        lateinit var emit: (ForegroundServicePlatformEvent) -> Unit
        val platform = object : ForegroundServicePlatform {
            override fun start(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit) {
                lifetime.starting(operationId)
                emit = callback
                callback(ForegroundServicePlatformEvent.Started(operationId))
            }
            override fun stop(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit) { error("unexpected stop") }
        }
        val port = AndroidForegroundServicePort(platform)
        var started = 0
        var failures = 0
        port.onUnexpectedFailure { failures++ }
        port.start(object : ForegroundServiceCallback {
            override fun started() { started++ }
            override fun stopped() { fail("unexpected stop") }
            override fun failed() { fail("startup already succeeded") }
        })
        val terminal = checkNotNull(lifetime.destroyed())
        emit(ForegroundServicePlatformEvent.Failed(terminal.payload))
        emit(ForegroundServicePlatformEvent.Failed(terminal.payload))
        assertEquals(1, started)
        assertEquals(1, failures)
        port.close()
    }
}
