package com.skycommand.relay.app

import com.skycommand.relay.discovery.DiscoveryCoordinator
import com.skycommand.relay.discovery.DiscoveryNetworkGeneration
import com.skycommand.relay.discovery.Ipv4Subnet
import com.skycommand.relay.discovery.android.DiscoveryReceiverSink
import com.skycommand.relay.discovery.android.DiscoveryReceiverState
import com.skycommand.relay.runtime.discovery.DiscoverySourceSink
import kotlin.test.*

class AndroidConnectionDiscoverySourceTest {
    @Test fun solicitationFollowsConnectionStateAndStopsWhenSourceCloses() {
        val f = Fixture(); f.source.start()
        assertTrue(f.receiverSink.shouldSolicit())
        f.soliciting = false
        assertFalse(f.receiverSink.shouldSolicit())
        f.soliciting = true
        assertTrue(f.receiverSink.shouldSolicit())
        f.source.close()
        assertFalse(f.receiverSink.shouldSolicit())
    }
    @Test fun creationIsLazyAndStartAndCloseAreIdempotent() {
        val f = Fixture()
        assertEquals(0, f.created)
        f.source.start(); f.source.start()
        assertEquals(1, f.created); assertEquals(1, f.starts)
        f.source.close(); f.source.close(); f.source.start()
        assertEquals(1, f.closes); assertEquals(1, f.starts)
    }
    @Test fun closeBeforeStartNeverAllocatesAReceiver() {
        val f = Fixture(); f.source.close(); f.source.start()
        assertEquals(0, f.created)
    }
    @Test fun networkAndPacketKeepTheirOriginalIdentityAndPayload() {
        val f = Fixture(); f.source.start()
        val token = f.receiverSink.advanceNetwork()
        assertSame(f.generation, token)
        val bytes = byteArrayOf(1, 2)
        val subnet = assertNotNull(Ipv4Subnet.create("192.168.1.3", 24))
        f.receiverSink.receive(token, bytes, "192.168.1.9", subnet); f.receiverSink.tick()
        assertSame(token, f.receivedGeneration); assertSame(bytes, f.bytes)
        assertSame(subnet, f.subnet); assertEquals("192.168.1.9", f.ip); assertEquals(1, f.ticks)
    }
    @Test fun onlyTerminalFailureStopsControlAndRetiredEventsAreSuppressed() {
        val f = Fixture(); f.source.start()
        DiscoveryReceiverState.entries.forEach { f.receiverSink.onStateChanged(it) }
        assertEquals(1, f.failures)
        f.source.close(); f.receiverSink.onStateChanged(DiscoveryReceiverState.FAILED); f.receiverSink.tick()
        f.receiverSink.receive(f.generation, byteArrayOf(1), "192.168.1.9", assertNotNull(Ipv4Subnet.create("192.168.1.3", 24)))
        assertEquals(1, f.failures); assertEquals(0, f.ticks); assertNull(f.bytes)
    }
    @Test fun partialStartFailureCanStillReleaseTheReceiver() {
        val f = Fixture(); f.startFailure = true
        assertFailsWith<IllegalStateException> { f.source.start() }; f.source.close()
        assertEquals(1, f.closes)
    }
    @Test fun failedCleanupRetainsTheHandleForRetry() {
        val f = Fixture(); f.source.start(); f.closeFailure = true
        assertFailsWith<IllegalStateException> { f.source.close() }
        f.source.start(); assertEquals(1, f.starts)
        f.closeFailure = false; f.source.close(); f.source.close(); assertEquals(2, f.closes)
    }
    @Test fun callbackCanCloseSourceDuringStartWithoutResurrection() {
        val f = Fixture(); f.duringStart = { f.source.close() }
        f.source.start(); f.source.start(); f.source.close()
        assertEquals(1, f.starts); assertEquals(1, f.closes)
    }
    private class Fixture {
        var created = 0; var starts = 0; var closes = 0
        var startFailure = false; var closeFailure = false
        var duringStart: () -> Unit = {}
        var failures = 0; var ticks = 0
        var soliciting = true
        val generation = DiscoveryCoordinator().networkGeneration()
        var receivedGeneration: DiscoveryNetworkGeneration? = null
        var bytes: ByteArray? = null; var ip: String? = null; var subnet: Ipv4Subnet? = null
        lateinit var receiverSink: DiscoveryReceiverSink
        val source = AndroidConnectionDiscoverySource(object : DiscoverySourceSink {
            override fun advanceNetwork() = generation
            override fun receive(generation: DiscoveryNetworkGeneration, bytes: ByteArray, sourceIpv4: String, subnet: Ipv4Subnet) {
                receivedGeneration = generation; this@Fixture.bytes = bytes; ip = sourceIpv4; this@Fixture.subnet = subnet
            }
            override fun tick() { ticks++ }
            override fun failed() { failures++ }
        }, { soliciting }) { sink ->
            created++; receiverSink = sink
            object : ConnectionDiscoveryReceiver {
                override fun start() { starts++; duringStart(); if (startFailure) error("start") }
                override fun close() { closes++; if (closeFailure) error("close") }
            }
        }
    }
}
