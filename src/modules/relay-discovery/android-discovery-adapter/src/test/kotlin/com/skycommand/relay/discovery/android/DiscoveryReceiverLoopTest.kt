package com.skycommand.relay.discovery.android

import com.skycommand.relay.discovery.DiscoveryCoordinator
import com.skycommand.relay.discovery.DiscoveryNetworkGeneration
import com.skycommand.relay.discovery.Ipv4Subnet
import org.junit.Test
import kotlin.test.*

class DiscoveryReceiverLoopTest {
    private class Sink : DiscoveryReceiverSink {
        val coordinator = DiscoveryCoordinator()
        val states = mutableListOf<DiscoveryReceiverState>()
        val packets = mutableListOf<Triple<DiscoveryNetworkGeneration, ByteArray, Ipv4Subnet>>()
        var advances = 0
        var ticks = 0
        var failPacket = false
        var soliciting = true
        override fun shouldSolicit() = soliciting
        override fun advanceNetwork(): DiscoveryNetworkGeneration {
            advances++
            return coordinator.advanceNetwork()
        }
        override fun receive(generation: DiscoveryNetworkGeneration, bytes: ByteArray, sourceIpv4: String, subnet: Ipv4Subnet) {
            if (failPacket) error("sink failed")
            assertEquals("192.168.1.2", sourceIpv4)
            packets += Triple(generation, bytes, subnet)
        }
        override fun tick() { ticks++ }
        override fun onStateChanged(state: DiscoveryReceiverState) { states += state }
    }
    private class Platform : ReceiverPlatform {
        class Task(val delay: Long, val action: () -> Unit) { var cancelled = false }
        class Reader(val packet: (ByteArray, String) -> Unit, val failed: () -> Unit) : DiscoveryReader {
            var requests = 0
            var canRequest = true
            override fun requestAnnouncement(): Boolean { requests++; return canRequest }
            var closes = 0
            override fun close() { closes++ }
            fun deliver(bytes: ByteArray = byteArrayOf(1)) = packet(bytes, "192.168.1.2")
        }
        lateinit var changed: (Long, WifiLink?) -> Unit
        val tasks = mutableListOf<Task>()
        val readers = mutableListOf<Reader>()
        var watches = 0
        var unwatch = 0
        var opens = 0
        var failOpen = false
        var failWatch = false
        override fun watch(changed: (Long, WifiLink?) -> Unit): ReceiverCancellation {
            watches++
            if (failWatch) error("watch failed")
            this.changed = changed
            return ReceiverCancellation { unwatch++ }
        }
        override fun open(link: WifiLink, packet: (ByteArray, String) -> Unit, failed: () -> Unit): DiscoveryReader {
            opens++
            if (failOpen) error("bind failed")
            return Reader(packet, failed).also { readers += it }
        }
        override fun later(delayMillis: Long, task: () -> Unit): ReceiverCancellation {
            val scheduled = Task(delayMillis, task).also { tasks += it }
            return ReceiverCancellation { scheduled.cancelled = true }
        }
        fun update(link: WifiLink) = changed(link.handle, link)
        fun lose(handle: Long) = changed(handle, null)
    }
    private class Fixture {
        val platform = Platform()
        val sink = Sink()
        val loop = DiscoveryReceiverLoop(platform, sink)
        val wifi = WifiLink(1, "wlan0", "192.168.1.10", 24)
        init { loop.start() }
        fun listen() { platform.update(wifi) }
    }

    @Test fun startsOnlyOnceAndTicksWithoutWifi() {
        val f = Fixture()
        f.loop.start()
        assertEquals(1, f.platform.watches)
        assertEquals(listOf(DiscoveryReceiverState.WAITING_WIFI), f.sink.states)
        f.platform.tasks.single().action()
        assertEquals(1, f.sink.ticks)
        assertEquals(2, f.platform.tasks.size)
        assertEquals(0, f.platform.opens)
    }

