package com.skycommand.relay.runtime.discovery

import com.skycommand.relay.discovery.*
import com.skycommand.relay.gateway.session.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RelayConnectionControlTest {
    private class Source(val sink: DiscoverySourceSink) : DiscoverySource {
        lateinit var network: DiscoveryNetworkGeneration
        var starts = 0
        var closes = 0
        var failStart = false
        var failClose = false
        var failSynchronously = false
        override fun start() {
            starts++
            network = sink.advanceNetwork()
            if (failStart) error("source started partly")
            if (failSynchronously) sink.failed()
        }
        override fun close() { closes++; if (failClose) error("source close failed") }
        fun receive(candidate: Int = 0) = sink.receive(network, announcement(candidate), "192.168.1.20", checkNotNull(Ipv4Subnet.create("192.168.1.10", 24)))
        fun tick() = sink.tick()
    }
    private class Fixture {
        lateinit var session: BridgeFixture
        val sources = mutableListOf<Source>()
        var configureSource: (Source) -> Unit = {}
        var failFactory = false
        val control = RelayConnectionControl(
            DiscoverySourceFactory { sink ->
                if (failFactory) error("factory failed")
                Source(sink).also { configureSource(it); sources += it }
            }, DiscoveryClock { session.now },
        )
        init {
            session = BridgeFixture(control.pairingAdmission, bindBridge = false)
            control.bind(session.port)
        }
        val source get() = sources.last()
        fun select() { assertTrue(control.start()); source.receive(); session.now += 3000; source.tick() }
        fun active() { select(); session.current.pair() }
    }

    @Test fun defaultsToAutoAndWaitsWithoutAddress() {
        val f = Fixture()
        repeat(3) { assertTrue(f.control.start()) }
        assertEquals(RelayConnectionMode.AUTO, f.control.snapshot().mode)
        assertTrue(f.control.snapshot().running)
        assertEquals(DiscoveryPhase.WAITING, f.control.snapshot().discovery?.phase)
        assertEquals(1, f.sources.size)
        assertEquals(1, f.session.port.starts)
        assertTrue(f.session.attempts.isEmpty())
        f.control.close()
    }

    @Test fun automaticToManualSwitchClosesSourceAndOldSessionOnly() {
        val f = Fixture(); f.active()
        val source = f.source
        val old = f.session.current
        assertTrue(f.control.manual("ws://192.168.1.30:8080/relay"))
        assertEquals(1, source.closes)
        assertEquals(1, old.closes)
        assertEquals(RelayConnectionMode.MANUAL, f.control.snapshot().mode)
        assertNull(f.control.snapshot().discovery)
        old.pair(); old.command()
        assertTrue(f.session.frames.isEmpty())
        f.session.current.pair(); f.session.current.command()
        assertEquals(SessionState.ACTIVE, f.session.session.snapshot().state)
        assertEquals(1, f.session.frames.size)
        f.control.close()
    }

    @Test fun invalidManualInputLeavesHealthyAutomaticConnectionUntouched() {
        val f = Fixture(); f.active()
        val old = f.session.current
        assertFalse(f.control.manual("https://bad/relay"))
        assertFalse(f.control.manual(""))
        assertEquals(RelayConnectionMode.AUTO, f.control.snapshot().mode)
        assertEquals(0, old.closes)
        assertEquals(0, f.source.closes)
        f.control.close()
    }

    @Test fun sameManualAddressKeepsSelectionAndBackoff() {
        val f = Fixture()
        assertTrue(f.control.manual("ws://192.168.1.30:8080/relay"))
        assertTrue(f.control.start())
        f.session.current.fail()
        val retry = f.session.tasks.last()
        val selection = f.session.port.selections.last()
        repeat(5) { assertTrue(f.control.manual("ws://192.168.1.30:8080/relay")) }
        assertFalse(retry.cancelled)
        assertSame(selection, f.session.port.selections.last())
        assertEquals(1, f.session.port.starts)
        assertTrue(f.sources.isEmpty())
        f.control.close()
    }

    @Test fun differentManualAddressReplacesIntentAndRejectsOldPaired() {
        val f = Fixture()
        f.control.manual("ws://192.168.1.30:8080/relay"); f.control.start()
        val old = f.session.current
        val selection = f.session.port.selections.last()
        f.control.manual("ws://192.168.1.31:8080/relay")
        assertEquals(1, old.closes)
        assertNotSame(selection, f.session.port.selections.last())
        old.pair(); old.command()
        assertTrue(f.session.frames.isEmpty())
        f.control.close()
    }

    @Test fun returningToAutomaticReobservesAndAllOldSourceEventsAreHarmless() {
        val f = Fixture(); f.select()
        val old = f.source
        val oldGeneration = old.network
        f.control.manual("ws://192.168.1.30:8080/relay")
        f.control.automatic()
        val fresh = f.source
        assertNotSame(old, fresh)
        old.receive(); old.tick(); old.sink.failed()
        assertSame(oldGeneration, old.sink.advanceNetwork())
        assertTrue(f.control.snapshot().running)
        assertFalse(f.control.snapshot().failed)
        assertEquals(DiscoveryPhase.WAITING, f.control.snapshot().discovery?.phase)
        fresh.receive()
        f.session.now += 2999; fresh.tick()
        assertEquals(DiscoveryPhase.OBSERVING, f.control.snapshot().discovery?.phase)
        f.session.now++; fresh.tick()
        assertEquals(DiscoveryPhase.SELECTED, f.control.snapshot().discovery?.phase)
        f.session.current.pair(); fresh.tick()
        assertEquals(DiscoveryPhase.ACTIVE, f.control.snapshot().discovery?.phase)
        f.control.close()
    }

    @Test fun oldAutoGateIsRevokedDuringSlowCleanup() {
        val f = Fixture(); f.select()
        val selection = checkNotNull(f.session.port.selections.last())
        val generation = f.session.current.generation
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.session.port.beforeSelect = { if (it == null) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val switching = executor.submit { f.control.manual("ws://192.168.1.30:8080/relay") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try { assertFalse(f.control.pairingAdmission.admit(selection, generation)) }
            finally { release.countDown() }
            switching.get(5, TimeUnit.SECONDS)
            assertFalse(f.control.pairingAdmission.admit(selection, generation))
        } finally { release.countDown(); f.session.port.beforeSelect = {}; f.control.close(); executor.shutdownNow() }
    }

    @Test fun stopAndRestartCreateNewSourceButCloseIsPermanent() {
        val f = Fixture(); f.select()
        val old = f.source
        assertTrue(f.control.stop()); assertTrue(f.control.stop())
        assertEquals(1, old.closes)
        assertFalse(f.control.snapshot().running)
        assertTrue(f.control.start())
        assertNotSame(old, f.source)
        f.control.close(); f.control.close()
        assertFalse(f.control.start())
        assertFalse(f.control.automatic())
        assertFalse(f.control.manual("ws://192.168.1.30:8080/relay"))
        f.source.receive(); f.source.tick(); f.source.sink.failed()
        assertFalse(f.control.snapshot().running)
    }

    @Test fun partialSourceStartupFailureClosesSourceAndSessionAndCanRetry() {
        val f = Fixture()
        f.configureSource = { it.failStart = true }
        assertFalse(f.control.start())
        assertTrue(f.control.snapshot().failed)
        assertEquals(1, f.source.closes)
        assertEquals(SessionState.STOPPED, f.session.session.snapshot().state)
        f.configureSource = {}
        assertTrue(f.control.start())
        assertEquals(2, f.sources.size)
        assertFalse(f.control.snapshot().failed)
        f.control.close()
    }

    @Test fun factoryFailureAndSynchronousSourceFailureBothRollBack() {
        val f = Fixture(); f.failFactory = true
        assertFalse(f.control.start())
        assertEquals(SessionState.STOPPED, f.session.session.snapshot().state)
        f.failFactory = false; f.configureSource = { it.failSynchronously = true }
        assertFalse(f.control.start())
        assertTrue(f.control.snapshot().failed)
        assertEquals(1, f.source.closes)
        assertEquals(SessionState.STOPPED, f.session.session.snapshot().state)
        f.control.close()
    }

    @Test fun sourceFailureRevokesGateAndStopsSessionWithoutReopening() {
        val f = Fixture(); f.active()
        val old = f.session.current
        f.source.sink.failed()
        assertEquals(1, old.closes)
        assertTrue(f.control.snapshot().failed)
        assertFalse(f.control.snapshot().running)
        assertEquals(1, f.sources.size)
        old.command()
        assertTrue(f.session.frames.isEmpty())
        f.control.close()
    }

    @Test fun failedSourceCleanupRetainsHandleAndBlocksSecondSourceUntilReleased() {
        val f = Fixture(); f.select()
        val old = f.source; old.failClose = true
        assertFalse(f.control.manual("ws://192.168.1.30:8080/relay"))
        assertEquals(1, old.closes)
        assertTrue(f.control.snapshot().failed)
        assertFalse(f.control.snapshot().running)
        assertFalse(f.control.start())
        assertEquals(2, old.closes)
        assertEquals(1, f.sources.size)
        old.failClose = false
        assertTrue(f.control.start())
        assertEquals(3, old.closes)
        assertEquals(2, f.sources.size)
        f.control.close()
    }

    @Test fun manualControlFailureRollsBackEvenIfSelectionWasAlreadyApplied() {
        val f = Fixture()
        f.control.manual("ws://192.168.1.30:8080/relay")
        f.session.port.afterSelect = { if (it != null) error("partial selection failure") }
        assertFalse(f.control.start())
        assertTrue(f.control.snapshot().failed)
        assertEquals(SessionState.STOPPED, f.session.session.snapshot().state)
        f.session.port.afterSelect = {}
        assertTrue(f.control.start())
        f.control.close()
    }

    @Test fun failedPauseDuringSwitchStillStopsSessionAndRequiresCleanRetry() {
        val f = Fixture(); f.active()
        f.session.port.beforeSelect = { if (it == null) error("pause failed") }
        assertFalse(f.control.manual("ws://192.168.1.30:8080/relay"))
        assertTrue(f.control.snapshot().failed)
        assertFalse(f.control.snapshot().running)
        assertEquals(SessionState.STOPPED, f.session.session.snapshot().state)
        assertEquals(1, f.sources.size)
        f.session.port.beforeSelect = {}
        assertTrue(f.control.start())
        assertEquals(2, f.sources.size)
        f.control.close()
    }

    @Test fun notifiesOutsideControlLockAndIsolatesListenerFailure() {
        lateinit var control: RelayConnectionControl
        val executor = Executors.newSingleThreadExecutor()
        try {
            var callbacks = 0
            control = RelayConnectionControl(DiscoverySourceFactory { error("manual needs no source") }, onChanged = {
                callbacks++
                // If callback holds controlLock, this operation cannot complete.
                executor.submit { control.bind(object : DiscoverySessionPort {
                    override fun start() {}
                    override fun stop() {}
                    override fun selectConnection(selection: ConnectionSelection?) {}
                    override fun snapshot(): SessionSnapshot = error("unused")
                    override fun onStateChanged(listener: SessionStateListener) = Registration {}
                }) }.get(5, TimeUnit.SECONDS)
                error("UI listener failed")
            })
            assertTrue(control.manual("ws://192.168.1.30:8080/relay"))
            assertEquals(1, callbacks)
            control.close()
        } finally { executor.shutdownNow() }
    }
}
