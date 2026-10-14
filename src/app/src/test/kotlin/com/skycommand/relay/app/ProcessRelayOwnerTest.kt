package com.skycommand.relay.app

import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.runtime.permission.PermissionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProcessRelayOwnerTest {
    @Test fun recreatedUiReusesTheSameProcessResource() {
        val owner = ProcessRelayOwner<Any>(); var creates = 0
        val first = owner.acquire { creates++; Any() }
        assertSame(first, owner.acquire { creates++; Any() }); assertEquals(1, creates)
    }
    @Test fun failedCreationDoesNotPoisonTheProcessSlot() {
        val owner = ProcessRelayOwner<Any>()
        assertFailsWith<IllegalStateException> { owner.acquire { error("assembly") } }
        val resource = Any(); assertSame(resource, owner.acquire { resource })
    }
    @Test fun staleReleaseCannotRemoveANewerRuntime() {
        val owner = ProcessRelayOwner<Any>(); val old = owner.acquire { Any() }
        owner.release(old); val current = owner.acquire { Any() }; owner.release(old)
        assertSame(current, owner.acquire { error("must reuse") })
    }
    @Test fun simultaneousAttachmentsCreateOnlyOneRuntime() {
        val owner = ProcessRelayOwner<Any>(); val creates = AtomicInteger(); val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = List(20) { executor.submit<Any> { check(start.await(3, TimeUnit.SECONDS)); owner.acquire { creates.incrementAndGet(); Any() } } }
            start.countDown(); val first = futures.first().get(3, TimeUnit.SECONDS)
            futures.forEach { assertSame(first, it.get(3, TimeUnit.SECONDS)) }; assertEquals(1, creates.get())
        } finally { executor.shutdownNow() }
    }
    @Test fun denialDoesNotLoopOnResumeButAnExplicitRetryIsAllowed() {
        val policy = VisibleRelayStartPolicy()
        assertTrue(policy.shouldStart(RuntimeState.STOPPED, PermissionState.UNKNOWN, false))
        assertFalse(policy.shouldStart(RuntimeState.FAILED, PermissionState.DENIED, false))
        assertFalse(policy.shouldStart(RuntimeState.FAILED, PermissionState.PERMANENTLY_DENIED, false))
        assertTrue(policy.shouldStart(RuntimeState.FAILED, PermissionState.DENIED, true))
    }
    @Test fun grantFromSystemSettingsContinuesAutomatically() {
        val policy = VisibleRelayStartPolicy(); policy.shouldStart(RuntimeState.STOPPED, PermissionState.UNKNOWN, false)
        assertTrue(policy.shouldStart(RuntimeState.FAILED, PermissionState.GRANTED, false))
    }
    @Test fun interruptedPermissionHostAllowsOneRecoveryAttempt() {
        val policy = VisibleRelayStartPolicy(); policy.shouldStart(RuntimeState.STOPPED, PermissionState.UNKNOWN, false)
        policy.interruptedPermissionRequest()
        assertTrue(policy.shouldStart(RuntimeState.FAILED, PermissionState.DENIED, false))
        assertFalse(policy.shouldStart(RuntimeState.FAILED, PermissionState.DENIED, false))
    }
    @Test fun runningAndTransitionsNeverLaunchAParallelStartEvenExplicitly() {
        val policy = VisibleRelayStartPolicy()
        RuntimeState.entries.filter { it != RuntimeState.STOPPED && it != RuntimeState.FAILED }.forEach {
            assertFalse(policy.shouldStart(it, PermissionState.GRANTED, false))
            assertFalse(policy.shouldStart(it, PermissionState.GRANTED, true))
        }
    }
}
