package com.skycommand.relay.runtime.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PermissionCoordinatorContractTest {
    @Test fun skipsSatisfiedPermissionsAndRejectsInvalidOrDuplicateRequests() {
        val port = FakePort(PermissionSnapshot.of(mapOf(PermissionKind.RUNTIME to PermissionState.GRANTED)))
        val coordinator = PermissionCoordinator.create(port)

        assertIs<PermissionRequestResult.AlreadySatisfied>(coordinator.request(setOf(PermissionKind.RUNTIME)) {})
        assertIs<PermissionRequestResult.Rejected>(coordinator.request(emptySet(), {}))
        coordinator.request(setOf(PermissionKind.USB_ACCESS), {})
        assertEquals(PermissionRejection.ALREADY_IN_PROGRESS, assertIs<PermissionRequestResult.Rejected>(coordinator.request(setOf(PermissionKind.RUNTIME), {})).reason)
        assertEquals(1, port.requests)
    }

    @Test fun completesOnceAndIgnoresDuplicateAndLateCallbacksAfterCancellation() {
        val port = FakePort(PermissionSnapshot.empty())
        val coordinator = PermissionCoordinator.create(port)
        val results = mutableListOf<PermissionRequestResult.Terminal>()
        val started = assertIs<PermissionRequestResult.Started>(coordinator.request(setOf(PermissionKind.RUNTIME)) { results += it })

        started.cancellation.cancel()
        port.complete(PermissionSnapshot.granted(PermissionKind.RUNTIME))
        port.complete(PermissionSnapshot.denied(PermissionKind.RUNTIME))

        assertEquals(listOf<PermissionRequestResult.Terminal>(PermissionRequestResult.Terminal.Cancelled), results)
    }

    @Test fun publishesCompletionAndIsolatesListenerFailures() {
        val port = FakePort(PermissionSnapshot.empty())
        val coordinator = PermissionCoordinator.create(port)
        var changes = 0
        coordinator.onChanged { changes += 1 }
        coordinator.onChanged { error("listener failure") }
        val results = mutableListOf<PermissionRequestResult.Terminal>()
        coordinator.request(setOf(PermissionKind.RUNTIME)) { results += it }
        port.complete(PermissionSnapshot.granted(PermissionKind.RUNTIME))

        assertEquals(1, changes)
        assertIs<PermissionRequestResult.Terminal.Completed>(results.single())
        assertEquals(PermissionState.GRANTED, coordinator.snapshot().stateOf(PermissionKind.RUNTIME))
        assertTrue(port.requests == 1)
    }

    @Test fun reportsPlatformCallbackFailureExactlyOnce() {
        val port = FakePort(PermissionSnapshot.empty())
        val coordinator = PermissionCoordinator.create(port)
        val results = mutableListOf<PermissionRequestResult.Terminal>()
        coordinator.request(setOf(PermissionKind.RUNTIME)) { results += it }

        port.fail()
        port.complete(PermissionSnapshot.granted(PermissionKind.RUNTIME))

        assertEquals(listOf<PermissionRequestResult.Terminal>(PermissionRequestResult.Terminal.Failed), results)
    }

    private class FakePort(initial: PermissionSnapshot) : PermissionPort {
        private var callback: PermissionPortCallback? = null
        private var current: PermissionSnapshot = initial
        var requests = 0

        override fun snapshot(): PermissionSnapshot = current

        override fun request(required: Set<PermissionKind>, callback: PermissionPortCallback): PermissionCancellation {
            requests += 1
            this.callback = callback
            return PermissionCancellation { }
        }

        fun complete(snapshot: PermissionSnapshot) {
            current = snapshot
            callback?.completed(snapshot)
        }

        fun fail() {
            callback?.failed()
        }
    }
}
