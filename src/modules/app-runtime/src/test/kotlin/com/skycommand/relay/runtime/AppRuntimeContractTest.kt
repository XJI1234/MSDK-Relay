package com.skycommand.relay.runtime

import com.skycommand.relay.runtime.bootstrap.AppBootstrap
import com.skycommand.relay.runtime.bootstrap.BootstrapModule
import com.skycommand.relay.runtime.permission.PermissionCoordinator
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionPort
import com.skycommand.relay.runtime.permission.PermissionPortCallback
import com.skycommand.relay.runtime.permission.PermissionSnapshot
import com.skycommand.relay.runtime.permission.PermissionState
import com.skycommand.relay.runtime.permission.PermissionCancellation
import com.skycommand.relay.runtime.service.ForegroundServiceCallback
import com.skycommand.relay.runtime.service.ForegroundServiceController
import com.skycommand.relay.runtime.service.ForegroundServicePort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AppRuntimeContractTest {
    @Test fun startsThroughPermissionsServiceAndModulesAndStopsInReverseOrder() {
        val permission = PermissionCoordinator.create(ImmediatePermissionPort())
        val service = ForegroundServiceController.create(ImmediateServicePort())
        val events = mutableListOf<String>()
        val runtime = AppRuntime.create(permission, service, AppBootstrap.create(listOf(Module("gateway", events))))

        assertIs<RuntimeStartResult.Accepted>(runtime.start(setOf(PermissionKind.RUNTIME)))
        assertEquals(RuntimeState.RUNNING, runtime.snapshot())
        assertIs<RuntimeStopResult.Accepted>(runtime.stop())
        assertEquals(RuntimeState.STOPPED, runtime.snapshot())
        assertEquals(listOf("start:gateway", "stop:gateway"), events)
    }

    @Test fun waitsForAndCanCancelPermissionRequest() {
        val permissionPort = DeferredPermissionPort()
        val runtime = AppRuntime.create(
            PermissionCoordinator.create(permissionPort),
            ForegroundServiceController.create(ImmediateServicePort()),
            AppBootstrap.create(emptyList()),
        )

        val result = assertIs<RuntimeStartResult.Accepted>(runtime.start(setOf(PermissionKind.RUNTIME)))
        assertEquals(RuntimeState.WAITING_PERMISSIONS, runtime.snapshot())
        result.cancellation.cancel()

        assertEquals(RuntimeState.STOPPED, runtime.snapshot())
    }

    @Test fun stopsTheForegroundServiceWhenModulesFailToStart() {
        val runtime = AppRuntime.create(
            PermissionCoordinator.create(ImmediatePermissionPort()),
            ForegroundServiceController.create(ImmediateServicePort()),
            AppBootstrap.create(listOf(Module("gateway", mutableListOf(), failStart = true))),
        )

        assertEquals(
            RuntimeStartFailure.MODULES,
            assertIs<RuntimeStartResult.Rejected>(runtime.start(setOf(PermissionKind.RUNTIME))).reason,
        )
        assertEquals(RuntimeState.FAILED, runtime.snapshot())
    }

    @Test fun stoppingDuringServiceStartupWaitsToStopTheServiceWithoutStartingModules() {
        val service = DeferredServicePort()
        val events = mutableListOf<String>()
        val runtime = AppRuntime.create(
            PermissionCoordinator.create(ImmediatePermissionPort()),
            ForegroundServiceController.create(service),
            AppBootstrap.create(listOf(Module("relay", events))),
        )

        assertIs<RuntimeStartResult.Accepted>(runtime.start(setOf(PermissionKind.RUNTIME)))
        assertEquals(RuntimeState.STARTING_SERVICE, runtime.snapshot())

        assertIs<RuntimeStopResult.Accepted>(runtime.stop())
        assertEquals(RuntimeState.STOPPING, runtime.snapshot())

        service.started()
        assertEquals(RuntimeState.STOPPING, runtime.snapshot())
        assertEquals(emptyList(), events)

        service.stopped()
        assertEquals(RuntimeState.STOPPED, runtime.snapshot())
        assertEquals(emptyList(), events)
    }

    @Test fun stoppingDuringModuleStartupCleansStartedModulesBeforeStoppingService() {
        val moduleStarted = CountDownLatch(1)
        val releaseModule = CountDownLatch(1)
        val service = ImmediateServicePort()
        val events = mutableListOf<String>()
        val module = object : BootstrapModule {
            override val name = "relay"
            override fun start() {
                moduleStarted.countDown()
                assertTrue(releaseModule.await(2, TimeUnit.SECONDS))
                events += "start:relay"
            }
            override fun stop() { events += "stop:relay" }
        }
        val runtime = AppRuntime.create(
            PermissionCoordinator.create(ImmediatePermissionPort()),
            ForegroundServiceController.create(service),
            AppBootstrap.create(listOf(module)),
        )

        val startThread = thread(start = true) { runtime.start(setOf(PermissionKind.RUNTIME)) }
        assertTrue(moduleStarted.await(2, TimeUnit.SECONDS))
        assertIs<RuntimeStopResult.Accepted>(runtime.stop())
        assertEquals(RuntimeState.STOPPING, runtime.snapshot())

        releaseModule.countDown()
        startThread.join(2_000)
        assertTrue(!startThread.isAlive)
        assertEquals(RuntimeState.STOPPED, runtime.snapshot())
        assertEquals(listOf("start:relay", "stop:relay"), events)
    }

    @Test fun reportsTheStableStopFailureReasonWhenAModuleCannotStop() {
        val runtime = AppRuntime.create(
            PermissionCoordinator.create(ImmediatePermissionPort()),
            ForegroundServiceController.create(ImmediateServicePort()),
            AppBootstrap.create(listOf(Module("gateway", mutableListOf(), failStop = true))),
        )
        runtime.start(setOf(PermissionKind.RUNTIME))

        val result = assertIs<RuntimeStopResult.Rejected>(runtime.stop())

        assertEquals(RuntimeStopFailure.STOP_FAILURE, result.reason)
        assertEquals(RuntimeState.FAILED, runtime.snapshot())
    }

    @Test fun unexpectedForegroundServiceFailureStopsModulesAndLeavesRuntimeFailed() {
        val events = mutableListOf<String>()
        val service = FailingServicePort()
        val runtime = AppRuntime.create(
            PermissionCoordinator.create(ImmediatePermissionPort()),
            ForegroundServiceController.create(service),
            AppBootstrap.create(listOf(Module("relay", events))),
        )

        assertIs<RuntimeStartResult.Accepted>(runtime.start(setOf(PermissionKind.RUNTIME)))
        assertEquals(RuntimeState.RUNNING, runtime.snapshot())

        service.fail()

        assertEquals(RuntimeState.FAILED, runtime.snapshot())
        assertEquals(listOf("start:relay", "stop:relay"), events)
    }

    private class Module(
        override val name: String,
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
        private val failStop: Boolean = false,
    ) : BootstrapModule {
        override fun start() {
            events += "start:$name"
            if (failStart) error("module failure")
        }
        override fun stop() {
            events += "stop:$name"
            if (failStop) error("module stop failure")
        }
    }

    private class ImmediatePermissionPort : PermissionPort {
        override fun snapshot() = PermissionSnapshot.granted(PermissionKind.RUNTIME)
        override fun request(required: Set<PermissionKind>, callback: PermissionPortCallback) = PermissionCancellation { }
    }

    private class DeferredPermissionPort : PermissionPort {
        private var callback: PermissionPortCallback? = null
        override fun snapshot() = PermissionSnapshot.of(mapOf(PermissionKind.RUNTIME to PermissionState.DENIED))
        override fun request(required: Set<PermissionKind>, callback: PermissionPortCallback): PermissionCancellation {
            this.callback = callback
            return PermissionCancellation { }
        }
    }

    private class ImmediateServicePort : ForegroundServicePort {
        override fun start(callback: ForegroundServiceCallback) { callback.started() }
        override fun stop(callback: ForegroundServiceCallback) { callback.stopped() }
    }

    private class FailingServicePort : ForegroundServicePort {
        private var unexpectedFailure: (() -> Unit)? = null

        override fun start(callback: ForegroundServiceCallback) {
            callback.started()
        }

        override fun stop(callback: ForegroundServiceCallback) { callback.stopped() }

        override fun onUnexpectedFailure(listener: () -> Unit) =
            com.skycommand.relay.runtime.service.ForegroundServiceRegistration {
                if (unexpectedFailure === listener) unexpectedFailure = null
            }.also { unexpectedFailure = listener }

        fun fail() = requireNotNull(unexpectedFailure).invoke()
    }

    private class DeferredServicePort : ForegroundServicePort {
        private var startCallback: ForegroundServiceCallback? = null
        private var stopCallback: ForegroundServiceCallback? = null

        override fun start(callback: ForegroundServiceCallback) {
            startCallback = callback
        }

        override fun stop(callback: ForegroundServiceCallback) {
            stopCallback = callback
        }

        fun started() = requireNotNull(startCallback).started()

        fun stopped() = requireNotNull(stopCallback).stopped()
    }
}
