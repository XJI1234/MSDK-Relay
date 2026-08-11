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
}