    @Test fun solicitsAtEachTickUntilConnectedAndResumesAfterDisconnect() {
        val f = Fixture()
        f.listen()
        val reader = f.platform.readers.single()
        assertEquals(1, reader.requests)
        repeat(20) { f.platform.tasks.last().action() }
        assertEquals(21, reader.requests)
        f.sink.soliciting = false
        repeat(20) { f.platform.tasks.last().action() }
        assertEquals(21, reader.requests)
        f.sink.soliciting = true
        f.platform.tasks.last().action()
        assertEquals(22, reader.requests)
        f.platform.update(f.wifi.copy(ipv4 = "192.168.1.11"))
        assertEquals(1, f.platform.readers.last().requests)
        val current = f.platform.readers.last()
        f.platform.lose(1)
        f.platform.tasks.last().action()
        assertEquals(1, current.requests)
    }

    @Test fun blockedSolicitationKeepsPassiveReceptionAndTicking() {
        val f = Fixture()
        f.listen()
        val reader = f.platform.readers.single().apply { canRequest = false }
        f.platform.tasks.last().action()
        reader.deliver()
        assertEquals(1, f.sink.packets.size)
        assertEquals(1, f.sink.ticks)
        assertEquals(DiscoveryReceiverState.LISTENING, f.sink.states.last())
        assertEquals(0, reader.closes)
    }

    @Test fun solicitationUsesRealSubnetBroadcasts() {
        assertEquals(listOf("10.20.191.255", "255.255.255.255"), WifiLink(1, "wlan0", "10.20.168.217", 18).broadcastAddresses())
        assertEquals(listOf("192.168.1.3", "255.255.255.255"), WifiLink(1, "wlan0", "192.168.1.2", 30).broadcastAddresses())
    }

    @Test fun priorComputerIsOnlyASameSubnetDiscoveryHintWithBroadcastFallback() {
        val wifi = WifiLink(1, "wlan0", "10.20.168.217", 18)
        assertEquals(listOf("10.20.165.251", "10.20.191.255", "255.255.255.255"), wifi.queryAddresses("10.20.165.251"))
        for (invalid in listOf(null, "10.21.1.2", "10.20.128.0", "10.20.191.255", "127.0.0.1", "bad", "10.20.168.217")) {
            assertEquals(wifi.broadcastAddresses(), wifi.queryAddresses(invalid))
        }
    }

    @Test fun duplicateLinkPreservesSocketAndGeneration() {
        val f = Fixture()
        f.listen()
        repeat(10) { f.listen() }
        assertEquals(1, f.platform.opens)
        assertEquals(1, f.sink.advances)
        f.platform.readers.single().deliver()
        assertSame(f.sink.coordinator.networkGeneration(), f.sink.packets.single().first)
    }

    @Test fun otherWifiArrivalAndLossKeepCurrentLink() {
        val f = Fixture()
        f.listen()
        f.platform.update(f.wifi.copy(handle = 2))
        f.platform.lose(2)
        assertEquals(1, f.platform.opens)
        assertEquals(0, f.platform.readers.single().closes)
    }

    @Test fun selectedWifiLossMovesToRemainingLinkAndIgnoresOldReader() {
        val f = Fixture()
        f.listen()
        val old = f.platform.readers.single()
        f.platform.update(f.wifi.copy(handle = 2))
        f.platform.lose(1)
        old.deliver()
        old.failed()
        assertEquals(1, old.closes)
        assertEquals(2, f.platform.opens)
        assertTrue(f.sink.packets.isEmpty())
        f.platform.readers.last().deliver()
        assertSame(f.sink.coordinator.networkGeneration(), f.sink.packets.single().first)
    }

    @Test fun addressPrefixAndInterfaceChangesEachAdvanceGeneration() {
        val f = Fixture()
        f.listen()
        f.platform.update(f.wifi.copy(ipv4 = "192.168.1.11"))
        f.platform.update(f.wifi.copy(prefix = 23))
        f.platform.update(f.wifi.copy(interfaceName = "wlan1"))
        assertEquals(4, f.platform.opens)
        assertEquals(4, f.sink.advances)
        assertTrue(f.platform.readers.dropLast(1).all { it.closes == 1 })
    }

    @Test fun invalidSubnetAndMissingInterfaceDoNotOpen() {
        val f = Fixture()
        listOf(0, 31, 32).forEach { f.platform.update(f.wifi.copy(prefix = it)) }
        f.platform.update(f.wifi.copy(ipv4 = "127.0.0.1"))
        f.platform.update(f.wifi.copy(interfaceName = ""))
        assertEquals(0, f.platform.opens)
    }

