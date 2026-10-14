package com.skycommand.relay.runtime.discovery

import com.skycommand.relay.discovery.DiscoveryClock
import com.skycommand.relay.discovery.DiscoveryNetworkGeneration
import com.skycommand.relay.gateway.session.*
import com.skycommand.relay.protocol.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs

internal class BridgeFixture {
    @Volatile var now = 0L
    val bridge = DiscoverySessionBridge(DiscoveryClock { now })
    val attempts = CopyOnWriteArrayList<Connection>()
    val tasks = CopyOnWriteArrayList<Task>()
    val notifications = CopyOnWriteArrayList<Pair<SessionStateEvent, List<SessionStateListener>>>()
    val frames = CopyOnWriteArrayList<ActiveSession>()
    val cleanups = CopyOnWriteArrayList<SessionGeneration>()
    var autoPair = false
    var beforeCancel: () -> Unit = { }
    val session = assertIs<SessionCreated>(ConnectionSession.create(
        SessionConfig(null, "bridge-test-device"),
        SessionDependencies(
            connector = TransportConnector { endpoint, generation, listener ->
                TransportOpenResult.OpenAccepted(Connection(endpoint, generation, listener).also(attempts::add))
            },
            outbound = object : SessionOutbound {
                override fun attach(generation: SessionGeneration, writer: TransportWriter) = AttachResult.AttachAccepted
                override fun sendHandshake(generation: SessionGeneration, frame: HelloFrame) = HandshakeSendResult.SendAccepted
                override fun discard(generation: SessionGeneration) { cleanups += generation }
            },
            activeFrameConsumer = ActiveFrameConsumer { active, _ -> frames += active },
            commandCleanup = CommandSessionCleanup { _, _ -> },
            missionCleanup = MissionSessionCleanup { _, _ -> },
            scheduler = MonotonicScheduler { delay, callback ->
                Task(delay, callback).also(tasks::add).let { task -> ScheduledCancellation { beforeCancel(); task.cancelled = true } }
            },
            stateNotifier = OrderedStateNotifier { event, listeners -> notifications += event to listeners },
            pairingAdmission = bridge.pairingAdmission,
        ),
    )).session
    val port = Port()

    init { bridge.bind(port) }

    inner class Port : DiscoverySessionPort {
        var starts = 0
        var stops = 0
        var unregistered = false
        var beforeSelect: (ConnectionSelection?) -> Unit = { }
        var afterSelect: (ConnectionSelection?) -> Unit = { }
        var beforeStart: () -> Unit = { }
        val selections = CopyOnWriteArrayList<ConnectionSelection?>()
        override fun start() { starts++; beforeStart(); session.start() }
        override fun stop() { stops++; session.stop() }
        override fun selectConnection(selection: ConnectionSelection?) {
            beforeSelect(selection)
            selections += selection
            session.selectConnection(selection)
            afterSelect(selection)
        }
        override fun snapshot() = session.snapshot()
        override fun onStateChanged(listener: SessionStateListener): Registration {
            val registration = session.onStateChanged(listener)
            return Registration { registration.unregister(); unregistered = true }
        }
    }

    inner class Connection(
        val endpoint: String,
        override val generation: SessionGeneration,
        private val listener: TransportListener,
    ) : TransportConnection {
        var closes = 0
        override val writer = TransportWriter { TransportWriteResult.WriteAccepted }
        override fun enableCallbacks() { if (autoPair) pair() }
        override fun close(reason: String): TransportCloseResult {
            if (closes > 0) return TransportCloseResult.AlreadyClosed
            closes++
            return TransportCloseResult.CloseRequested
        }
        fun pair() { listener.onOpened(this); receive(PairedFrame("bridge-session", null)) }
        fun receive(frame: RelayFrame) {
            val bytes = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value
            listener.onBytes(generation, bytes)
        }
        fun fail() { listener.onFailure(generation, "Transport failed") }
        fun command() { receive(CommandFrame("bridge-command", "telemetry.read", JsonObject(emptyMap()))) }
    }

    class Task(val delay: Long, private val callback: () -> Unit) {
        var cancelled = false
        var fired = false
        fun fire() { fired = true; callback() }
    }

    val current get() = attempts.last()
    fun retry() { tasks.first { !it.cancelled && !it.fired }.fire() }
    fun receive(
        candidate: Int = 0,
        source: String = "192.168.1.20",
        network: DiscoveryNetworkGeneration = bridge.networkGeneration(),
    ) = bridge.receive(network, announcement(candidate), source)
    fun select() { bridge.start(); receive(); now = 3000; bridge.tick() }
    fun active() { select(); current.pair() }
    fun drain() {
        val pending = notifications.toList()
        notifications.removeAll(pending.toSet())
        pending.forEach { (event, listeners) -> listeners.forEach { it.onStateChanged(event) } }
    }
}

internal fun announcement(candidate: Int = 0): ByteArray = (
    """{"kind":"SKY_COMMAND_RELAY_ANNOUNCEMENT","discoveryVersion":1,"serverId":"11111111-1111-4111-8111-%012d","instanceId":"22222222-2222-4222-8222-222222222222","protocolVersion":"1","relayPort":8080,"relayPath":"/relay"}""".format(candidate)
).toByteArray(Charsets.UTF_8)
