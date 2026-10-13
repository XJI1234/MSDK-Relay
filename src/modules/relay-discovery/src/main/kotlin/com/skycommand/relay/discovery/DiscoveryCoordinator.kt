package com.skycommand.relay.discovery

fun interface DiscoveryClock { fun nowMillis(): Long }

/** Opaque identities: compare references, never serialize them. */
class DiscoveryNetworkGeneration internal constructor()
class DiscoverySelectionToken internal constructor()

class DiscoveryTarget internal constructor(
    val identity: CandidateIdentity,
    val endpoint: String,
    val selectionToken: DiscoverySelectionToken,
)

enum class DiscoveryPhase { WAITING, OBSERVING, CONFLICT, SELECTED, ACTIVE }

data class DiscoverySnapshot(
    val phase: DiscoveryPhase,
    val candidateCount: Int,
    val target: DiscoveryTarget?,
    val capacityIsolation: Boolean,
)

/** Pure policy: the runtime consumes stable targets; only the session owns retries. */
class DiscoveryCoordinator(private val clock: DiscoveryClock = DiscoveryClock { System.nanoTime() / 1_000_000 }) {
    private data class Endpoint(val announcement: RelayAnnouncement, val receivedAt: Long)
    private val candidates = linkedMapOf<CandidateIdentity, MutableMap<String, Endpoint>>()
    private var generation = DiscoveryNetworkGeneration()
    private var lastNow = Long.MIN_VALUE
    private var observedIdentity: CandidateIdentity? = null
    private var observedAt = 0L
    private var isolationStarted: Long? = null
    private var selected: DiscoveryTarget? = null
    private var active: DiscoveryTarget? = null

    @Synchronized
    fun networkGeneration(): DiscoveryNetworkGeneration = generation

    @Synchronized
    fun advanceNetwork(): DiscoveryNetworkGeneration {
        generation = DiscoveryNetworkGeneration()
        candidates.clear()
        isolationStarted = null
        resetSelection()
        return generation
    }

    @Synchronized
    fun receive(
        networkGeneration: DiscoveryNetworkGeneration,
        bytes: ByteArray,
        sourceIpv4: String,
        receiverSubnet: Ipv4Subnet? = null,
    ): DiscoverySnapshot {
        val now = now()
        expire(now)
        if (networkGeneration !== generation || isolationStarted != null) return evaluate(now)
        val parsed = AnnouncementParser.parse(bytes, sourceIpv4, receiverSubnet)
        if (parsed !is AnnouncementParseResult.Accepted) return evaluate(now)
        val announcement = parsed.announcement
        var endpoints = candidates[announcement.identity]
        if (endpoints == null) {
            if (candidates.size >= DiscoveryLimits.MAX_CANDIDATES) return isolate(now)
            endpoints = linkedMapOf()
            candidates[announcement.identity] = endpoints
        }
        if (announcement.endpoint !in endpoints && endpoints.size >= DiscoveryLimits.MAX_ENDPOINTS_PER_CANDIDATE) {
            return isolate(now)
        }
        endpoints[announcement.endpoint] = Endpoint(announcement, now)
        return evaluate(now)
    }

    @Synchronized
    fun snapshot(): DiscoverySnapshot = evaluate(now())

    @Synchronized
    fun activate(selectionToken: DiscoverySelectionToken): Boolean {
        val current = evaluate(now())
        if (current.phase == DiscoveryPhase.ACTIVE) return active?.selectionToken === selectionToken
        if (current.phase != DiscoveryPhase.SELECTED || selected?.selectionToken !== selectionToken) return false
        active = selected
        return true
    }

    @Synchronized
    fun disconnected(selectionToken: DiscoverySelectionToken): DiscoverySnapshot {
        if (active?.selectionToken === selectionToken) {
            active = null
            resetSelection()
        }
        return evaluate(now())
    }

    private fun now(): Long = clock.nowMillis().coerceAtLeast(lastNow).also { lastNow = it }

    private fun resetSelection() {
        selected = null
        observedIdentity = null
    }

    private fun isolate(now: Long): DiscoverySnapshot {
        isolationStarted = now
        resetSelection()
        return evaluate(now)
    }

    private fun expire(now: Long) {
        val isolatedAt = isolationStarted
        if (isolatedAt != null && now - isolatedAt >= DiscoveryLimits.EXPIRY_MILLIS) {
            candidates.clear()
            isolationStarted = null
            resetSelection()
        }
        val iterator = candidates.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            candidate.value.entries.removeAll { now - it.value.receivedAt >= DiscoveryLimits.EXPIRY_MILLIS }
            if (candidate.value.isEmpty()) iterator.remove()
        }
        if (observedIdentity != null && observedIdentity !in candidates) resetSelection()
    }

    private fun evaluate(now: Long): DiscoverySnapshot {
        expire(now)
        active?.let { return result(DiscoveryPhase.ACTIVE, it) }
        if (isolationStarted != null || candidates.size > 1) {
            resetSelection()
            return result(DiscoveryPhase.CONFLICT)
        }
        if (candidates.isEmpty()) {
            resetSelection()
            return result(DiscoveryPhase.WAITING)
        }
        val candidate = candidates.entries.single()
        if (observedIdentity != candidate.key) {
            resetSelection()
            observedIdentity = candidate.key
            observedAt = now
        }
        if (now - observedAt < DiscoveryLimits.OBSERVATION_MILLIS) return result(DiscoveryPhase.OBSERVING)
        val endpoint = candidate.value.values.sortedWith(
            compareByDescending<Endpoint> { it.receivedAt }
                .thenBy { it.announcement.sourceIpv4 }
                .thenBy { it.announcement.relayPort },
        ).first().announcement.endpoint
        if (selected?.identity != candidate.key || selected?.endpoint != endpoint) {
            selected = DiscoveryTarget(candidate.key, endpoint, DiscoverySelectionToken())
        }
        return result(DiscoveryPhase.SELECTED, selected)
    }

    private fun result(phase: DiscoveryPhase, target: DiscoveryTarget? = null) =
        DiscoverySnapshot(phase, candidates.size, target, isolationStarted != null)
}
