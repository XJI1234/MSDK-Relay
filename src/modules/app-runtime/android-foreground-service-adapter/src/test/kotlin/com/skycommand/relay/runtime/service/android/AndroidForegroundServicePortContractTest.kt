package com.skycommand.relay.runtime.service.android

import com.skycommand.relay.runtime.service.ForegroundServiceCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidForegroundServicePortContractTest {
    @Test
    fun reportsStartedOnlyForTheCurrentStartOperation() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)
        var started = 0
        port.start(callback(onStarted = { started += 1 }))

        platform.emitStarted("old-operation")
        assertEquals(0, started)
        platform.emitStarted(platform.currentOperationId!!)

        assertEquals(1, started)
    }

    @Test
    fun ignoresAnUnexpectedTerminalEventForTheCurrentOperation() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)
        var started = 0
        port.start(callback(onStarted = { started += 1 }))
        val operationId = platform.currentOperationId!!

        platform.emitStopped(operationId)
        platform.emitStarted(operationId)

        assertEquals(1, started)
    }

    @Test
    fun closeSuppressesLateServiceCallbacksAndFutureRequests() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)
        port.start(callback(onStarted = { error("late callback") }))
        val operationId = platform.currentOperationId!!

        port.close()
        platform.emitStarted(operationId)

        assertFailsWith<IllegalStateException> { port.start(callback()) }
    }

    @Test
    fun rejectsASecondDirectOperationUntilTheFirstOneCompletes() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)

        port.start(callback())

        assertFailsWith<IllegalStateException> { port.stop(callback()) }
    }

    @Test
    fun callbackFailureDoesNotKeepThePortBusy() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)

        port.start(callback(onStarted = { error("consumer callback failed") }))
        platform.emitStarted(platform.currentOperationId!!)

        port.start(callback())
    }

    @Test
    fun closeReleasesThePlatformResources() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)

        port.close()

        assertTrue(platform.closed)
    }

    @Test
    fun reportsFailureAndAllowsASeparateOperationAfterwards() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)
        var failures = 0
        var starts = 0

        port.start(callback(onStarted = { starts += 1 }, onFailed = { failures += 1 }))
        val firstOperation = platform.currentOperationId!!
        platform.emitFailed(firstOperation)

        port.start(callback(onStarted = { starts += 1 }))
        platform.emitStarted(platform.currentOperationId!!)

        assertEquals(1, failures)
        assertEquals(1, starts)
    }

    @Test
    fun acceptsOnlyStoppedForAStopOperation() {
        val platform = FakePlatform()
        val port = AndroidForegroundServicePort(platform)
        var stopped = 0

        port.stop(callback(onStopped = { stopped += 1 }))
        val operationId = platform.currentOperationId!!
        platform.emitStarted(operationId)
        assertEquals(0, stopped)

        platform.emitStopped(operationId)
        platform.emitStopped(operationId)
        assertEquals(1, stopped)
    }

    @Test
    fun convertsPlatformThrowIntoFailureBeforeRethrowing() {
        val platform = FakePlatform(throwOnStart = true)
        val port = AndroidForegroundServicePort(platform)
        var failures = 0

        assertFailsWith<IllegalStateException> {
            port.start(callback(onFailed = { failures += 1 }))
        }

        assertEquals(1, failures)
    }

    private fun callback(
        onStarted: () -> Unit = {},
        onStopped: () -> Unit = {},
        onFailed: () -> Unit = {},
    ): ForegroundServiceCallback =
        object : ForegroundServiceCallback {
            override fun started() = onStarted()
            override fun stopped() = onStopped()
            override fun failed() = onFailed()
        }

    private class FakePlatform(
        private val throwOnStart: Boolean = false,
    ) : ForegroundServicePlatform {
        private var callback: ((ForegroundServicePlatformEvent) -> Unit)? = null
        var currentOperationId: String? = null
        var closed = false

        override fun close() {
            closed = true
            callback = null
        }

        override fun start(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit) {
            if (throwOnStart) error("start failed")
            currentOperationId = operationId
            this.callback = callback
        }

        override fun stop(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit) {
            currentOperationId = operationId
            this.callback = callback
        }

        fun emitStarted(operationId: String) {
            callback?.invoke(ForegroundServicePlatformEvent.Started(operationId))
        }

        fun emitStopped(operationId: String) {
            callback?.invoke(ForegroundServicePlatformEvent.Stopped(operationId))
        }

        fun emitFailed(operationId: String) {
            callback?.invoke(ForegroundServicePlatformEvent.Failed(operationId))
        }
    }
}

class ForegroundNotificationSpecContractTest {
    @Test
    fun rejectsBlankChannelAndNonPositiveResourceIdentifiers() {
        assertFailsWith<IllegalArgumentException> {
            ForegroundNotificationSpec(" ", 1, 2, 3, 4)
        }
        assertFailsWith<IllegalArgumentException> {
            ForegroundNotificationSpec("relay", 0, 2, 3, 4)
        }
        assertFailsWith<IllegalArgumentException> {
            ForegroundNotificationSpec("relay", 1, -2, 3, 4)
        }
        assertFailsWith<IllegalArgumentException> {
            ForegroundNotificationSpec("relay", 1, 2, 0, 4)
        }
        assertFailsWith<IllegalArgumentException> {
            ForegroundNotificationSpec("relay", 1, 2, 3, -4)
        }
    }

    @Test
    fun acceptsACompleteNotificationSpecification() {
        ForegroundNotificationSpec("relay", 1, 2, 3, 4)
    }
}
