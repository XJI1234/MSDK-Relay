package com.skycommand.relay.runtime.discovery

import com.skycommand.relay.discovery.*
import com.skycommand.relay.gateway.session.ConnectionSelection
import com.skycommand.relay.gateway.session.ConnectionSelectionResult
import com.skycommand.relay.gateway.session.SessionPairingAdmission
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class RelayConnectionMode { AUTO, MANUAL }

data class RelayConnectionControlSnapshot(
    val mode: RelayConnectionMode,
    val running: Boolean,
    val failed: Boolean,
    val discovery: DiscoverySnapshot?,
)

interface DiscoverySource : AutoCloseable {
    fun start()
}

interface DiscoverySourceSink {
    fun advanceNetwork(): DiscoveryNetworkGeneration
    fun receive(generation: DiscoveryNetworkGeneration, bytes: ByteArray, sourceIpv4: String, subnet: Ipv4Subnet)
    fun tick()
    fun failed()
}

/** create must not activate resources or call the sink before source.start(). */
fun interface DiscoverySourceFactory { fun create(sink: DiscoverySourceSink): DiscoverySource }

/** The gateway's sole controller. Sources must not wait for sink callbacks on close. */
class RelayConnectionControl(
    private val sourceFactory: DiscoverySourceFactory,
    private val clock: DiscoveryClock = DiscoveryClock { System.nanoTime() / 1_000_000 },
    private val onChanged: () -> Unit = {},
) : AutoCloseable {
    private class Epoch {
        var bridge: DiscoverySessionBridge? = null
        var source: DiscoverySource? = null
        var network: DiscoveryNetworkGeneration? = null
        var sessionNeedsStop = false
    }
    private val controlLock = ReentrantLock()
    private val gateLock = Any()
    private var gate: SessionPairingAdmission? = null
    private var port: DiscoverySessionPort? = null
    private var epoch: Epoch? = null
    private var mode = RelayConnectionMode.AUTO
    private var manualEndpoint: String? = null
    private var running = false
    private var failed = false
    private var closed = false
    @Volatile private var published = RelayConnectionControlSnapshot(mode, false, false, null)

    val pairingAdmission = SessionPairingAdmission { selection, generation ->
        synchronized(gateLock) { gate?.admit(selection, generation) ?: false }
    }

    fun bind(sessionPort: DiscoverySessionPort) = controlLock.withLock {
        check(port == null && !closed) { "Connection control already bound or closed" }
        port = sessionPort
    }

    fun snapshot(): RelayConnectionControlSnapshot = published

    fun start(): Boolean = change {
        if (closed) return@change false
        checkNotNull(port) { "Connection control must be bound before start" }
        if (running) true else activateLocked()
    }

    fun manual(endpoint: String): Boolean {
        val validated = ConnectionSelection.create(endpoint)
        if (validated !is ConnectionSelectionResult.Valid) return false
        return change {
            if (closed) return@change false
            if (mode == RelayConnectionMode.MANUAL && manualEndpoint == endpoint && !failed) return@change true
            val shouldRun = running || failed
            if (!disposeLocked()) { failed = true; return@change false }
            mode = RelayConnectionMode.MANUAL
            manualEndpoint = endpoint
            failed = false
            if (shouldRun) activateLocked(validated.selection) else true
        }
    }

    fun automatic(): Boolean = change {
        if (closed) return@change false
        if (mode == RelayConnectionMode.AUTO && !failed) return@change true
        val shouldRun = running || failed
        if (!disposeLocked()) { failed = true; return@change false }
        mode = RelayConnectionMode.AUTO
        manualEndpoint = null
        failed = false
        if (shouldRun) activateLocked() else true
    }

    fun stop(): Boolean = change {
        val cleaned = disposeLocked()
        failed = !cleaned
        cleaned
    }

    override fun close() {
        change {
            closed = true
            failed = !disposeLocked()
        }
    }

    private fun activateLocked(manualSelection: ConnectionSelection? = null): Boolean {
        if (!disposeLocked()) { failed = true; return false }
        val session = checkNotNull(port)
        val current = Epoch().also { epoch = it }
        running = true
        failed = false
        try {
            if (mode == RelayConnectionMode.AUTO) {
                val bridge = DiscoverySessionBridge(clock)
                current.bridge = bridge
                current.network = bridge.networkGeneration()
                synchronized(gateLock) { gate = bridge.pairingAdmission }
                bridge.bind(session)
                check(bridge.start())
                // The source handle is stored before start, so partial starts roll back.
                val source = sourceFactory.create(sourceSink(current))
                current.source = source
                source.start()
            } else {
                val selection = manualSelection ?: (ConnectionSelection.create(checkNotNull(manualEndpoint))
                    as ConnectionSelectionResult.Valid).selection
                synchronized(gateLock) { gate = SessionPairingAdmission { selected, _ -> selected === selection } }
                current.sessionNeedsStop = true
                session.selectConnection(selection)
                session.start()
            }
            // A synchronous source failure may have already retired this epoch.
            return running && epoch === current
        } catch (_: Exception) {
            failLocked()
            return false
        }
    }

    private fun sourceSink(current: Epoch) = object : DiscoverySourceSink {
        override fun advanceNetwork(): DiscoveryNetworkGeneration = change {
            if (!isCurrent(current)) return@change checkNotNull(current.network)
            try { checkNotNull(current.bridge).advanceNetwork().also { current.network = it } }
            catch (_: Exception) { failLocked(); checkNotNull(current.network) }
        }
        override fun receive(generation: DiscoveryNetworkGeneration, bytes: ByteArray, sourceIpv4: String, subnet: Ipv4Subnet) {
            sourceAction(current) { checkNotNull(current.bridge).receive(generation, bytes, sourceIpv4, subnet) }
        }
        override fun tick() { sourceAction(current) { checkNotNull(current.bridge).tick() } }
        override fun failed() { change { if (isCurrent(current)) failLocked() } }
    }

    private fun sourceAction(current: Epoch, action: () -> Unit) {
        change {
            if (isCurrent(current)) {
                try { action() } catch (_: Exception) { failLocked() }
            }
        }
    }

    private fun isCurrent(current: Epoch): Boolean = !closed && running && epoch === current

    private fun failLocked() {
        disposeLocked()
        failed = true
    }

    /** Preserve failed cleanup handles, blocking a second source until retry succeeds. */
    private fun disposeLocked(): Boolean {
        synchronized(gateLock) { gate = null }
        running = false
        val current = epoch ?: return true
        var clean = true
        current.source?.let {
            try { it.close(); current.source = null } catch (_: Exception) { clean = false }
        }
        current.bridge?.let {
            try { it.close(); current.bridge = null }
            catch (_: Exception) { clean = false; current.sessionNeedsStop = true }
        }
        if (current.sessionNeedsStop) {
            var sessionClean = true
            try { port?.selectConnection(null) } catch (_: Exception) { sessionClean = false }
            try { port?.stop() } catch (_: Exception) { sessionClean = false }
            current.sessionNeedsStop = !sessionClean
            clean = clean && sessionClean
        }
        if (clean) epoch = null
        return clean
    }

    private inline fun <T> change(action: () -> T): T {
        val result = controlLock.withLock {
            try { action() } finally {
                published = RelayConnectionControlSnapshot(mode, running, failed, epoch?.bridge?.snapshot().takeIf { running })
            }
        }
        // Reentrant source callbacks defer notification to the outer control call.
        if (!controlLock.isHeldByCurrentThread) runCatching { onChanged() }
        return result
    }
}