    @Test fun networkLossClosesAndWaitsWithoutRetry() {
        val f = Fixture()
        f.listen()
        f.platform.lose(1)
        assertEquals(1, f.platform.readers.single().closes)
        assertEquals(DiscoveryReceiverState.WAITING_WIFI, f.sink.states.last())
        assertEquals(1, f.platform.tasks.size) // only the tick
        assertEquals(2, f.sink.advances)
    }

    @Test fun bindingFailuresBackOffToEightSeconds() {
        val f = Fixture()
        f.platform.failOpen = true
        f.listen()
        val delays = mutableListOf<Long>()
        repeat(6) {
            val retry = f.platform.tasks.last()
            delays += retry.delay
            retry.action()
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L, 8000L), delays)
        assertEquals(DiscoveryReceiverState.RETRYING, f.sink.states.last())
    }

    @Test fun successfulBindAloneDoesNotResetRepeatedReceiveFailureBackoff() {
        val f = Fixture()
        f.listen()
        f.platform.readers.last().failed()
        assertEquals(1000L, f.platform.tasks.last().delay)
        f.platform.tasks.last().action()
        f.platform.readers.last().failed()
        assertEquals(2000L, f.platform.tasks.last().delay)
        f.platform.tasks.last().action()
        f.platform.readers.last().deliver()
        f.platform.readers.last().failed()
        assertEquals(1000L, f.platform.tasks.last().delay)
    }

    @Test fun staleCancelledRetryCannotReopenOnNewNetwork() {
        val f = Fixture()
        f.listen()
        f.platform.readers.last().failed()
        val retry = f.platform.tasks.last()
        f.platform.update(f.wifi.copy(ipv4 = "192.168.1.11"))
        assertTrue(retry.cancelled)
        retry.action() // model an already queued task
        assertEquals(2, f.platform.opens)
    }

    @Test fun packetBoundsAndSubnetReachSink() {
        val f = Fixture()
        f.listen()
        val reader = f.platform.readers.single()
        reader.deliver(byteArrayOf())
        reader.deliver(ByteArray(513))
        reader.deliver(ByteArray(512))
        assertEquals(1, f.sink.packets.size)
        assertEquals(512, f.sink.packets.single().second.size)
        // Core recognizes this receiving subnet's directed broadcast as non-host.
        val invalid = com.skycommand.relay.discovery.AnnouncementParser.parse(byteArrayOf(1), "192.168.1.255", f.sink.packets.single().third)
        assertEquals(com.skycommand.relay.discovery.AnnouncementParseResult.Rejected(com.skycommand.relay.discovery.AnnouncementRejection.SOURCE), invalid)
    }

    @Test fun closeIsPermanentAndSuppressesLateEvents() {
        val f = Fixture()
        f.listen()
        val old = f.platform.readers.single()
        f.loop.close()
        f.loop.close()
        f.loop.start()
        old.deliver()
        old.failed()
        f.platform.update(f.wifi.copy(ipv4 = "192.168.1.11"))
        f.platform.tasks.forEach { it.action() }
        assertEquals(1, old.closes)
        assertEquals(1, f.platform.unwatch)
        assertEquals(1, f.platform.opens)
        assertTrue(f.sink.packets.isEmpty())
        assertEquals(0, f.sink.ticks)
        assertTrue(f.platform.tasks.all { it.cancelled })
    }

    @Test fun closeBeforeStartDoesNotRegister() {
        val platform = Platform()
        val loop = DiscoveryReceiverLoop(platform, Sink())
        loop.close()
        loop.start()
        assertEquals(0, platform.watches)
    }

    @Test fun registrationFailureReportsFailureWithoutTimer() {
        val platform = Platform().apply { failWatch = true }
        val sink = Sink()
        val loop = DiscoveryReceiverLoop(platform, sink)
        loop.start()
        assertEquals(listOf(DiscoveryReceiverState.FAILED), sink.states)
        assertTrue(platform.tasks.isEmpty())
        loop.close()
    }

    @Test fun sinkFailureStopsAllSources() {
        val f = Fixture()
        f.listen()
        f.sink.failPacket = true
        f.platform.readers.single().deliver()
        assertEquals(DiscoveryReceiverState.FAILED, f.sink.states.last())
        assertEquals(1, f.platform.readers.single().closes)
        assertEquals(1, f.platform.unwatch)
        assertTrue(f.platform.tasks.all { it.cancelled })
    }
}
