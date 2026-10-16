package com.skycommand.relay.discovery.android

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.*

class WifiUdpReaderTest {
    @Test fun broadcastOptionFailureKeepsUnicastAndPassiveReception() {
        DatagramSocket(0).use { desktop ->
            desktop.soTimeout = 1_000
            val received = LinkedBlockingQueue<ByteArray>()
            val reader = WifiUdpReader.open(
                acquireWifiLock = { AutoCloseable {} }, bindNetwork = {},
                packet = { bytes, _ -> received.add(bytes) }, failed = { fail("passive receiver failed") },
                port = 0, queryAddresses = listOf("127.0.0.1"), queryPort = desktop.localPort,
                createSocket = {
                    object : DatagramSocket(null as java.net.SocketAddress?) {
                        override fun setBroadcast(on: Boolean) { throw java.net.SocketException("broadcast unavailable") }
                    }
                },
            )
            try {
                assertTrue(reader.requestAnnouncement())
                val request = DatagramPacket(ByteArray(128), 128)
                desktop.receive(request)
                val announcement = byteArrayOf(1, 2, 3)
                desktop.send(DatagramPacket(announcement, announcement.size, request.address, request.port))
                assertContentEquals(announcement, assertNotNull(received.poll(3, TimeUnit.SECONDS)))
            } finally { reader.close() }
        }
    }

    @Test fun requestsDoNotWaitForTheAndroidReceiveMonitor() {
        DatagramSocket(0).use { desktop ->
            desktop.soTimeout = 1_000
            var ownedSocket: DatagramSocket? = null
            val reader = WifiUdpReader.open(
                acquireWifiLock = { AutoCloseable {} }, bindNetwork = { ownedSocket = it },
                packet = { _, _ -> }, failed = {},
                port = 0, queryAddresses = listOf("127.0.0.1"), queryPort = desktop.localPort,
                createSocket = {
                    object : DatagramSocket(null as java.net.SocketAddress?) {
                        override fun setBroadcast(on: Boolean) = synchronized(this) { super.setBroadcast(on) }
                    }
                },
            )
            try {
                // Android receive() holds this monitor while waiting for a packet.
                // setBroadcast() also takes it; solicitation must not change socket options.
                synchronized(assertNotNull(ownedSocket)) {
                    val request = CompletableFuture.supplyAsync { reader.requestAnnouncement() }
                    assertTrue(request.get(1, TimeUnit.SECONDS))
                    val packet = DatagramPacket(ByteArray(128), 128)
                    desktop.receive(packet)
                    assertEquals("SKY_COMMAND_RELAY_DISCOVER_V1", String(packet.data, 0, packet.length, Charsets.US_ASCII))
                }
            } finally { reader.close() }
        }
    }

    @Test fun requestsAnAnnouncementFromTheOwnedReceiveSocketAndStopsAfterClose() {
        DatagramSocket(0).use { desktop ->
            desktop.soTimeout = 1_000
            val reader = WifiUdpReader.open(
                acquireWifiLock = { AutoCloseable {} }, bindNetwork = {}, packet = { _, _ -> }, failed = {},
                port = 0, queryAddresses = listOf("127.0.0.1"), queryPort = desktop.localPort,
            )
            try {
                assertTrue(reader.requestAnnouncement())
                val packet = DatagramPacket(ByteArray(128), 128)
                desktop.receive(packet)
                assertEquals("SKY_COMMAND_RELAY_DISCOVER_V1", String(packet.data, 0, packet.length, Charsets.US_ASCII))
                assertEquals(reader.localPort, packet.port)
            } finally { reader.close() }
            assertFalse(reader.requestAnnouncement())
        }
    }
    @Test fun copiesOnlyActualLengthAndOffset() {
        val buffer = byteArrayOf(9, 1, 2, 9, 9)
        val packet = DatagramPacket(buffer, 1, 2, InetAddress.getByName("192.168.1.2"), 39082)
        val received = assertNotNull(copyIpv4Datagram(packet))
        buffer.fill(0)
        assertContentEquals(byteArrayOf(1, 2), received.first)
        assertEquals("192.168.1.2", received.second)
    }

    @Test fun rejectsEmptyOversizedAndIpv6Packets() {
        val ipv4 = InetAddress.getByName("192.168.1.2")
        assertNull(copyIpv4Datagram(DatagramPacket(ByteArray(0), 0, ipv4, 1)))
        assertNull(copyIpv4Datagram(DatagramPacket(ByteArray(513), 513, ipv4, 1)))
        assertNull(copyIpv4Datagram(DatagramPacket(byteArrayOf(1), 1, InetAddress.getByName("::1"), 1)))
        assertNotNull(copyIpv4Datagram(DatagramPacket(ByteArray(512), 512, ipv4, 1)))
    }

    @Test fun realUdpResetsCapacityAfterShortPacketAndDropsTruncatedOversize() {
        val queue = LinkedBlockingQueue<ByteArray>()
        val releases = AtomicInteger()
        val failures = AtomicInteger()
        val reader = WifiUdpReader.open(
            acquireWifiLock = { AutoCloseable { releases.incrementAndGet() } },
            bindNetwork = {}, packet = { bytes, _ -> queue.add(bytes) },
            failed = { failures.incrementAndGet() }, port = 0,
        )
        try {
            DatagramSocket().use { sender ->
                fun send(bytes: ByteArray) = sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), reader.localPort))
                send(byteArrayOf(7))
                assertContentEquals(byteArrayOf(7), assertNotNull(queue.poll(3, TimeUnit.SECONDS)))
                send(ByteArray(512) { 8 })
                assertContentEquals(ByteArray(512) { 8 }, assertNotNull(queue.poll(3, TimeUnit.SECONDS)))
                send(ByteArray(2048) { 9 })
                send(byteArrayOf(10, 11))
                assertContentEquals(byteArrayOf(10, 11), assertNotNull(queue.poll(3, TimeUnit.SECONDS)))
                assertTrue(queue.isEmpty())
            }
        } finally { reader.close() }
        reader.close()
        assertEquals(1, releases.get())
        assertEquals(0, failures.get())
    }

    @Test fun networkBindFailureClosesSocketAndReleasesLock() {
        val releases = AtomicInteger()
        var socket: DatagramSocket? = null
        assertFailsWith<IllegalStateException> {
            WifiUdpReader.open(
                acquireWifiLock = { AutoCloseable { releases.incrementAndGet() } },
                bindNetwork = { socket = it; error("bind network failed") },
                packet = { _, _ -> }, failed = {}, port = 0,
            )
        }
        assertTrue(assertNotNull(socket).isClosed)
        assertEquals(1, releases.get())
    }

    @Test fun exclusivePortFailureRollsBackWifiLock() {
        val releases = AtomicInteger()
        DatagramSocket(null).use { occupied ->
            occupied.bind(InetSocketAddress(0))
            assertFailsWith<java.net.BindException> {
                WifiUdpReader.open(
                    acquireWifiLock = { AutoCloseable { releases.incrementAndGet() } },
                    bindNetwork = { fail("network binding must not happen") },
                    packet = { _, _ -> }, failed = {}, port = occupied.localPort,
                )
            }
            assertEquals(1, releases.get())
        }
    }
}
