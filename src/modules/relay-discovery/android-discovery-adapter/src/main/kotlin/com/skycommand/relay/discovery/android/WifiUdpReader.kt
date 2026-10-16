package com.skycommand.relay.discovery.android

import com.skycommand.relay.discovery.DiscoveryLimits
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

internal fun copyIpv4Datagram(packet: DatagramPacket): Pair<ByteArray, String>? {
    if (packet.length !in 1..DiscoveryLimits.MAX_DATAGRAM_BYTES || packet.address !is Inet4Address) return null
    return packet.data.copyOfRange(packet.offset, packet.offset + packet.length) to packet.address.hostAddress!!
}

/** The socket and Wi-Fi lock either both transfer to the reader or both roll back. */
internal class WifiUdpReader private constructor(
    private val socket: DatagramSocket,
    private val wifiLock: AutoCloseable,
    private val packet: (ByteArray, String) -> Unit,
    private val failed: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    internal val localPort: Int get() = socket.localPort

    private fun receive() {
        val bytes = ByteArray(DiscoveryLimits.MAX_DATAGRAM_BYTES + 1)
        val datagram = DatagramPacket(bytes, bytes.size)
        try {
            while (!closed.get()) {
                datagram.length = bytes.size
                socket.receive(datagram)
                val received = copyIpv4Datagram(datagram) ?: continue
                if (!closed.get()) packet(received.first, received.second)
            }
        } catch (_: Exception) {
            if (!closed.get()) failed()
        } finally { close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { socket.close() } finally { runCatching { wifiLock.close() } }
    }

    companion object {
        fun open(
            acquireWifiLock: () -> AutoCloseable,
            bindNetwork: (DatagramSocket) -> Unit,
            packet: (ByteArray, String) -> Unit,
            failed: () -> Unit,
            port: Int = DiscoveryLimits.UDP_PORT,
        ): WifiUdpReader {
            val wifiLock = acquireWifiLock()
            var socket: DatagramSocket? = null
            try {
                val current = DatagramSocket(null)
                socket = current
                // Exclusive wildcard bind accepts directed and limited broadcasts.
                current.bind(InetSocketAddress(port))
                bindNetwork(current)
                val reader = WifiUdpReader(current, wifiLock, packet, failed)
                Thread(reader::receive, "relay-discovery-udp").apply { isDaemon = true }.start()
                return reader
            } catch (failure: Exception) {
                runCatching { socket?.close() }
                runCatching { wifiLock.close() }
                throw failure
            }
        }
    }
}
