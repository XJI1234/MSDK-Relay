package com.skycommand.relay.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiscoveryCoordinatorTest {
    @Test fun initiallyWaitsAndIgnoresInvalidAnnouncements() {
        val f = DiscoveryFixture()
        assertEquals(DiscoveryPhase.WAITING, f.coordinator.snapshot().phase)
        f.coordinator.receive(f.network, byteArrayOf(0), source)
        f.coordinator.receive(f.network, announcement(), "127.0.0.1")
        val snapshot = f.advance(4000)
        assertEquals(DiscoveryPhase.WAITING, snapshot.phase)
        assertEquals(0, snapshot.candidateCount); assertNull(snapshot.target)
    }

    @Test fun observesThreeSecondsThenKeepsSameTokenAcrossRepeatedAnnouncements() {
        val f = DiscoveryFixture()
        assertEquals(DiscoveryPhase.OBSERVING, f.receive().phase)
        assertNull(f.advance(2999).target)
        val selected = assertNotNull(f.advance(1).target)
        assertEquals("ws://$source:8080/relay", selected.endpoint)
        repeat(5) {
            f.advance(1000)
            assertSame(selected.selectionToken, f.receive().target?.selectionToken)
        }
        assertEquals(1, f.coordinator.snapshot().candidateCount)
    }

    @Test fun expiresExactlyAtEightSecondsAndInvalidPacketsNeverRefresh() {
        val f = DiscoveryFixture(); val selected = f.select()
        f.now = 7999
        f.coordinator.receive(f.network, "{}".toByteArray(), source)
        assertSame(selected.selectionToken, f.coordinator.snapshot().target?.selectionToken)
        val expired = f.advance(1)
        assertEquals(DiscoveryPhase.WAITING, expired.phase); assertNull(expired.target)
        assertFalse(f.coordinator.activate(selected.selectionToken))
    }

    @Test fun aFreshPacketAfterExpiryRequiresANewObservationAndToken() {
        val f = DiscoveryFixture(); val old = f.select(); f.now = 8000
        assertEquals(DiscoveryPhase.OBSERVING, f.receive().phase)
        assertFalse(f.coordinator.activate(old.selectionToken))
        assertNull(f.advance(2999).target)
        val next = assertNotNull(f.advance(1).target)
        assertNotSame(old.selectionToken, next.selectionToken)
    }

    @Test fun twoCandidatesConflictAndTheSurvivorIsObservedAgain() {
        val f = DiscoveryFixture(); f.receive(); f.advance(1000)
        assertEquals(DiscoveryPhase.CONFLICT, f.receive(1).phase)
        f.now = 7000; f.receive(1)
        assertEquals(DiscoveryPhase.CONFLICT, f.coordinator.snapshot().phase)
        assertEquals(DiscoveryPhase.OBSERVING, f.advance(1000).phase)
        assertNull(f.advance(2999).target)
        val selected = assertNotNull(f.advance(1).target)
        assertTrue(selected.identity.serverId.endsWith("000000000001"))
    }

    @Test fun conflictWhileConnectingInvalidatesPendingPaired() {
        val f = DiscoveryFixture(); val selected = f.select()
        val conflict = f.receive(1)
        assertEquals(DiscoveryPhase.CONFLICT, conflict.phase); assertNull(conflict.target)
        assertFalse(f.coordinator.activate(selected.selectionToken))
    }

    @Test fun sameServerWithNewProcessInstanceIsAConflictUntilTheOldInstanceExpires() {
        val f = DiscoveryFixture(); val selected = f.select()
        val restarted = announcement().toString(Charsets.UTF_8)
            .replace(instanceId, "33333333-3333-4333-8333-333333333333").toByteArray()
        assertEquals(DiscoveryPhase.CONFLICT, f.coordinator.receive(f.network, restarted, source).phase)
        assertFalse(f.coordinator.activate(selected.selectionToken))
        f.now = 8000
        assertEquals(DiscoveryPhase.OBSERVING, f.coordinator.snapshot().phase)
    }

    @Test fun duplicateTargetsDeduplicateAndLatestAddressWinsWithDeterministicTies() {
        val f = DiscoveryFixture(); f.receive(ip = "10.20.1.20"); f.receive(ip = "10.20.1.10")
        assertEquals(1, f.coordinator.snapshot().candidateCount)
        val selected = assertNotNull(f.advance(3000).target)
        assertEquals("ws://10.20.1.10:8080/relay", selected.endpoint)
        f.advance(1)
        val changed = assertNotNull(f.receive(ip = "10.20.1.20").target)
        assertEquals("ws://10.20.1.20:8080/relay", changed.endpoint)
        assertNotSame(selected.selectionToken, changed.selectionToken)
        assertFalse(f.coordinator.activate(selected.selectionToken))
        assertTrue(f.coordinator.activate(changed.selectionToken))
    }

    @Test fun sameAddressChangedPortCreatesANewSelectionAndOldPairedIsRejected() {
        val f = DiscoveryFixture(); val selected = f.select()
        f.advance(1); val changed = assertNotNull(f.receive(port = 8081).target)
        assertEquals("ws://$source:8081/relay", changed.endpoint)
        assertFalse(f.coordinator.activate(selected.selectionToken))
        assertTrue(f.coordinator.activate(changed.selectionToken))
    }

    @Test fun expiresEachEndpointIndependently() {
        val f = DiscoveryFixture(); f.receive(ip = "10.20.1.10"); f.advance(2000)
        f.receive(ip = "10.20.1.20"); f.now = 8000
        assertEquals("ws://10.20.1.20:8080/relay", f.coordinator.snapshot().target?.endpoint)
        f.now = 10000
        assertEquals(DiscoveryPhase.WAITING, f.coordinator.snapshot().phase)
    }

    @Test fun activeConnectionStaysDespiteDuplicatesSecondComputerNewAddressAndExpiry() {
        val f = DiscoveryFixture(); val active = f.select()
        assertTrue(f.coordinator.activate(active.selectionToken))
        f.receive(); f.receive(1); f.advance(1); f.receive(ip = "10.20.1.1")
        val snapshot = f.advance(10000)
        assertEquals(DiscoveryPhase.ACTIVE, snapshot.phase)
        assertEquals(0, snapshot.candidateCount)
        assertSame(active.selectionToken, snapshot.target?.selectionToken)
        assertEquals(active.endpoint, snapshot.target?.endpoint)
        assertTrue(f.coordinator.activate(active.selectionToken))
    }

    @Test fun disconnectWaitsWhenNoCandidatesAndObservesUniqueSurvivorWhenAvailable() {
        val empty = DiscoveryFixture(); val old = empty.select(); empty.coordinator.activate(old.selectionToken)
        empty.advance(8000)
        assertEquals(DiscoveryPhase.WAITING, empty.coordinator.disconnected(old.selectionToken).phase)
        val f = DiscoveryFixture(); val active = f.select(); f.coordinator.activate(active.selectionToken)
        assertEquals(DiscoveryPhase.OBSERVING, f.coordinator.disconnected(active.selectionToken).phase)
        assertNull(f.advance(2999).target)
        val next = assertNotNull(f.advance(1).target)
        assertNotSame(active.selectionToken, next.selectionToken)
        assertTrue(f.coordinator.activate(next.selectionToken))
        assertSame(next.selectionToken, f.coordinator.disconnected(active.selectionToken).target?.selectionToken)
    }

    @Test fun disconnectDuringMultiComputerConflictDoesNotChooseThePreviousComputer() {
        val f = DiscoveryFixture(); val active = f.select(); f.coordinator.activate(active.selectionToken)
        f.receive(1)
        val conflict = f.coordinator.disconnected(active.selectionToken)
        assertEquals(DiscoveryPhase.CONFLICT, conflict.phase); assertNull(conflict.target)
    }

    @Test fun networkGenerationInvalidatesOldPacketsAndPendingPairedAndRestartsObservation() {
        val f = DiscoveryFixture(); val old = f.select()
        val nextNetwork = f.coordinator.advanceNetwork()
        assertNotSame(f.network, nextNetwork)
        assertEquals(DiscoveryPhase.WAITING, f.receive().phase)
        assertFalse(f.coordinator.activate(old.selectionToken))
        f.network = nextNetwork
        assertEquals(DiscoveryPhase.OBSERVING, f.receive().phase)
        val selected = assertNotNull(f.advance(3000).target)
        assertNotSame(old.selectionToken, selected.selectionToken)
    }

    @Test fun networkChangeKeepsAnActiveSessionUntilTransportDisconnects() {
        val f = DiscoveryFixture(); val active = f.select(); f.coordinator.activate(active.selectionToken)
        f.network = f.coordinator.advanceNetwork()
        assertSame(active.selectionToken, f.coordinator.snapshot().target?.selectionToken)
        f.receive(1)
        assertSame(active.selectionToken, f.coordinator.snapshot().target?.selectionToken)
        assertEquals(DiscoveryPhase.OBSERVING, f.coordinator.disconnected(active.selectionToken).phase)
        val next = assertNotNull(f.advance(3000).target)
        assertTrue(next.identity.serverId.endsWith("000000000001"))
    }

    @Test fun candidateOverflowFailsClosedAndAllowsRecoveryAfterEightSeconds() {
        val f = DiscoveryFixture()
        repeat(32) { f.receive(it) }
        assertEquals(32, f.coordinator.snapshot().candidateCount)
        assertFalse(f.coordinator.snapshot().capacityIsolation)
        val overflow = f.receive(32)
        assertEquals(DiscoveryPhase.CONFLICT, overflow.phase); assertTrue(overflow.capacityIsolation)
        repeat(7) { f.advance(1000); f.receive(0) }
        assertTrue(f.coordinator.snapshot().capacityIsolation)
        assertEquals(DiscoveryPhase.WAITING, f.advance(1000).phase)
        assertFalse(f.coordinator.snapshot().capacityIsolation)
        assertEquals(DiscoveryPhase.OBSERVING, f.receive().phase)
        assertNotNull(f.advance(3000).target)
    }

    @Test fun endpointOverflowAlsoFailsClosedAndDoesNotInterruptActiveSessions() {
        val f = DiscoveryFixture(); val active = f.select(); f.coordinator.activate(active.selectionToken)
        repeat(7) { f.receive(ip = "10.20.1.${it + 1}") }
        assertFalse(f.coordinator.snapshot().capacityIsolation)
        val overflow = f.receive(ip = "10.20.1.8")
        assertEquals(DiscoveryPhase.ACTIVE, overflow.phase); assertTrue(overflow.capacityIsolation)
        assertSame(active.selectionToken, overflow.target?.selectionToken)
        assertEquals(DiscoveryPhase.CONFLICT, f.coordinator.disconnected(active.selectionToken).phase)
        assertEquals(DiscoveryPhase.WAITING, f.advance(8000).phase)
    }

    @Test fun timeRegressionDoesNotMakeObservationCompleteEarlyOrUndoExpiry() {
        val f = DiscoveryFixture(); f.now = 5000; f.receive(); f.now = 4000
        assertEquals(DiscoveryPhase.OBSERVING, f.coordinator.snapshot().phase)
        f.now = 7999; assertNull(f.coordinator.snapshot().target)
        f.now = 8000; assertNotNull(f.coordinator.snapshot().target)
        f.now = 13000; assertEquals(DiscoveryPhase.WAITING, f.coordinator.snapshot().phase)
        f.now = 5000; assertEquals(DiscoveryPhase.WAITING, f.coordinator.snapshot().phase)
    }

    @Test fun tokensFromOtherCoordinatorsAndOldDisconnectsAreIgnored() {
        val f = DiscoveryFixture(); val target = f.select()
        val other = DiscoveryFixture(); val unrelated = other.select()
        assertFalse(f.coordinator.activate(unrelated.selectionToken))
        assertSame(target.selectionToken, f.coordinator.disconnected(unrelated.selectionToken).target?.selectionToken)
        assertTrue(f.coordinator.activate(target.selectionToken))
        assertSame(target.selectionToken, f.coordinator.disconnected(unrelated.selectionToken).target?.selectionToken)
        assertEquals(DiscoveryPhase.ACTIVE, f.coordinator.snapshot().phase)
    }

    @Test fun earlierSnapshotsRemainImmutableWhenTheCoordinatorChanges() {
        val f = DiscoveryFixture(); val old = f.advance(0)
        f.receive(); val observed = f.coordinator.snapshot(); f.advance(3000); f.receive(1)
        assertEquals(DiscoveryPhase.WAITING, old.phase); assertEquals(0, old.candidateCount)
        assertEquals(DiscoveryPhase.OBSERVING, observed.phase); assertEquals(1, observed.candidateCount)
        assertNull(observed.target)
    }

    @Test fun arbitraryEventSequencePreservesCapacityAndTargetInvariants() {
        val f = DiscoveryFixture()
        val random = kotlin.random.Random(20261013)
        repeat(3000) {
            when (random.nextInt(6)) {
                0, 1 -> f.receive(random.nextInt(40), "10.20.1.${random.nextInt(1, 20)}")
                2 -> f.advance(random.nextLong(0, 1000))
                3 -> f.coordinator.snapshot().target?.let { f.coordinator.activate(it.selectionToken) }
                4 -> f.coordinator.snapshot().target?.let { f.coordinator.disconnected(it.selectionToken) }
                else -> { f.network = f.coordinator.advanceNetwork() }
            }
            val snapshot = f.coordinator.snapshot()
            assertTrue(snapshot.candidateCount in 0..32)
            assertEquals(snapshot.phase in setOf(DiscoveryPhase.SELECTED, DiscoveryPhase.ACTIVE), snapshot.target != null)
            if (snapshot.phase == DiscoveryPhase.SELECTED) {
                assertEquals(1, snapshot.candidateCount); assertFalse(snapshot.capacityIsolation)
            }
        }
    }

    @Test fun concurrentActivationAndConflictHaveOneLegalLinearOrder() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val f = DiscoveryFixture(); val target = f.select()
                val ready = java.util.concurrent.CountDownLatch(1)
                val activation = executor.submit<Boolean> { ready.await(); f.coordinator.activate(target.selectionToken) }
                val conflict = executor.submit<DiscoverySnapshot> { ready.await(); f.receive(1) }
                ready.countDown()
                val activated = activation.get(5, java.util.concurrent.TimeUnit.SECONDS)
                conflict.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val snapshot = f.coordinator.snapshot()
                assertEquals(2, snapshot.candidateCount)
                assertEquals(if (activated) DiscoveryPhase.ACTIVE else DiscoveryPhase.CONFLICT, snapshot.phase)
                if (activated) assertSame(target.selectionToken, snapshot.target?.selectionToken) else assertNull(snapshot.target)
            }
        } finally { executor.shutdownNow() }
    }
}
