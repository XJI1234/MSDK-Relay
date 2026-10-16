package com.skycommand.relay.runtime.discovery

import com.skycommand.relay.discovery.DiscoveryPhase
import com.skycommand.relay.gateway.session.SessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DiscoverySessionBridgeTest {
    @Test fun waitsWithoutEndpointAndDropsInputBeforeStartAndAfterClose() {
        val f = BridgeFixture()
        f.receive(); f.now = 3000; f.bridge.tick()
        assertEquals(DiscoveryPhase.WAITING, f.bridge.snapshot().phase)
        repeat(3) { assertTrue(f.bridge.start()) }
        assertEquals(1, f.port.starts); assertEquals(listOf(null), f.port.selections.toList())
        assertTrue(f.attempts.isEmpty()); assertTrue(f.tasks.isEmpty())
        f.bridge.close(); f.bridge.close(); f.receive(); f.bridge.tick()
        assertFalse(f.bridge.start()); assertEquals(1, f.port.stops); assertTrue(f.port.unregistered)
        assertEquals(DiscoveryPhase.WAITING, f.bridge.snapshot().phase)
        assertTrue(f.attempts.isEmpty())
    }

    @Test fun requiresBindingOnceAndCannotBindAfterClose() {
        val f = BridgeFixture()
        assertFailsWith<IllegalStateException> { f.bridge.bind(f.port) }
        val unbound = DiscoverySessionBridge()
        assertFailsWith<IllegalStateException> { unbound.start() }
        unbound.close(); assertFalse(unbound.start())
        assertFailsWith<IllegalStateException> { unbound.bind(f.port) }
    }

    @Test fun observesBeforeConnectingAndOnlyPairedMarksActive() {
        val f = BridgeFixture(); f.bridge.start(); f.receive()
        f.now = 2999; f.bridge.tick(); assertTrue(f.attempts.isEmpty())
        f.now = 3000; f.bridge.tick()
        assertEquals(DiscoveryPhase.SELECTED, f.bridge.snapshot().phase)
        assertEquals(SessionState.CONNECTING, f.session.snapshot().state)
        f.current.pair(); f.current.command()
        assertEquals(DiscoveryPhase.ACTIVE, f.bridge.snapshot().phase)
        assertEquals(SessionState.ACTIVE, f.session.snapshot().state); assertEquals(1, f.frames.size)
        f.bridge.close()
    }

    @Test fun duplicateAnnouncementsAndTicksNeverResetBackoffOrStartAgain() {
        val f = BridgeFixture(); f.select()
        val selected = f.port.selections.last()
        val delays = mutableListOf<Long>()
        repeat(6) {
            f.current.fail(); val retry = f.tasks.last(); delays += retry.delay
            repeat(5) { f.receive(); f.bridge.tick() }
            assertFalse(retry.cancelled); assertSame(selected, f.port.selections.last())
            f.retry()
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L), delays)
        assertEquals(1, f.port.starts); assertEquals(2, f.port.selections.size)
        f.bridge.close()
    }

    @Test fun conflictPausesPendingConnectionAndRejectsLatePairedAndBusiness() {
        val f = BridgeFixture(); f.select(); val old = f.current
        f.receive(1); old.pair(); old.command()
        assertEquals(DiscoveryPhase.CONFLICT, f.bridge.snapshot().phase)
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        assertEquals(1, old.closes); assertTrue(f.frames.isEmpty())
        assertTrue(f.notifications.none { it.first.snapshot.state == SessionState.ACTIVE })
        f.bridge.close()
    }

    @Test fun expiryCancelsRetryAndFreshSameUrlHasNewSelection() {
        val f = BridgeFixture(); f.select(); val selected = f.port.selections.last()
        f.current.fail(); val retry = f.tasks.last()
        f.now = 8000; f.bridge.tick(); retry.fire()
        assertTrue(retry.cancelled); assertEquals(1, f.attempts.size)
        assertEquals(DiscoveryPhase.WAITING, f.bridge.snapshot().phase)
        f.receive(); f.now = 11000; f.bridge.tick()
        assertNotSame(selected, f.port.selections.last()); assertEquals(2, f.attempts.size)
        f.bridge.close()
    }

    @Test fun changingEndpointCleansOnlySessionAndIgnoresOldCallbacks() {
        val f = BridgeFixture(); f.select(); val old = f.current
        f.now = 3001; f.receive(source = "192.168.1.21")
        assertEquals(2, f.attempts.size); assertEquals(1, old.closes)
        assertEquals("ws://192.168.1.21:8080/relay", f.current.endpoint)
        assertEquals(listOf(old.generation), f.cleanups.toList())
        old.pair(); old.command(); old.fail()
        assertEquals(SessionState.CONNECTING, f.session.snapshot().state); assertTrue(f.frames.isEmpty())
        f.current.pair(); assertEquals(DiscoveryPhase.ACTIVE, f.bridge.snapshot().phase)
        f.bridge.close()
    }

    @Test fun networkChangePausesUnpairedTargetAndRejectsOldNetworkPackets() {
        val f = BridgeFixture(); f.select(); val old = f.bridge.networkGeneration()
        val next = f.bridge.advanceNetwork(); assertNotSame(old, next)
        f.receive(network = old); f.now = 6000; f.bridge.tick()
        assertEquals(1, f.attempts.size); assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        f.receive(network = next); f.now = 9000; f.bridge.tick()
        assertEquals(2, f.attempts.size); f.bridge.close()
    }

    @Test fun healthyActiveSurvivesConflictsExpiryAndNetworkChange() {
        val f = BridgeFixture(); f.active(); val generation = f.current.generation
        f.receive(1); f.now = 20000; f.bridge.tick(); f.bridge.advanceNetwork(); f.receive(2)
        assertEquals(DiscoveryPhase.ACTIVE, f.bridge.snapshot().phase)
        assertEquals(generation, f.session.snapshot().generation); assertEquals(1, f.attempts.size)
        assertEquals(0, f.current.closes); f.bridge.close()
    }

    @Test fun tickDetectsEndedActiveBeforeDelayedNotificationsAndObservesAgain() {
        val f = BridgeFixture(); f.active(); f.current.fail()
        assertEquals(DiscoveryPhase.ACTIVE, f.bridge.snapshot().phase)
        f.bridge.tick()
        assertEquals(DiscoveryPhase.OBSERVING, f.bridge.snapshot().phase)
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        f.now = 5999; f.bridge.tick(); assertEquals(1, f.attempts.size)
        f.now = 6000; f.bridge.tick(); assertEquals(2, f.attempts.size)
        f.bridge.close()
    }

    @Test fun delayedOldEndDoesNotClearNewActiveOrReplaceItsSelection() {
        val f = BridgeFixture(); f.active(); val old = f.current
        old.fail(); f.bridge.tick(); f.now = 6000; f.bridge.tick(); f.current.pair()
        val next = f.current.generation; val selected = f.port.selections.last()
        f.drain()
        assertEquals(DiscoveryPhase.ACTIVE, f.bridge.snapshot().phase)
        assertEquals(next, f.session.snapshot().generation); assertSame(selected, f.port.selections.last())
        assertEquals(2, f.attempts.size); f.bridge.close()
    }

    @Test fun earlyRetryPairedCannotReuseStaleActiveToBypassConflict() {
        val f = BridgeFixture(); f.active(); f.receive(1); f.current.fail()
        f.retry(); f.current.pair(); f.current.command()
        assertEquals(DiscoveryPhase.CONFLICT, f.bridge.snapshot().phase)
        assertEquals(SessionState.STOPPED, f.session.snapshot().state); assertTrue(f.frames.isEmpty())
        assertEquals(1, f.notifications.count { it.first.snapshot.state == SessionState.ACTIVE })
        f.drain(); assertEquals(DiscoveryPhase.CONFLICT, f.bridge.snapshot().phase)
        f.bridge.close()
    }

    @Test fun earlyRetryEvenWithoutConflictMustObserveAfterActualActiveLoss() {
        val f = BridgeFixture(); f.active(); f.current.fail(); f.retry(); f.current.pair()
        assertEquals(DiscoveryPhase.OBSERVING, f.bridge.snapshot().phase)
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        f.bridge.tick(); f.now = 6000; f.bridge.tick(); f.current.pair()
        assertEquals(DiscoveryPhase.ACTIVE, f.bridge.snapshot().phase); f.bridge.close()
    }

    @Test fun controlFailureDoesNotCacheUnappliedSelectionAndNextTickRetries() {
        val f = BridgeFixture(); f.bridge.start(); f.receive(); f.now = 3000
        f.port.beforeSelect = { if (it != null) throw IllegalStateException("select failed") }
        assertFailsWith<IllegalStateException> { f.bridge.tick() }; assertTrue(f.attempts.isEmpty())
        f.port.beforeSelect = { }; f.bridge.tick()
        assertEquals(1, f.attempts.size); f.bridge.close()
    }

    @Test fun failedStartRevokesRunningAndCanBeExplicitlyRetried() {
        val f = BridgeFixture(); f.port.beforeStart = { throw IllegalStateException("start failed") }
        assertFailsWith<IllegalStateException> { f.bridge.start() }
        f.receive(); f.now = 3000; f.bridge.tick(); assertTrue(f.attempts.isEmpty())
        assertEquals(1, f.port.stops)
        f.port.beforeStart = { }; assertTrue(f.bridge.start()); f.receive(); f.now = 6000; f.bridge.tick()
        assertEquals(1, f.attempts.size); f.bridge.close()
    }

    @Test fun conflictStillPausesWhenAdapterThrowsAfterApplyingASelection() {
        val f = BridgeFixture(); f.bridge.start(); f.receive(); f.now = 3000
        f.port.afterSelect = { if (it != null) throw IllegalStateException("adapter failed after applying") }
        assertFailsWith<IllegalStateException> { f.bridge.tick() }
        assertEquals(SessionState.CONNECTING, f.session.snapshot().state)
        f.port.afterSelect = { }; f.receive(1)
        assertEquals(DiscoveryPhase.CONFLICT, f.bridge.snapshot().phase)
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        assertEquals(1, f.current.closes)
        f.bridge.close()
    }

    @Test fun closeAttemptsStopAndUnregisterEvenWhenPauseThrows() {
        val f = BridgeFixture(); f.active()
        f.port.beforeSelect = { if (it == null) throw IllegalStateException("pause failed") }
        assertFailsWith<IllegalStateException> { f.bridge.close() }
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        assertTrue(f.port.unregistered); assertEquals(1, f.port.stops)
        f.current.pair(); f.bridge.tick(); assertFalse(f.bridge.start())
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
    }

    @Test fun conflictWhileSelectionWaitsDoesNotDeadlockAdmissionOrAdmitBusiness() {
        concurrentSelectionChange(close = false)
    }

    @Test fun closeWhileSelectionWaitsRejectsPairedAndConvergesWithoutDeadlock() {
        concurrentSelectionChange(close = true)
    }

    @Test fun tickDuringAdmittedPairingDoesNotTreatSameGenerationAsDisconnected() {
        val f = BridgeFixture(); f.select()
        val admitted = CountDownLatch(1); val release = CountDownLatch(1)
        f.beforeCancel = { admitted.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pairing = executor.submit { f.current.pair() }
            assertTrue(admitted.await(5, TimeUnit.SECONDS))
            try {
                assertEquals(SessionState.AWAITING_PAIRING, f.session.snapshot().state)
                f.bridge.tick()
                assertEquals(DiscoveryPhase.ACTIVE, f.bridge.snapshot().phase)
                assertEquals(1, f.attempts.size); assertEquals(0, f.current.closes)
            } finally { release.countDown() }
            pairing.get(5, TimeUnit.SECONDS); f.current.command()
            assertEquals(SessionState.ACTIVE, f.session.snapshot().state); assertEquals(1, f.frames.size)
            f.bridge.close()
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun closingDoesNotHoldControlLockWhileWaitingForStateListenerToFinish() {
        val executor = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        try {
            val f = BridgeFixture(); f.active(); f.drain(); f.current.fail()
            val entered = CountDownLatch(1)
            f.port.beforeSelect = { if (it == null) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val notifying = executor.submit { f.drain() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = executor.submit { f.bridge.close() }
            try { awaitPhase(f, DiscoveryPhase.WAITING) } finally { release.countDown() }
            notifying.get(5, TimeUnit.SECONDS); closing.get(5, TimeUnit.SECONDS)
            assertTrue(f.port.unregistered); assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    private fun concurrentSelectionChange(close: Boolean) {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(30) {
                val f = BridgeFixture(); f.bridge.start(); f.receive(); f.now = 3000; f.autoPair = true
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                f.port.beforeSelect = { if (it != null) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
                val selecting = executor.submit { f.bridge.tick() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val changing = executor.submit { if (close) f.bridge.close() else f.receive(1) }
                try {
                    awaitPhase(f, if (close) DiscoveryPhase.WAITING else DiscoveryPhase.CONFLICT)
                } finally { release.countDown() }
                selecting.get(5, TimeUnit.SECONDS); changing.get(5, TimeUnit.SECONDS)
                assertEquals(SessionState.STOPPED, f.session.snapshot().state)
                assertTrue(f.notifications.none { it.first.snapshot.state == SessionState.ACTIVE })
                assertTrue(f.frames.isEmpty()); f.bridge.close()
            }
        } finally { executor.shutdownNow() }
    }

    private fun awaitPhase(f: BridgeFixture, phase: DiscoveryPhase) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (f.bridge.snapshot().phase != phase && System.nanoTime() < deadline) Thread.yield()
        assertEquals(phase, f.bridge.snapshot().phase)
    }
}
