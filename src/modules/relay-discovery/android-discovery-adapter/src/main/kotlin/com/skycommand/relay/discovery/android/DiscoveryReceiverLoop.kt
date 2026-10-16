package com.skycommand.relay.discovery.android

import com.skycommand.relay.discovery.DiscoveryLimits
import com.skycommand.relay.discovery.DiscoveryNetworkGeneration
import com.skycommand.relay.discovery.Ipv4Subnet

enum class DiscoveryReceiverState { WAITING_WIFI, LISTENING, RETRYING, FAILED }

interface DiscoveryReceiverSink {
    fun advanceNetwork(): DiscoveryNetworkGeneration
    fun receive(generation: DiscoveryNetworkGeneration, bytes: ByteArray, sourceIpv4: String, subnet: Ipv4Subnet)
    fun tick()
    fun onStateChanged(state: DiscoveryReceiverState) = Unit
}

internal data class WifiLink(val handle: Long, val interfaceName: String, val ipv4: String, val prefix: Int) {
    fun subnet(): Ipv4Subnet? = Ipv4Subnet.create(ipv4, prefix)
}

internal fun interface ReceiverCancellation { fun cancel() }

internal interface ReceiverPlatform {
    fun watch(changed: (Long, WifiLink?) -> Unit): ReceiverCancellation
    fun open(link: WifiLink, packet: (ByteArray, String) -> Unit, failed: () -> Unit): AutoCloseable
    fun later(delayMillis: Long, task: () -> Unit): ReceiverCancellation
}

/** All calls and platform callbacks are serialized by the owning receiver. */
internal class DiscoveryReceiverLoop(private val platform: ReceiverPlatform, private val sink: DiscoveryReceiverSink) {
    private class Epoch(val link: WifiLink, val generation: DiscoveryNetworkGeneration, val subnet: Ipv4Subnet)
    private val links = mutableMapOf<Long, WifiLink>()
    private var watcher: ReceiverCancellation? = null
    private var reader: AutoCloseable? = null
    private var retry: ReceiverCancellation? = null
    private var ticker: ReceiverCancellation? = null
    private var epoch: Epoch? = null
    private var failures = 0
    private var started = false
    private var closed = false
    private var failed = false
    private var state: DiscoveryReceiverState? = null

    fun start() {
        if (started || closed) return
        started = true
        try {
            // watch callbacks must be queued, never inline during registration.
            watcher = platform.watch(::networkChanged)
            report(DiscoveryReceiverState.WAITING_WIFI)
            scheduleTick()
        } catch (_: Exception) { fail() }
    }

    private fun networkChanged(handle: Long, link: WifiLink?) = guarded {
        if (link != null && link.handle == handle && link.interfaceName.isNotBlank() && link.subnet() != null) {
            links[handle] = link
        } else links.remove(handle)
        val next = epoch?.link?.handle?.let(links::get) ?: links.values.minByOrNull { it.handle }
        if (next == epoch?.link) return@guarded
        clearReader()
        failures = 0
        val generation = sink.advanceNetwork()
        if (next == null) report(DiscoveryReceiverState.WAITING_WIFI)
        else {
            val current = Epoch(next, generation, checkNotNull(next.subnet()))
            epoch = current
            open(current)
        }
    }

    private fun open(current: Epoch) {
        try {
            reader = platform.open(current.link, { bytes, source -> packet(current, bytes, source) }, { broken(current) })
            report(DiscoveryReceiverState.LISTENING)
        } catch (_: Exception) { broken(current) }
    }

    private fun packet(current: Epoch, bytes: ByteArray, source: String) = guarded {
        if (epoch !== current || reader == null) return@guarded
        if (bytes.isEmpty() || bytes.size > DiscoveryLimits.MAX_DATAGRAM_BYTES) return@guarded
        failures = 0
        sink.receive(current.generation, bytes, source, current.subnet)
    }

    private fun broken(current: Epoch) = guarded {
        if (epoch !== current) return@guarded
        clearReader()
        val replacement = Epoch(current.link, sink.advanceNetwork(), current.subnet)
        epoch = replacement
        val delay = (1_000L shl failures.coerceAtMost(3))
        failures = (failures + 1).coerceAtMost(3)
        report(DiscoveryReceiverState.RETRYING)
        retry = platform.later(delay) {
            guarded {
                if (epoch === replacement) {
                    retry = null
                    open(replacement)
                }
            }
        }
    }

    private fun scheduleTick() {
        ticker = platform.later(1_000) {
            guarded {
                ticker = null
                sink.tick()
                scheduleTick()
            }
        }
    }

    private inline fun guarded(action: () -> Unit) {
        if (!started || closed || failed) return
        try { action() } catch (_: Exception) { fail() }
    }

    private fun report(next: DiscoveryReceiverState) {
        if (state == next) return
        state = next
        runCatching { sink.onStateChanged(next) }
    }

    private fun clearReader() {
        epoch = null
        retry?.let { runCatching { it.cancel() } }
        retry = null
        reader?.let { runCatching { it.close() } }
        reader = null
    }

    private fun fail() {
        failed = true
        clearReader()
        ticker?.let { runCatching { it.cancel() } }
        ticker = null
        watcher?.let { runCatching { it.cancel() } }
        watcher = null
        links.clear()
        report(DiscoveryReceiverState.FAILED)
    }

    fun close() {
        if (closed) return
        closed = true
        clearReader()
        ticker?.let { runCatching { it.cancel() } }
        ticker = null
        watcher?.let { runCatching { it.cancel() } }
        watcher = null
        links.clear()
    }
}
