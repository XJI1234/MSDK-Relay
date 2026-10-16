package com.skycommand.relay.runtime.permission.android

import com.skycommand.relay.runtime.permission.*
import org.junit.Test
import kotlin.test.*

class PermissionHostBindingTest {
    private class Host : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun detachReleasesIdentityAndHostAndFailsPendingRequestOnce() {
        val hosts = PermissionHostBinding<Host>()
        val identity = Any()
        val host = Host()
        val token = hosts.bind(identity, host)
        var failed = 0
        val request = hosts.begin(token, { fail("stale completion") }, { failed++ })
        assertTrue(hosts.detach(identity))
        assertFalse(hosts.detach(identity))
        hosts.complete(request)
        assertEquals(1, failed)
        assertEquals(1, host.closes)
        assertNull(token.identity)
        assertNull(token.host)
        assertNull(hosts.currentHost())
    }

    @Test fun lateDetachAndResultCannotAffectReplacementHost() {
        val hosts = PermissionHostBinding<Host>()
        val oldId = Any()
        val oldHost = Host()
        val oldToken = hosts.bind(oldId, oldHost)
        var failures = 0
        val oldRequest = hosts.begin(oldToken, { fail("stale result") }, { failures++ })
        val newHost = Host()
        val newToken = hosts.bind(Any(), newHost)
        var completed = 0
        val next = hosts.begin(newToken, { completed++ }, { fail("new request failure") })
        assertFalse(hosts.detach(oldId))
        hosts.complete(oldRequest)
        hosts.cancel(oldRequest)
        assertSame(newHost, hosts.currentHost())
        hosts.complete(next)
        hosts.complete(next)
        assertEquals(1, completed)
        assertEquals(1, failures)
        assertEquals(1, oldHost.closes)
        assertEquals(0, newHost.closes)
    }

    @Test fun cancelledAndDuplicateResultsCannotCompleteNewRequestOnSameHost() {
        val hosts = PermissionHostBinding<Host>()
        val token = hosts.bind(Any(), Host())
        val first = hosts.begin(token, { fail("cancelled") }, { fail("cancelled failure") })
        hosts.cancel(first)
        var completed = 0
        val second = hosts.begin(token, { completed++ }, { fail("failed") })
        hosts.complete(first)
        hosts.cancel(first)
        assertEquals(0, completed)
        hosts.complete(second)
        val third = hosts.begin(token, { completed++ }, { fail("failed") })
        hosts.complete(second)
        assertEquals(1, completed)
        hosts.complete(third)
        assertEquals(2, completed)
    }

    @Test fun closeDropsPendingWorkAndReleasesHostOnce() {
        val hosts = PermissionHostBinding<Host>()
        val host = Host()
        val token = hosts.bind(Any(), host)
        val request = hosts.begin(token, { fail("closed") }, { fail("close must be silent") })
        hosts.close()
        hosts.close()
        hosts.complete(request)
        assertNull(token.host)
        assertNull(token.identity)
        assertEquals(1, host.closes)
        assertFailsWith<IllegalStateException> { hosts.begin(token, {}, {}) }
    }

    @Test fun cleanupFailureDoesNotKeepHostOrLoseFailureTerminal() {
        val hosts = PermissionHostBinding<AutoCloseable>()
        val id = Any()
        val token = hosts.bind(id, AutoCloseable { error("unregister failed") })
        var failed = 0
        hosts.begin(token, {}, { failed++; error("listener failed") })
        hosts.detach(id)
        assertEquals(1, failed)
        assertNull(token.host)
        assertNull(hosts.currentHost())
    }

    @Test fun detachFlowsThroughAdapterCoordinatorAndAllowsNewRequestAfterRebind() {
        val hosts = PermissionHostBinding<Host>()
        var snapshot = PermissionSnapshot.denied(PermissionKind.RUNTIME)
        var lastRequest: PermissionHostBinding.Request<Host>? = null
        val platform = object : PermissionAdapterPlatform {
            override fun snapshot() = snapshot
            override fun requestRuntimePermissions(callback: () -> Unit, failure: () -> Unit): PermissionCancellation {
                val request = hosts.begin(checkNotNull(hosts.currentToken()), callback, failure)
                lastRequest = request
                return PermissionCancellation { hosts.cancel(request) }
            }
            override fun requestUsbPermission(callback: () -> Unit, failure: () -> Unit) = PermissionCancellation {}
            override fun rebind(activity: Any, activityResultRegistry: Any, lifecycleOwner: Any) { hosts.bind(activity, Host()) }
            override fun detach(activity: Any) { hosts.detach(activity) }
            override fun close() { hosts.close() }
        }
        val adapter = AndroidPermissionAdapter(platform)
        val coordinator = PermissionCoordinator.create(adapter)
        val oldId = Any()
        adapter.rebind(oldId, Any(), Any())
        val results = mutableListOf<PermissionRequestResult.Terminal>()
        coordinator.request(setOf(PermissionKind.RUNTIME)) { results += it }
        val oldRequest = checkNotNull(lastRequest)
        adapter.detach(oldId)
        assertEquals(1, results.size)
        assertSame(PermissionRequestResult.Terminal.Failed, results.single())
        adapter.rebind(Any(), Any(), Any())
        assertIs<PermissionRequestResult.Started>(coordinator.request(setOf(PermissionKind.RUNTIME)) { results += it })
        snapshot = PermissionSnapshot.granted(PermissionKind.RUNTIME)
        hosts.complete(oldRequest)
        assertEquals(1, results.size)
        hosts.complete(checkNotNull(lastRequest))
        assertIs<PermissionRequestResult.Terminal.Completed>(results.last())
        adapter.close()
    }
}
