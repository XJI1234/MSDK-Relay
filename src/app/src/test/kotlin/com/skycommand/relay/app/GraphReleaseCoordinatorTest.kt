package com.skycommand.relay.app

import com.skycommand.relay.runtime.*
import com.skycommand.relay.runtime.bootstrap.AppBootstrap
import com.skycommand.relay.runtime.permission.*
import com.skycommand.relay.runtime.service.*
import kotlin.test.*

class GraphReleaseCoordinatorTest {
    @Test fun lateForegroundStartIsStoppedBeforePortsAreReleased() {
        val servicePort = DelayedService()
        val service = ForegroundServiceController.create(servicePort)
        val permissions = PermissionCoordinator.create(object : PermissionPort {
            override fun snapshot() = PermissionSnapshot.granted(PermissionKind.RUNTIME)
            override fun request(required: Set<PermissionKind>, callback: PermissionPortCallback) = PermissionCancellation {}
        })
        val runtime = AppRuntime.create(permissions, service, AppBootstrap.create(emptyList()))
        val queue = ArrayDeque<() -> Unit>()
        var releases = 0
        val coordinator = GraphReleaseCoordinator(runtime::snapshot, service::snapshot, { queue.add(it) }, { runtime.stop() }, { releases++ })
        runtime.onChanged { coordinator.changed() }
        service.onChanged { coordinator.changed() }
        runtime.start(setOf(PermissionKind.RUNTIME))
        coordinator.close(); drain(queue)
        assertEquals(RuntimeState.STOPPING, runtime.snapshot()); assertEquals(0, releases)
        servicePort.startCallback.started(); drain(queue)
        assertEquals(ForegroundServiceState.STOPPING, service.snapshot()); assertEquals(0, releases)
        servicePort.stopCallback.stopped(); drain(queue)
        assertEquals(RuntimeState.STOPPED, runtime.snapshot()); assertEquals(1, releases)
        coordinator.close(); coordinator.changed(); drain(queue); assertEquals(1, releases)
    }

    @Test fun runtimeFailureWhileServiceStillStopsDoesNotRelease() {
        val f = Fixture(); f.runtime = RuntimeState.FAILED; f.service = ForegroundServiceState.STOPPING
        f.coordinator.close(); f.drain(); assertEquals(0, f.releases)
        f.service = ForegroundServiceState.STOPPED; f.coordinator.changed(); f.drain()
        assertEquals(1, f.releases)
    }

    @Test fun terminalServiceDoesNotReleaseDuringModuleTransition() {
        val f = Fixture(); f.runtime = RuntimeState.STARTING_MODULES; f.service = ForegroundServiceState.FAILED
        f.coordinator.close(); f.drain(); assertEquals(0, f.releases)
        f.runtime = RuntimeState.FAILED; f.coordinator.changed(); f.drain(); assertEquals(1, f.releases)
    }

    @Test fun inFlightOperationPreventsStoppingAndRejectsNewCalls() {
        val f = Fixture(); assertTrue(f.coordinator.enter())
        f.coordinator.close(); f.drain(); assertEquals(0, f.stops); assertFalse(f.coordinator.enter())
        f.coordinator.leave(); f.drain(); assertEquals(1, f.stops); assertEquals(1, f.releases)
    }

    @Test fun synchronousTerminalCallbackCannotReleaseBeforeStopReturns() {
        var inStop = false; var released = false
        lateinit var coordinator: GraphReleaseCoordinator
        coordinator = GraphReleaseCoordinator({ RuntimeState.STOPPED }, { ForegroundServiceState.STOPPED }, { it() }, {
            inStop = true; coordinator.changed(); assertFalse(released); inStop = false
        }, { assertFalse(inStop); released = true })
        coordinator.close(); assertTrue(released)
    }

    @Test fun burstNotificationsAndRepeatedCloseReleaseOnce() {
        val f = Fixture()
        repeat(20) { f.coordinator.close(); f.coordinator.changed() }
        f.drain(); assertEquals(1, f.stops); assertEquals(1, f.releases)
    }

    @Test fun stopExceptionStillAllowsTerminalCleanup() {
        val queue = ArrayDeque<() -> Unit>(); var releases = 0
        val coordinator = GraphReleaseCoordinator({ RuntimeState.FAILED }, { ForegroundServiceState.FAILED }, { queue.add(it) }, { error("stop") }, { releases++ })
        coordinator.close(); assertFailsWith<IllegalStateException> { queue.removeFirst()() }
        drain(queue); assertEquals(1, releases)
    }

    private class DelayedService : ForegroundServicePort {
        lateinit var startCallback: ForegroundServiceCallback
        lateinit var stopCallback: ForegroundServiceCallback
        override fun start(callback: ForegroundServiceCallback) { startCallback = callback }
        override fun stop(callback: ForegroundServiceCallback) { stopCallback = callback }
    }
    private class Fixture {
        var runtime = RuntimeState.STOPPED; var service = ForegroundServiceState.STOPPED
        var releases = 0; var stops = 0
        val queue = ArrayDeque<() -> Unit>()
        val coordinator = GraphReleaseCoordinator({ runtime }, { service }, { queue.add(it) }, { stops++ }, { releases++ })
        fun drain() = drain(queue)
    }
    companion object {
        private fun drain(queue: ArrayDeque<() -> Unit>) { while (queue.isNotEmpty()) queue.removeFirst()() }
    }
}
