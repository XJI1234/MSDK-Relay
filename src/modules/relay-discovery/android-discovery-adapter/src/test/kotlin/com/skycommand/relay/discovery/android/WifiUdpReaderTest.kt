package com.skycommand.relay.discovery.android

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.*

class WifiUdpReaderTest {
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
