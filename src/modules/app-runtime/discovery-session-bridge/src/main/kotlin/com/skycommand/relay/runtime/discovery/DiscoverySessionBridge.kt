package com.skycommand.relay.runtime.discovery

import com.skycommand.relay.discovery.DiscoveryClock
import com.skycommand.relay.discovery.DiscoveryCoordinator
import com.skycommand.relay.discovery.DiscoveryNetworkGeneration
import com.skycommand.relay.discovery.DiscoverySelectionToken
import com.skycommand.relay.discovery.DiscoverySnapshot
import com.skycommand.relay.discovery.Ipv4Subnet
import com.skycommand.relay.gateway.session.ConnectionSelection
import com.skycommand.relay.gateway.session.ConnectionSelectionResult
import com.skycommand.relay.gateway.session.Registration
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.SessionPairingAdmission
import com.skycommand.relay.gateway.session.SessionStateEvent
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Caller owns the network and tick source. This bridge owns only selection policy. */
class DiscoverySessionBridge(
    clock: DiscoveryClock = DiscoveryClock { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private data class Mapping(val token: DiscoverySelectionToken, val selection: ConnectionSelection)
    private data class Admission(val token: DiscoverySelectionToken, val generation: SessionGeneration)

    private val coordinator = DiscoveryCoordinator(clock)
    private val stateLock = ReentrantLock()
    private val controlLock = ReentrantLock()
    private var running = false
    private var closed = false
    private var mapping: Mapping? = null
    private var admitted: Admission? = null
    private var lastSnapshot = coordinator.snapshot()
    // The following fields belong exclusively to controlLock.
    private var port: DiscoverySessionPort? = null
    private var registration: Registration? = null
    private var appliedKnown = false
    private var appliedSelection: ConnectionSelection? = null

    val pairingAdmission = SessionPairingAdmission { selection, generation ->
        stateLock.withLock {
            if (!running || closed) return@withLock false
            // A new attempt proves the previous admitted generation has ended,
            // even if its async end notification is still waiting for delivery.
            admitted?.takeIf { it.generation != generation }?.let { releaseAdmissionLocked(it) }
            refreshLocked()
            val current = mapping ?: return@withLock false
            if (selection !== current.selection || !coordinator.activate(current.token)) {
                refreshLocked()
                return@withLock false
            }
            admitted = Admission(current.token, generation)
            refreshLocked()
            true
        }
    }

    fun bind(sessionPort: DiscoverySessionPort) = controlLock.withLock {
        check(port == null && !stateLock.withLock { closed }) { "Discovery bridge is already bound or closed" }
        registration = sessionPort.onStateChanged(::handleStateEvent)
        port = sessionPort
    }

    /** True for running or newly started, false once permanently closed. */
    fun start(): Boolean = controlLock.withLock {
        if (stateLock.withLock { closed }) return false
        val currentPort = checkNotNull(port) { "Discovery bridge must be bound before start" }
        stateLock.withLock {
            if (closed) return false
            if (running) return true
            running = true
            refreshLocked()
        }
        try {
            reconcileLocked(currentPort)
            if (stateLock.withLock { !closed }) currentPort.start()
            !stateLock.withLock { closed }
        } catch (failure: Throwable) {
            stateLock.withLock {
                running = false
                admitted?.let(::releaseAdmissionLocked)
                coordinator.advanceNetwork()
                refreshLocked()
            }
            appliedKnown = false
            runCatching { currentPort.stop() }.exceptionOrNull()?.let {
                if (it !== failure) failure.addSuppressed(it)
            }
            throw failure
        }
    }

    fun networkGeneration(): DiscoveryNetworkGeneration = stateLock.withLock { coordinator.networkGeneration() }

    fun advanceNetwork(): DiscoveryNetworkGeneration {
        val generation = stateLock.withLock {
            if (closed) return@withLock coordinator.networkGeneration()
            coordinator.advanceNetwork().also { refreshLocked() }
        }
        reconcile()
        return generation
    }

    fun receive(
        networkGeneration: DiscoveryNetworkGeneration,
        bytes: ByteArray,
        sourceIpv4: String,
        receiverSubnet: Ipv4Subnet? = null,
    ): DiscoverySnapshot {
        stateLock.withLock {
            if (running && !closed) {
                coordinator.receive(networkGeneration, bytes, sourceIpv4, receiverSubnet)
                refreshLocked()
            }
        }
        reconcile()
        return snapshot()
    }

    fun tick(): DiscoverySnapshot {
        reconcile()
        return snapshot()
    }

    fun snapshot(): DiscoverySnapshot = stateLock.withLock { lastSnapshot }

    private fun handleStateEvent(event: SessionStateEvent) {
        stateLock.withLock {
            if (!running || closed) return
            admitted?.takeIf { it.generation == event.endedGeneration }?.let(::releaseAdmissionLocked)
            refreshLocked()
        }
        reconcile()
    }

    private fun releaseAdmissionLocked(previous: Admission) {
        coordinator.disconnected(previous.token)
        admitted = null
    }

    private fun refreshLocked() {
        lastSnapshot = coordinator.snapshot()
        val target = lastSnapshot.target.takeIf { running && !closed }
        mapping = when {
            target == null -> null
            mapping?.token === target.selectionToken -> mapping
            else -> {
                val validated = ConnectionSelection.create(target.endpoint)
                check(validated is ConnectionSelectionResult.Valid) { "Discovery endpoint failed session validation" }
                Mapping(target.selectionToken, validated.selection)
            }
        }
    }

    private fun reconcile() {
        controlLock.withLock {
            if (!stateLock.withLock { running && !closed }) return
            port?.let(::reconcileLocked)
        }
    }

    private fun reconcileLocked(currentPort: DiscoverySessionPort) {
        while (true) {
            val observedAdmission = stateLock.withLock { admitted }
            val sessionSnapshot = currentPort.snapshot()
            val desired = stateLock.withLock {
                if (!running || closed) return
                if (observedAdmission != null && admitted === observedAdmission &&
                    sessionSnapshot.generation != observedAdmission.generation
                ) {
                    releaseAdmissionLocked(observedAdmission)
                }
                refreshLocked()
                mapping?.selection
            }
            if (appliedKnown && appliedSelection === desired) return
            // No state lock while session waits for its serialized event loop.
            // A throwing adapter might already have changed the session. Do not
            // trust the previous applied value if the next call fails halfway.
            appliedKnown = false
            currentPort.selectConnection(desired)
            appliedSelection = desired
            appliedKnown = true
            // Selection/gate/network/close may have changed during that call.
        }
    }

    override fun close() {
        val firstClose = stateLock.withLock {
            if (closed) false else {
                closed = true
                running = false
                admitted?.let(::releaseAdmissionLocked)
                coordinator.advanceNetwork()
                refreshLocked()
                true
            }
        }
        if (!firstClose) return
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        val listener = controlLock.withLock {
            port?.let { currentPort ->
                attempt { currentPort.selectConnection(null) }
                attempt { currentPort.stop() }
            }
            registration.also { registration = null }
        }
        // An in-flight listener can be waiting for controlLock; release it first.
        attempt { listener?.unregister() }
        failure?.let { throw it }
    }
}
